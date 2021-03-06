/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.grpc.v1_5.server;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.config.Config;
import io.opentelemetry.instrumentation.grpc.v1_5.common.GrpcHelper;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;

public class TracingServerInterceptor implements ServerInterceptor {

  private static final boolean CAPTURE_EXPERIMENTAL_SPAN_ATTRIBUTES =
      Config.get()
          .getBooleanProperty("otel.instrumentation.grpc.experimental-span-attributes", false);

  public static ServerInterceptor newInterceptor() {
    return newInterceptor(new GrpcServerTracer());
  }

  public static ServerInterceptor newInterceptor(Tracer tracer) {
    return newInterceptor(new GrpcServerTracer(tracer));
  }

  public static ServerInterceptor newInterceptor(GrpcServerTracer tracer) {
    return new TracingServerInterceptor(tracer);
  }

  private final GrpcServerTracer tracer;

  private TracingServerInterceptor(GrpcServerTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public <REQUEST, RESPONSE> ServerCall.Listener<REQUEST> interceptCall(
      ServerCall<REQUEST, RESPONSE> call,
      Metadata headers,
      ServerCallHandler<REQUEST, RESPONSE> next) {

    String methodName = call.getMethodDescriptor().getFullMethodName();
    Context context = tracer.startSpan(methodName, headers);
    Span span = Span.fromContext(context);

    SocketAddress address = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
    if (address instanceof InetSocketAddress) {
      InetSocketAddress inetSocketAddress = (InetSocketAddress) address;
      span.setAttribute(SemanticAttributes.NET_PEER_PORT, inetSocketAddress.getPort());
      span.setAttribute(
          SemanticAttributes.NET_PEER_IP, inetSocketAddress.getAddress().getHostAddress());
    }
    GrpcHelper.prepareSpan(span, methodName);

    try (Scope ignored = context.makeCurrent()) {
      return new TracingServerCallListener<>(
          next.startCall(new TracingServerCall<>(call, context, tracer), headers), context, tracer);
    } catch (Throwable e) {
      tracer.endExceptionally(context, e);
      throw e;
    }
  }

  static final class TracingServerCall<REQUEST, RESPONSE>
      extends ForwardingServerCall.SimpleForwardingServerCall<REQUEST, RESPONSE> {
    private final Context context;
    private final GrpcServerTracer tracer;

    TracingServerCall(
        ServerCall<REQUEST, RESPONSE> delegate, Context context, GrpcServerTracer tracer) {
      super(delegate);
      this.context = context;
      this.tracer = tracer;
    }

    @Override
    public void close(Status status, Metadata trailers) {
      tracer.setStatus(context, status);
      try (Scope ignored = context.makeCurrent()) {
        delegate().close(status, trailers);
      } catch (Throwable e) {
        tracer.endExceptionally(context, e);
        throw e;
      }
    }
  }

  static final class TracingServerCallListener<REQUEST>
      extends ForwardingServerCallListener.SimpleForwardingServerCallListener<REQUEST> {
    private final Context context;
    private final GrpcServerTracer tracer;

    private final AtomicLong messageId = new AtomicLong();

    TracingServerCallListener(
        Listener<REQUEST> delegate, Context context, GrpcServerTracer tracer) {
      super(delegate);
      this.context = context;
      this.tracer = tracer;
    }

    @Override
    public void onMessage(REQUEST message) {
      // TODO(anuraaga): Restore
      Attributes attributes =
          Attributes.of(
              GrpcHelper.MESSAGE_TYPE,
              "RECEIVED",
              GrpcHelper.MESSAGE_ID,
              messageId.incrementAndGet());
      Span.fromContext(context).addEvent("message", attributes);
      try (Scope ignored = context.makeCurrent()) {
        delegate().onMessage(message);
      }
    }

    @Override
    public void onHalfClose() {
      try (Scope ignored = context.makeCurrent()) {
        delegate().onHalfClose();
      } catch (Throwable e) {
        tracer.endExceptionally(context, e);
        throw e;
      }
    }

    @Override
    public void onCancel() {
      try (Scope ignored = context.makeCurrent()) {
        delegate().onCancel();
        if (CAPTURE_EXPERIMENTAL_SPAN_ATTRIBUTES) {
          Span.fromContext(context).setAttribute("grpc.canceled", true);
        }
      } catch (Throwable e) {
        tracer.endExceptionally(context, e);
        throw e;
      }
      tracer.end(context);
    }

    @Override
    public void onComplete() {
      try (Scope ignored = context.makeCurrent()) {
        delegate().onComplete();
      } catch (Throwable e) {
        tracer.endExceptionally(context, e);
        throw e;
      }
      tracer.end(context);
    }

    @Override
    public void onReady() {
      try (Scope ignored = context.makeCurrent()) {
        delegate().onReady();
      } catch (Throwable e) {
        tracer.endExceptionally(context, e);
        throw e;
      }
    }
  }
}
