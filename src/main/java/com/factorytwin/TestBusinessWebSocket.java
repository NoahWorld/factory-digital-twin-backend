package com.factorytwin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Anonymous, read-only, bounded test source; never carries tenant or customer data. */
@Configuration
public class TestBusinessWebSocket extends TextWebSocketHandler implements WebSocketConfigurer {
  final TestBusiness business;
  final RequestFilter origins;
  final Map<String, Connection> connections = new ConcurrentHashMap<>();
  final Logger log = LoggerFactory.getLogger(TestBusinessWebSocket.class);
  static class Connection {
    final WebSocketSession socket;
    final String origin;
    final boolean internal;
    long lastPong = System.currentTimeMillis(), lastPing;
    Connection(WebSocketSession socket) {
      this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 65536);
      origin = (String) socket.getAttributes().get("origin");
      internal = Boolean.TRUE.equals(socket.getAttributes().get("internal"));
    }
  }
  public TestBusinessWebSocket(TestBusiness business, RequestFilter origins) { this.business = business; this.origins = origins; }
  HandshakeInterceptor interceptor() {
    return new HandshakeInterceptor() {
      public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        // The connector's bound port is trusted; request Host and forwarded authority are not.
        boolean internal = request instanceof ServletServerHttpRequest servlet && origin != null
            && origin.equals("http://127.0.0.1:" + servlet.getServletRequest().getLocalPort())
            && request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null
            && request.getRemoteAddress().getAddress().isLoopbackAddress();
        if (!internal && !origins.allows(origin)) {
          response.setStatusCode(HttpStatus.FORBIDDEN);
          log.warn("test_business_origin_rejected origin={}", origin);
          return false;
        }
        attributes.put("origin", origin); attributes.put("internal", internal); return true;
      }
      public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception error) {}
    };
  }
  @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(this, "/api/v1/test-business/handling-cell/live").setAllowedOriginPatterns("*").addInterceptors(interceptor());
  }
  @Override public synchronized void afterConnectionEstablished(WebSocketSession socket) throws Exception {
    if (connections.size() >= 128) { socket.close(CloseStatus.SERVICE_OVERLOAD); return; }
    socket.setTextMessageSizeLimit(8192);
    var connection = new Connection(socket); connections.put(socket.getId(), connection);
    log.info("test_business_connected connection={} count={}", socket.getId(), connections.size());
    try { connection.socket.sendMessage(new TextMessage(business.state().toString())); }
    catch (IOException | IllegalStateException error) { failed(connection, error); }
  }
  @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
    // The endpoint has no commands or subscription protocol. Reject rather than ignore a request.
    var connection = connections.get(socket.getId());
    if (connection != null) close(connection, CloseStatus.POLICY_VIOLATION.withReason("Read-only source; no text commands."));
  }
  @Override protected void handlePongMessage(WebSocketSession socket, PongMessage message) {
    var connection = connections.get(socket.getId());
    if (connection != null) connection.lastPong = System.currentTimeMillis();
  }
  @Scheduled(fixedDelay = 200)
  public void push() {
    String state = business.state().toString(); long now = System.currentTimeMillis();
    for (var connection : connections.values()) synchronized (connection) {
      try {
        if ((!connection.internal && !origins.allows(connection.origin)) || now - connection.lastPong > 45000) { close(connection, CloseStatus.POLICY_VIOLATION); continue; }
        if (!connection.socket.isOpen()) { connections.remove(connection.socket.getId(), connection); continue; }
        if (now - connection.lastPing >= 15000) { connection.socket.sendMessage(new PingMessage(ByteBuffer.allocate(0))); connection.lastPing = now; }
        connection.socket.sendMessage(new TextMessage(state));
      } catch (IOException | IllegalStateException error) { failed(connection, error); }
    }
  }
  void failed(Connection connection, Exception error) { log.warn("test_business_transport_failed connection={}", connection.socket.getId(), error); close(connection, CloseStatus.SERVER_ERROR); }
  void close(Connection connection, CloseStatus status) {
    try { if (connection.socket.isOpen()) connection.socket.close(status); }
    catch (IOException | IllegalStateException error) { log.warn("test_business_close_failed connection={}", connection.socket.getId(), error); }
    finally { connections.remove(connection.socket.getId(), connection); }
  }
  @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) { connections.remove(socket.getId()); log.info("test_business_disconnected connection={} status={}", socket.getId(), status); }
  @Override public void handleTransportError(WebSocketSession socket, Throwable error) {
    var connection = connections.get(socket.getId());
    if (connection != null) { log.warn("test_business_transport_failed connection={}", socket.getId(), error); close(connection, CloseStatus.SERVER_ERROR); }
  }
}
