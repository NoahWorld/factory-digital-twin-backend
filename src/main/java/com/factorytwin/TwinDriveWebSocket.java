package com.factorytwin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Dedicated, authenticated control channel. Opening covers and reading documents never starts it. */
@Configuration
public class TwinDriveWebSocket extends TextWebSocketHandler implements WebSocketConfigurer {
  final Auth auth;
  final Projects projects;
  final Publications publications;
  final RequestFilter origins;
  final TwinDriveRuntime runtime;
  final Logger log = LoggerFactory.getLogger(TwinDriveWebSocket.class);
  final Map<String, Connection> connections = new ConcurrentHashMap<>();

  static class Connection {
    final WebSocketSession socket;
    final String token, share, project, origin;
    long revision = -1, sequence = -1, lastMessageAt = System.currentTimeMillis();
    long subscribedRevision = -1;
    Set<String> topics = Set.of();
    int messages = 0;
    long rateWindow = System.currentTimeMillis();
    Connection(WebSocketSession socket) {
      this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 512 * 1024);
      token = (String) socket.getAttributes().get("token");
      share = (String) socket.getAttributes().get("share");
      project = (String) socket.getAttributes().get("project");
      origin = (String) socket.getAttributes().get("origin");
    }
  }

  public TwinDriveWebSocket(Auth auth, Projects projects, Publications publications,
      RequestFilter origins, TwinDriveRuntime runtime) {
    this.auth = auth; this.projects = projects; this.publications = publications;
    this.origins = origins; this.runtime = runtime;
  }

  @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(this, "/api/v1/twin-drive").setAllowedOriginPatterns("*").addInterceptors(
        new HandshakeInterceptor() {
          public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
              WebSocketHandler handler, Map<String, Object> attributes) {
            try {
              String origin = request.getHeaders().getOrigin();
              if (!origins.allows(origin)) throw new ApiException(403, "origin_denied", "WebSocket Origin is not allowed.");
              if (!(request instanceof ServletServerHttpRequest servlet))
                throw new ApiException(400, "invalid_handshake", "Servlet request required.");
              String project = servlet.getServletRequest().getParameter("projectId");
              Json.require(project != null && project.length() <= 120, "A projectId is required.");
              Contracts.identifier(project);
              String share = servlet.getServletRequest().getParameter("share");
              String token = share == null ? auth.token(servlet.getServletRequest()) : null;
              Auth.User user = share == null ? auth.fromToken(token) : publications.reader(share, project);
              Json.require(projects.access(user, project, false).path("projectType").asText().equals("3d"), "A 3D project is required.");
              attributes.put("token", token); attributes.put("share", share);
              attributes.put("project", project); attributes.put("origin", origin);
              return true;
            } catch (ApiException error) {
              response.setStatusCode(HttpStatus.valueOf(error.status));
              log.warn("twin_handshake_rejected error={}", error.code);
              return false;
            }
          }
          public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception error) {}
        });
  }

  Auth.User authorize(Connection connection) {
    if (!origins.allows(connection.origin)) throw new ApiException(403, "origin_denied", "WebSocket Origin is no longer allowed.");
    Auth.User user = connection.share == null ? auth.fromToken(connection.token)
        : publications.reader(connection.share, connection.project);
    projects.access(user, connection.project, false);
    return user;
  }

  @Override public synchronized void afterConnectionEstablished(WebSocketSession socket) throws Exception {
    if (connections.size() >= 128) { socket.close(CloseStatus.SERVICE_OVERLOAD); return; }
    socket.setTextMessageSizeLimit(32768);
    Connection connection = new Connection(socket);
    connections.put(socket.getId(), connection);
    try { send(connection, Json.obj("type", "hello", "version", 1, "heartbeatSeconds", 10)); }
    catch (IOException | IllegalStateException error) { failConnection(connection, error, CloseStatus.SERVER_ERROR); }
    // The scheduled publisher sends the first snapshot, avoiding a handshake/command lock race.
  }

  @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
    Connection connection = connections.get(socket.getId());
    if (connection == null) return;
    synchronized (connection) {
      if (!connection.socket.isOpen()) { connections.remove(socket.getId(), connection); return; }
      String commandId = null;
      try {
        Auth.User user = authorize(connection);
        long now = System.currentTimeMillis();
        connection.lastMessageAt = now;
        if (now - connection.rateWindow >= 1000) { connection.rateWindow = now; connection.messages = 0; }
        if (++connection.messages > 40) throw new ApiException(429, "twin_socket_rate_limited", "At most 40 messages per connection per second.");
        JsonNode payload = Json.M.readTree(message.getPayload());
        Json.require(payload != null && payload.isObject(), "Expected a JSON object.");
        if (payload.path("commandId").isTextual() && payload.path("commandId").asText().length() <= 120)
          commandId = payload.path("commandId").asText();
        if (payload.path("type").asText().equals("ping")) {
          Json.fields(payload, "type"); send(connection, Json.obj("type", "pong")); return;
        }
        if (payload.path("type").asText().equals("subscribe")) {
          ObjectNode ack = runtime.subscribe(user, connection.project, (ObjectNode) payload);
          Set<String> topics = new HashSet<>(); ack.path("topics").forEach(topic -> topics.add(topic.asText()));
          connection.topics = Set.copyOf(topics);
          connection.subscribedRevision = ack.path("revision").asLong();
          connection.revision = connection.subscribedRevision;
          connection.sequence = -1; // A reconnect/resubscription receives the currently sampled values.
          send(connection, ack); return;
        }
        if (connection.share != null)
          throw new ApiException(403, "publication_read_only", "Publications cannot issue point commands.");
        send(connection, runtime.command(user, connection.project, (ObjectNode) payload));
      } catch (ApiException error) {
        sendError(connection, commandId, error.code, error.getMessage());
        log.warn("twin_command_rejected project={} connection={} command={} error={}", connection.project, socket.getId(), commandId, error.code);
        if (error.status == 401 || error.status == 403
            || (connection.share != null && error.status == 404))
          close(connection, CloseStatus.POLICY_VIOLATION);
      } catch (JsonProcessingException error) {
        sendError(connection, null, "invalid_json", "Invalid WebSocket JSON.");
      } catch (Exception error) {
        log.error("twin_command_failed project={} connection={} command={}", connection.project, socket.getId(), commandId, error);
        sendError(connection, commandId, "twin_runtime_failed", "The command result is uncertain; inspect the next snapshot before retrying the same command ID.");
        close(connection, CloseStatus.SERVER_ERROR);
      }
    }
  }

  void send(Connection connection, JsonNode message) throws IOException {
    connection.socket.sendMessage(new TextMessage(message.toString()));
  }

  void sendError(Connection connection, String commandId, String code, String message) {
    if (!connection.socket.isOpen()) { connections.remove(connection.socket.getId(), connection); return; }
    ObjectNode error = Json.obj("type", "error", "error", code, "message", message);
    if (commandId != null) error.put("commandId", commandId);
    try { send(connection, error); }
    catch (IOException | IllegalStateException transportError) {
      // The peer may close between isOpen and send. The original error code remains observable.
      log.warn("twin_error_delivery_failed project={} connection={} command={} error={} open={}",
          connection.project, connection.socket.getId(), commandId, code, connection.socket.isOpen(), transportError);
      close(connection, CloseStatus.SERVER_ERROR);
    }
  }

  void close(Connection connection, CloseStatus status) {
    try { if (connection.socket.isOpen()) connection.socket.close(status); }
    catch (IOException | IllegalStateException error) {
      log.warn("twin_socket_close_failed project={} connection={} status={} open={}",
          connection.project, connection.socket.getId(), status, connection.socket.isOpen(), error);
    } finally { connections.remove(connection.socket.getId(), connection); }
  }

  @Scheduled(fixedDelay = 100)
  public void push() {
    // Auto-mode readers never integrate. Legacy manual projects still share one integration per round.
    Map<String, TwinDriveRuntime.StreamFrame> frames = new HashMap<>();
    for (Connection connection : connections.values()) synchronized (connection) {
      try {
        if (!connection.socket.isOpen()) { connections.remove(connection.socket.getId(), connection); continue; }
        Auth.User user = authorize(connection);
        if (System.currentTimeMillis() - connection.lastMessageAt > 45000) {
          sendError(connection, null, "twin_heartbeat_timeout", "No client heartbeat received for 45 seconds.");
          close(connection, CloseStatus.POLICY_VIOLATION); continue;
        }
        String key = TwinDriveRuntime.key(user.tenant(), connection.project);
        TwinDriveRuntime.StreamFrame frame = frames.get(key);
        if (frame == null) { frame = runtime.frame(user, connection.project); frames.put(key, frame); }
        ObjectNode snapshot = frame.snapshot();
        long revision = snapshot.path("revision").asLong(), sequence = snapshot.path("sequence").asLong();
        // Another viewer may have subscribed to a just-saved revision after this round cached its
        // frame. Never revoke that newer subscription with an older observer's cached snapshot.
        if (revision < connection.subscribedRevision) continue;
        if (connection.revision != -1 && connection.revision != revision) {
          send(connection, Json.obj("type", "config_changed", "revision", revision));
          connection.subscribedRevision = -1; connection.topics = Set.of();
        }
        boolean subscribed = frame.topics().isEmpty() || (connection.subscribedRevision == revision
            && connection.topics.equals(new HashSet<>(frame.topics())));
        if (subscribed && (revision != connection.revision || sequence != connection.sequence)) send(connection, snapshot);
        connection.revision = revision; connection.sequence = sequence;
      } catch (ApiException error) {
        // Lock contention is a bounded scheduling outcome, not a successful sample. Clients retain
        // their last timestamp and therefore detect staleness if contention persists.
        if (error.code.equals("twin_runtime_busy")) continue;
        failConnection(connection, error, error.status == 401 || error.status == 403
            || (connection.share != null && error.status == 404)
            ? CloseStatus.POLICY_VIOLATION : CloseStatus.SERVER_ERROR);
      } catch (Exception error) { failConnection(connection, error, CloseStatus.SERVER_ERROR); }
    }
  }

  void failConnection(Connection connection, Exception error, CloseStatus status) {
    if (!connection.socket.isOpen() && (error instanceof IOException || error instanceof IllegalStateException))
      log.warn("twin_socket_closed_during_send project={} connection={}", connection.project, connection.socket.getId(), error);
    else log.error("twin_connection_failed project={} connection={}", connection.project, connection.socket.getId(), error);
    try {
      if (connection.socket.isOpen())
        sendError(connection, null, error instanceof ApiException api ? api.code : "twin_runtime_failed", error instanceof ApiException ? error.getMessage() : "The point stream failed; reconnect to inspect current state.");
    } finally { close(connection, status); }
  }

  @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) { connections.remove(socket.getId()); }
  @Override public void handleTransportError(WebSocketSession socket, Throwable error) throws Exception {
    log.warn("twin_transport_failed connection={}", socket.getId(), error);
    Connection connection = connections.get(socket.getId());
    if (connection != null) close(connection, CloseStatus.SERVER_ERROR);
    else try { if (socket.isOpen()) socket.close(CloseStatus.SERVER_ERROR); }
    catch (IOException | IllegalStateException closeError) {
      log.warn("twin_untracked_socket_close_failed connection={} open={}", socket.getId(), socket.isOpen(), closeError);
    } finally { connections.remove(socket.getId()); }
  }
}
