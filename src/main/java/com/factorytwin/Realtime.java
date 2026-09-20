package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
public class Realtime extends TextWebSocketHandler implements WebSocketConfigurer {
  final Auth auth;
  final Projects projects;
  final RuntimeState runtime;
  final RequestFilter origins;
  final Logger log = LoggerFactory.getLogger(Realtime.class);

  record Subscription(String project, String cursor) {}

  static class Connection {
    final WebSocketSession socket;
    final String token;
    final Map<String, String> subscriptions = new HashMap<>();
    long lastHeartbeat = 0;

    Connection(WebSocketSession s, String token) {
      socket = new ConcurrentWebSocketSessionDecorator(s, 5000, 512 * 1024);
      this.token = token;
    }
  }

  final Map<String, Connection> connections = new ConcurrentHashMap<>();

  public Realtime(Auth auth, Projects p, RuntimeState runtime, RequestFilter origins) {
    this.auth = auth;
    projects = p;
    this.runtime = runtime;
    this.origins = origins;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(this, "/api/v1/realtime")
        .setAllowedOriginPatterns("*")
        .addInterceptors(
            new HandshakeInterceptor() {
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Map<String, Object> attributes) {
                try {
                  if (!origins.allows(request.getHeaders().getOrigin()))
                    throw new ApiException(
                        403, "origin_denied", "WebSocket Origin is not allowed.");
                  if (!(request instanceof ServletServerHttpRequest servlet))
                    throw new ApiException(400, "invalid_handshake", "Servlet request required.");
                  String token = auth.token(servlet.getServletRequest());
                  auth.fromToken(token);
                  attributes.put("token", token);
                  return true;
                } catch (ApiException e) {
                  response.setStatusCode(org.springframework.http.HttpStatus.valueOf(e.status));
                  return false;
                }
              }

              public void afterHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Exception error) {}
            });
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
    if (connections.size() >= 500) {
      socket.close(CloseStatus.SERVICE_OVERLOAD);
      return;
    }
    socket.setTextMessageSizeLimit(8192);
    Connection c = new Connection(socket, (String) socket.getAttributes().get("token"));
    connections.put(socket.getId(), c);
    send(
        c, Json.obj("type", "hello", "schemaVersion", 1, "maxProjects", 4, "heartbeatSeconds", 15));
  }

  @Override
  protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
    Connection c = connections.get(socket.getId());
    if (c == null) return;
    synchronized (c) {
      try {
        JsonNode m = Json.M.readTree(message.getPayload());
        Auth.User u = auth.fromToken(c.token);
        String type = m.path("type").asText();
        if (type.equals("ping")) {
          send(c, Json.obj("type", "pong"));
          return;
        }
        String project = Json.text(m, "projectId", 1, 120);
        if (type.equals("unsubscribe")) {
          c.subscriptions.remove(project);
          return;
        }
        Json.require(type.equals("subscribe"), "Expected subscribe, unsubscribe or ping.");
        projects.access(u, project, false);
        Json.require(
            c.subscriptions.size() < 4 || c.subscriptions.containsKey(project),
            "At most four project subscriptions per socket.");
        String cursor = m.path("cursor").asText("");
        if (cursor.isEmpty()) {
          JsonNode snapshot = runtime.snapshot(u.tenant(), project);
          send(c, snapshot);
          cursor = snapshot.path("cursor").asText();
        } else Json.require(cursor.matches("[0-9]{1,20}-[0-9]{1,20}"), "Invalid stream cursor.");
        c.subscriptions.put(project, cursor);
      } catch (ApiException e) {
        send(c, Json.obj("type", "error", "error", e.code, "message", e.getMessage()));
        if (e.status == 401) c.socket.close(CloseStatus.POLICY_VIOLATION);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        send(
            c,
            Json.obj(
                "type", "error", "error", "invalid_json", "message", "Invalid WebSocket JSON."));
      }
    }
  }

  void send(Connection c, Object message) throws java.io.IOException {
    c.socket.sendMessage(new TextMessage(Json.M.writeValueAsString(message)));
  }

  @Scheduled(fixedDelay = 250)
  public void push() {
    for (Connection c : connections.values())
      synchronized (c) {
        try {
          Auth.User u = auth.fromToken(c.token);
          for (var sub : new ArrayList<>(c.subscriptions.entrySet())) {
            String project = sub.getKey(), cursor = sub.getValue();
            projects.access(u, project, false);
            String stream = RuntimeState.key(u.tenant(), project) + ":stream";
            var first =
                runtime
                    .redis
                    .opsForStream()
                    .range(
                        stream,
                        Range.unbounded(),
                        org.springframework.data.redis.connection.Limit.limit().count(1));
            Object savedCursor =
                runtime
                    .redis
                    .opsForHash()
                    .get(RuntimeState.key(u.tenant(), project) + ":latest", "_cursor");
            String last = savedCursor == null ? "0-0" : savedCursor.toString();
            boolean reset =
                !cursor.equals("0-0")
                    && (last.equals("0-0")
                        || compare(cursor, last) > 0
                        || (first != null
                            && !first.isEmpty()
                            && compare(cursor, first.getFirst().getId().getValue()) < 0));
            if (reset) {
              JsonNode snapshot = runtime.snapshot(u.tenant(), project);
              send(
                  c,
                  Json.obj(
                      "type",
                      "resync_required",
                      "projectId",
                      project,
                      "reason",
                      "cursor_outside_retention"));
              send(c, snapshot);
              c.subscriptions.put(project, snapshot.path("cursor").asText());
              continue;
            }
            var records =
                runtime
                    .redis
                    .opsForStream()
                    .range(
                        stream,
                        Range.rightUnbounded(Range.Bound.exclusive(cursor)),
                        org.springframework.data.redis.connection.Limit.limit().count(32));
            if (records != null)
              for (var record : records) {
                send(
                    c,
                    Json.obj(
                        "type",
                        "source_update",
                        "schemaVersion",
                        1,
                        "projectId",
                        project,
                        "cursor",
                        record.getId().getValue(),
                        "sourceId",
                        record.getValue().get("sourceId"),
                        "data",
                        Json.parse(record.getValue().get("data").toString())));
                c.subscriptions.put(project, record.getId().getValue());
              }
          }
          if (System.currentTimeMillis() - c.lastHeartbeat > 15000) {
            send(
                c, Json.obj("type", "heartbeat", "serverTime", java.time.Instant.now().toString()));
            c.lastHeartbeat = System.currentTimeMillis();
          }
        } catch (Exception e) {
          log.warn(
              "realtime_connection_closed connection={} reason={}", c.socket.getId(), e.toString());
          connections.remove(c.socket.getId());
          try {
            c.socket.close(
                e instanceof ApiException
                    ? CloseStatus.POLICY_VIOLATION
                    : CloseStatus.SERVER_ERROR);
          } catch (java.io.IOException close) {
            log.warn("websocket_close_failed", close);
          }
        }
      }
  }

  static int compare(String a, String b) {
    String[] aa = a.split("-"), bb = b.split("-");
    int c = new java.math.BigInteger(aa[0]).compareTo(new java.math.BigInteger(bb[0]));
    return c == 0 ? new java.math.BigInteger(aa[1]).compareTo(new java.math.BigInteger(bb[1])) : c;
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    connections.remove(s.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession s, Throwable error) throws Exception {
    log.warn("websocket_transport_failed connection={}", s.getId(), error);
    connections.remove(s.getId());
    s.close(CloseStatus.SERVER_ERROR);
  }
}
