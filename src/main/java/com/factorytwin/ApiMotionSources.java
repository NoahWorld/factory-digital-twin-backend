package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Actual, bounded upstream observations; no browser clock, simulation or client credentials. */
@Service
public class ApiMotionSources {
  static final String REST_PATH = "/api/v1/test-business/handling-cell/state";
  static final String WS_PATH = "/api/v1/test-business/handling-cell/live";
  static final int BODY_LIMIT = 256 * 1024;
  static final long MAX_SEQUENCE = 9007199254740991L;
  static final Pattern PATH = Pattern.compile("^(?:\\$\\.)?[A-Za-z_][A-Za-z0-9_]*(?:\\[\\d{1,3}\\]|\\.[A-Za-z_][A-Za-z0-9_]*)*$");
  static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|\\d+");
  final TwinDriveDocuments documents;
  final Set<String> allowed;
  final int port;
  final HttpClient http;
  final Logger log = LoggerFactory.getLogger(ApiMotionSources.class);
  final Map<String, Observation> observations = new ConcurrentHashMap<>();
  final ExecutorService workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(128), Thread.ofPlatform().name("motion-source-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
  long lastDiscovery;

  public ApiMotionSources(TwinDriveDocuments documents, SourceClient sourceClient, @Value("${server.port:8080}") int port) {
    this.documents = documents; this.allowed = sourceClient.allowed; this.port = port;
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  }
  static final class Observation {
    final String tenant, project;
    final long revision;
    final ObjectNode config;
    ObjectNode snapshot;
    volatile WebSocket socket;
    volatile boolean pending;
    boolean retired;
    long attempt, nextAttempt, lastReceive, lastHeartbeat, sourceSequence = -1;
    int retryCount;
    String sourceTimestamp;
    JsonNode lastPayload;
    Observation(String tenant, String project, ObjectNode document) {
      this.tenant = tenant; this.project = project; revision = document.path("revision").asLong(); config = (ObjectNode) document.path("config").deepCopy();
      snapshot = Json.obj("type", "snapshot", "projectId", project, "revision", revision, "sequence", 0,
          "timestamp", Instant.now().toString(), "source", "api", "status", "idle", "points", Json.obj(), "procedure", null);
    }
  }
  static void pathSyntax(String path) {
    Json.require(path != null && path.length() <= 256 && PATH.matcher(path).matches(), "Only bounded JSON field paths are accepted.");
    var matcher = TOKEN.matcher(path.replaceFirst("^\\$\\.", "")); int count = 0;
    while (matcher.find()) { Json.require(++count <= 8 && !Set.of("__proto__", "prototype", "constructor").contains(matcher.group()), "Unsupported JSON field path."); }
  }
  static JsonNode value(JsonNode root, String path) {
    pathSyntax(path); var matcher = TOKEN.matcher(path.replaceFirst("^\\$\\.", "")); JsonNode current = root;
    while (matcher.find()) { String token = matcher.group(); current = Character.isDigit(token.charAt(0)) ? current.path(Integer.parseInt(token)) : current.path(token); }
    return current;
  }
  static void validateConnection(JsonNode connection, boolean allowEmpty) {
    Json.fields(connection, "protocol", "url", "timestampPath", "intervalMs", "timeoutMs", "subscribeMessage", "redacted");
    Json.require(!connection.has("redacted"), "A redacted read-only source configuration cannot be saved or tested.");
    String protocol = Json.text(connection, "protocol", 1, 20);
    Json.require(Set.of("rest", "websocket").contains(protocol), "Source protocol must be rest or websocket.");
    pathSyntax(Json.text(connection, "timestampPath", 1, 256));
    Json.integer(connection, "intervalMs", 200, 60000); Json.integer(connection, "timeoutMs", 500, 30000);
    String address = Json.text(connection, "url", allowEmpty ? 0 : 1, 2048);
    Json.require(address.equals(connection.path("url").asText()), "Source URL cannot contain surrounding whitespace.");
    if (!address.isEmpty()) {
      String internal = protocol.equals("rest") ? REST_PATH : WS_PATH;
      if (!address.equals(internal)) {
        URI uri;
        try { uri = URI.create(address); } catch (IllegalArgumentException error) { throw new ApiException(400, "invalid_source_url", "Invalid source URL."); }
        Json.require(uri.getScheme() != null && (protocol.equals("rest") ? Set.of("http", "https") : Set.of("ws", "wss")).contains(uri.getScheme())
            && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null, "Source URL protocol must match and cannot contain credentials or a fragment.");
        if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
          String key;
          try { key = java.net.URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8); }
          catch (IllegalArgumentException error) { throw new ApiException(400, "invalid_source_url", "Invalid URL query encoding."); }
          Json.require(!key.matches("(?i).*(token|secret|password|credential|api[-_]?key|authorization).*"), "Credentials cannot be placed in the URL.");
        }
      }
    }
    if (connection.has("subscribeMessage")) {
      Json.require(protocol.equals("websocket"), "Only WebSocket sources accept a subscription message.");
      JsonNode text = connection.path("subscribeMessage");
      Json.require(text.isTextual() && text.asText().length() <= 8192 && text.asText().getBytes(StandardCharsets.UTF_8).length <= 32768, "Subscription message exceeds its text budget.");
      try { JsonNode message = Json.M.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text.asText()); Json.require(message != null && (message.isObject() || message.isArray()), "Subscription message must be a JSON object or array."); }
      catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new ApiException(400, "invalid_subscription_json", "Subscription message is not valid JSON."); }
    }
  }
  URI uri(JsonNode connection) {
    validateConnection(connection, false);
    String address = connection.path("url").asText();
    if (address.equals(REST_PATH)) return URI.create("http://127.0.0.1:" + port + REST_PATH);
    if (address.equals(WS_PATH)) return URI.create("ws://127.0.0.1:" + port + WS_PATH);
    URI uri = URI.create(address);
    String scheme = switch (uri.getScheme()) { case "ws" -> "http"; case "wss" -> "https"; default -> uri.getScheme(); };
    String origin = scheme + "://" + uri.getRawAuthority();
    if (!allowed.contains(origin)) throw new ApiException(403, "source_origin_denied", "Source origin is not in RUNTIME_ALLOWED_ORIGINS.");
    return uri;
  }
  ObjectNode payload(byte[] bytes) {
    if (bytes.length > BODY_LIMIT) throw new ApiException(502, "source_payload_too_large", "Source response exceeds 256 KiB.");
    try { JsonNode parsed = Json.M.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes); if (parsed == null || !parsed.isObject()) throw new ApiException(502, "invalid_source_payload", "Source must return a JSON object."); return (ObjectNode) parsed; }
    catch (IOException error) { throw new ApiException(502, "invalid_source_json", "Source did not return valid JSON."); }
  }
  ObjectNode fetch(JsonNode connection) throws Exception {
    URI target = uri(connection); int timeout = connection.path("timeoutMs").asInt();
    var future = http.sendAsync(HttpRequest.newBuilder(target).timeout(Duration.ofMillis(timeout)).header("Accept", "application/json").GET().build(), info -> new BoundedJsonBodySubscriber());
    try {
      var response = future.get(timeout, TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200) throw new ApiException(502, "source_http_error", "Source returned HTTP " + response.statusCode() + "; redirects are not followed.");
      return payload(response.body());
    } catch (TimeoutException error) { future.cancel(true); throw new ApiException(504, "source_timeout", "Source did not respond before the configured timeout."); }
  }
  static class BoundedJsonBodySubscriber extends SourceClient.LimitedBodySubscriber {
    @Override public void onNext(List<ByteBuffer> items) {
      long size = bytes.size(); for (var item : items) size += item.remaining();
      if (size > BODY_LIMIT) { subscription.cancel(); future.completeExceptionally(new ApiException(502, "source_payload_too_large", "Source response exceeds 256 KiB.")); return; }
      super.onNext(items);
    }
  }
  static Instant timestamp(JsonNode payload, JsonNode connection) {
    JsonNode value = value(payload, connection.path("timestampPath").asText());
    if (!value.isTextual()) throw new ApiException(502, "invalid_source_timestamp", "Source timestamp field must be an ISO-8601 string.");
    try {
      Instant timestamp = Instant.parse(value.asText());
      if (timestamp.isAfter(Instant.now().plusSeconds(300))) throw new ApiException(502, "source_clock_error", "Source timestamp is more than five minutes in the future.");
      return timestamp;
    } catch (java.time.format.DateTimeParseException error) { throw new ApiException(502, "invalid_source_timestamp", "Source timestamp field must be an ISO-8601 string."); }
  }
  ObjectNode test(JsonNode connection) {
    uri(connection); int timeout = connection.path("timeoutMs").asInt();
    try {
      ObjectNode sample;
      if (connection.path("protocol").asText().equals("rest")) sample = fetch(connection);
      else {
        CompletableFuture<ObjectNode> first = new CompletableFuture<>();
        var listener = new UpstreamListener(connection, sampleValue -> first.complete(sampleValue), error -> first.completeExceptionally(error));
        var pending = builder(connection).buildAsync(uri(connection), listener); WebSocket socket = null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        try { socket = pending.get(timeout, TimeUnit.MILLISECONDS); sample = first.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        finally { if (socket != null) socket.abort(); else pending.cancel(true); listener.closed = true; }
      }
      String time = timestamp(sample, connection).toString(); List<ObjectNode> fields = new ArrayList<>(); flatten(sample, "", fields, 0);
      return Json.obj("timestamp", time, "fields", fields);
    } catch (ApiException error) { log.warn("motion_source_test_failed protocol={} origin={} error={}", connection.path("protocol"), safeOrigin(connection), error.code, error); throw error; }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ApiException(503, "source_test_interrupted", "Source test was interrupted."); }
    catch (Exception error) { log.warn("motion_source_test_failed protocol={} origin={}", connection.path("protocol"), safeOrigin(connection), error); throw sourceException(error); }
  }
  void flatten(JsonNode node, String path, List<ObjectNode> fields, int depth) {
    if (fields.size() >= 128 || depth > 8) return;
    if (node.isObject()) node.fields().forEachRemaining(entry -> {
      if (entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") && !Set.of("__proto__", "prototype", "constructor").contains(entry.getKey()))
        flatten(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), fields, depth + 1);
    });
    else if (node.isArray()) for (int i = 0; i < Math.min(1000, node.size()) && fields.size() < 128; i++) flatten(node.get(i), path + "[" + i + "]", fields, depth + 1);
    else if (!path.isEmpty() && PATH.matcher(path).matches() && (node.isNumber() || node.isBoolean() || node.isTextual()) && (!node.isTextual() || node.asText().length() <= 256))
      fields.add(Json.obj("path", path, "type", node.isNumber() ? "number" : node.isBoolean() ? "boolean" : "string", "value", node));
  }
  WebSocket.Builder builder(JsonNode connection) {
    var builder = http.newWebSocketBuilder().connectTimeout(Duration.ofMillis(connection.path("timeoutMs").asInt()));
    if (connection.path("url").asText().equals(WS_PATH)) builder.header("Origin", "http://127.0.0.1:" + port);
    return builder;
  }
  interface SampleConsumer { void accept(ObjectNode sample); }
  class UpstreamListener implements WebSocket.Listener {
    final JsonNode connection; final SampleConsumer samples; final java.util.function.Consumer<Throwable> errors;
    final StringBuilder buffer = new StringBuilder(); int bytes; volatile boolean closed;
    UpstreamListener(JsonNode connection, SampleConsumer samples, java.util.function.Consumer<Throwable> errors) { this.connection = connection; this.samples = samples; this.errors = errors; }
    public void onOpen(WebSocket socket) {
      socket.request(1);
      if (connection.has("subscribeMessage")) socket.sendText(connection.path("subscribeMessage").asText(), true).whenComplete((ignored, error) -> { if (error != null && !closed) errors.accept(error); });
    }
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      if (closed) { socket.abort(); return null; }
      try {
        bytes += data.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > BODY_LIMIT) throw new ApiException(502, "source_payload_too_large", "Source WebSocket frame exceeds 256 KiB.");
        buffer.append(data);
        if (last) { ObjectNode sample = payload(buffer.toString().getBytes(StandardCharsets.UTF_8)); buffer.setLength(0); bytes = 0; samples.accept(sample); }
        socket.request(1);
      } catch (Exception error) { closed = true; socket.abort(); errors.accept(error); }
      return null;
    }
    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) { closed = true; socket.abort(); errors.accept(new ApiException(502, "source_binary_not_supported", "Source WebSocket must send JSON text.")); return null; }
    public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) { socket.request(1); return socket.sendPong(message); }
    public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) { socket.request(1); return null; }
    public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { if (!closed) errors.accept(new ApiException(502, "source_socket_closed", "Upstream WebSocket closed with status " + code + ".")); closed = true; return null; }
    public void onError(WebSocket socket, Throwable error) { if (!closed) errors.accept(error); closed = true; }
  }
  static ApiException sourceException(Throwable error) {
    boolean timeout = false;
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ApiException api) return api;
      if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) timeout = true;
      if (cause.getCause() == cause) break;
    }
    return new ApiException(timeout ? 504 : 502, timeout ? "source_timeout" : "source_connection_failed", "The business source could not be read; inspect backend source logs.");
  }
  String safeOrigin(JsonNode connection) {
    try { URI target = uri(connection); return target.getScheme() + "://" + target.getHost() + (target.getPort() < 0 ? "" : ":" + target.getPort()); }
    catch (ApiException | IllegalArgumentException error) { return "invalid-or-denied"; }
  }
  void accept(Observation observation, ObjectNode payload) {
    synchronized (observation) { accept(observation, payload, observation.attempt); }
  }
  void accept(Observation observation, ObjectNode payload, long attempt) {
    synchronized (observation) {
      if (observation.retired || observation.attempt != attempt) return;
      try {
        Instant time = timestamp(payload, observation.config.path("connection"));
        long upstreamSequence = -1;
        if (payload.has("sequence")) {
          JsonNode sequence = payload.path("sequence");
          if (!sequence.isIntegralNumber() || !sequence.canConvertToLong() || sequence.asLong() < 0 || sequence.asLong() > MAX_SEQUENCE)
            throw new ApiException(502, "invalid_source_sequence", "Source sequence must be a non-negative safe integer.");
          upstreamSequence = sequence.asLong();
        }
        if ((observation.sourceTimestamp != null && time.isBefore(Instant.parse(observation.sourceTimestamp)))
            || (upstreamSequence >= 0 && upstreamSequence < observation.sourceSequence))
          throw new ApiException(502, "source_sequence_regressed", "Source sequence or timestamp moved backwards; resave the source configuration after correcting the upstream clock.");
        if (observation.sourceTimestamp != null && time.toString().equals(observation.sourceTimestamp)) {
          if (!payload.equals(observation.lastPayload)) throw new ApiException(502, "source_timestamp_reused", "Source changed values without advancing its sample timestamp.");
          observation.lastReceive = System.currentTimeMillis(); return;
        }
        ObjectNode points = Json.obj();
        for (JsonNode point : observation.config.path("points")) {
          JsonNode raw = value(payload, point.path("sourcePath").asText());
          if (!(raw.isNumber() || raw.isBoolean())) throw new ApiException(502, "source_point_missing", "A mapped source field is missing or is not numeric/boolean: " + point.path("id").asText());
          double number = raw.isBoolean() ? (raw.asBoolean() ? 1 : 0) : raw.asDouble();
          if (!Double.isFinite(number) || number < point.path("min").asDouble() || number > point.path("max").asDouble())
            throw new ApiException(502, "source_point_out_of_range", "A mapped source value is outside its engineering range: " + point.path("id").asText());
          points.set(point.path("id").asText(), Json.obj("value", number, "target", number, "timestamp", time.toString(), "quality", System.currentTimeMillis() - time.toEpochMilli() > point.path("staleAfterMs").asLong() ? "stale" : "good"));
        }
        observation.snapshot.set("points", points); observation.snapshot.put("timestamp", time.toString());
        observation.snapshot.put("status", "running"); observation.snapshot.remove(List.of("error", "retryCount"));
        observation.snapshot.put("sequence", observation.snapshot.path("sequence").asLong() + 1);
        if (observation.retryCount > 0) log.info("motion_source_recovered tenant={} project={} revision={} retries={}", observation.tenant, observation.project, observation.revision, observation.retryCount);
        observation.retryCount = 0; observation.sourceTimestamp = time.toString(); observation.sourceSequence = upstreamSequence;
        observation.lastPayload = payload.deepCopy(); observation.lastReceive = System.currentTimeMillis();
      } catch (Exception error) { failed(observation, error, attempt); }
    }
  }
  void failed(Observation observation, Throwable error) {
    synchronized (observation) { failed(observation, error, observation.attempt); }
  }
  void failed(Observation observation, Throwable error, long attempt) {
    synchronized (observation) {
      if (observation.retired || observation.attempt != attempt) return;
      ++observation.attempt; // Fence callbacks from the failed socket/request before reconnecting.
      var api = sourceException(error); observation.retryCount++;
      observation.snapshot.put("status", "error").put("error", api.code).put("retryCount", observation.retryCount);
      observation.snapshot.put("sequence", observation.snapshot.path("sequence").asLong() + 1);
      observation.snapshot.path("points").forEach(point -> ((ObjectNode) point).put("quality", "error"));
      observation.nextAttempt = System.currentTimeMillis() + Math.min(30000, 500L << Math.min(6, observation.retryCount - 1));
      observation.pending = false;
      if (observation.socket != null) { observation.socket.abort(); observation.socket = null; }
      log.warn("motion_source_failed tenant={} project={} revision={} protocol={} origin={} error={} retry={}", observation.tenant, observation.project, observation.revision,
          observation.config.path("connection").path("protocol").asText(), safeOrigin(observation.config.path("connection")), api.code, observation.retryCount, error);
    }
  }
  Observation ensure(String tenant, String project, ObjectNode document) {
    String key = TwinDriveRuntime.key(tenant, project);
    return observations.compute(key, (ignored, previous) -> {
      if (previous != null && previous.revision == document.path("revision").asLong()) return previous;
      if (previous != null) retire(previous);
      if (observations.size() >= 128 && previous == null) throw new ApiException(503, "motion_source_capacity_exceeded", "At most 128 API source projects may run.");
      return new Observation(tenant, project, document);
    });
  }
  ObjectNode snapshot(String tenant, String project, ObjectNode document) {
    if (!document.path("config").path("enabled").asBoolean()) return new Observation(tenant, project, document).snapshot;
    var observation = ensure(tenant, project, document);
    synchronized (observation) {
      ObjectNode snapshot = observation.snapshot.deepCopy(); long now = System.currentTimeMillis();
      for (JsonNode point : observation.config.path("points")) {
        var sample = snapshot.path("points").path(point.path("id").asText());
        if (sample.isObject() && sample.path("quality").asText().equals("good") && now - Instant.parse(sample.path("timestamp").asText()).toEpochMilli() > point.path("staleAfterMs").asLong()) ((ObjectNode) sample).put("quality", "stale");
      }
      return snapshot;
    }
  }
  void retire(Observation observation) { synchronized (observation) { observation.retired = true; ++observation.attempt; if (observation.socket != null) observation.socket.abort(); } }
  public void collect() {
    long now = System.currentTimeMillis();
    if (now - lastDiscovery >= 1000) {
      try {
        var active = documents.apiProjects(); var keys = new HashSet<String>();
        for (var project : active) {
          var document = documents.stored(project.tenant(), project.project());
          if (!document.path("config").path("source").asText().equals("api") || !document.path("config").path("enabled").asBoolean()) continue;
          keys.add(TwinDriveRuntime.key(project.tenant(), project.project())); ensure(project.tenant(), project.project(), document);
        }
        observations.forEach((key, observation) -> { if (!keys.contains(key)) { retire(observation); observations.remove(key, observation); } });
        lastDiscovery = now;
      } catch (Exception error) {
        log.error("motion_source_discovery_failed projects={}", observations.size(), error);
        observations.values().forEach(observation -> failed(observation, new ApiException(503, "source_discovery_failed", "Saved source configuration could not be revalidated.")));
        return;
      }
    }
    for (var observation : observations.values()) synchronized (observation) {
      if (!observation.config.path("enabled").asBoolean() || observation.retired) continue;
      JsonNode connection = observation.config.path("connection");
      if (observation.socket != null) {
        long timeout = connection.path("timeoutMs").asLong();
        if (now - observation.lastReceive > timeout) { failed(observation, new ApiException(504, "source_timeout", "No upstream WebSocket sample arrived before the configured timeout.")); continue; }
        if (now - observation.lastHeartbeat > 10000) {
          long attempt = observation.attempt;
          observation.socket.sendPing(ByteBuffer.allocate(0)).whenComplete((ignored, error) -> { if (error != null) failed(observation, error, attempt); }); observation.lastHeartbeat = now;
        }
        continue;
      }
      if (observation.pending || now < observation.nextAttempt) continue;
      observation.pending = true; observation.nextAttempt = now + connection.path("intervalMs").asLong();
      long attempt = ++observation.attempt;
      try {
        workers.submit(() -> {
          try {
            if (connection.path("protocol").asText().equals("rest")) { accept(observation, fetch(connection), attempt); synchronized (observation) { if (observation.attempt == attempt) observation.pending = false; } }
            else {
              uri(connection);
              var listener = new UpstreamListener(connection, sample -> accept(observation, sample, attempt), error -> failed(observation, error, attempt));
              var pending = builder(connection).buildAsync(uri(connection), listener);
              WebSocket socket;
              try { socket = pending.get(connection.path("timeoutMs").asLong(), TimeUnit.MILLISECONDS); }
              catch (Exception error) { listener.closed = true; pending.cancel(true); throw error; }
              synchronized (observation) {
                if (observation.retired || observation.attempt != attempt || !observation.pending) { listener.closed = true; socket.abort(); }
                else { observation.socket = socket; observation.pending = false; observation.lastReceive = System.currentTimeMillis(); observation.lastHeartbeat = observation.lastReceive; log.info("motion_source_connected tenant={} project={} revision={}", observation.tenant, observation.project, observation.revision); }
              }
            }
          } catch (InterruptedException error) { Thread.currentThread().interrupt(); failed(observation, error, attempt); }
          catch (Exception error) { failed(observation, error, attempt); }
        });
      } catch (RejectedExecutionException error) { failed(observation, new ApiException(503, "source_worker_capacity_exceeded", "Source workers are at capacity."), attempt); }
    }
  }
  @PreDestroy public void stop() { observations.values().forEach(this::retire); workers.shutdownNow(); }
}
