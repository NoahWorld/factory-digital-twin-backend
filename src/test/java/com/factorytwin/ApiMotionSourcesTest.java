package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ApiMotionSourcesTest {
  static ObjectNode connection(String protocol, String url) {
    return Json.obj("protocol", protocol, "url", url, "timestampPath", "timestamp", "intervalMs", 200, "timeoutMs", 2000);
  }
  static ObjectNode document(ObjectNode connection) {
    var config = TwinDriveDocuments.emptyConfig().put("enabled", true);
    config.set("connection", connection);
    config.set("points", Json.M.valueToTree(List.of(Json.obj("id", "position", "sourcePath", "agv.positionM", "min", 0, "max", 6, "staleAfterMs", 1500))));
    return Json.obj("projectId", "project", "revision", 1, "config", config);
  }
  static ObjectNode sample(long time, long sequence, double position) {
    return Json.obj("timestamp", Instant.ofEpochMilli(time).toString(), "sequence", sequence, "agv", Json.obj("positionM", position), "cycle", Json.obj("phase", "去程"));
  }
  @Test void validatesBoundedPathsUrlsAndOriginWithoutInternalProxyEscape() {
    ApiMotionSources.validateConnection(connection("rest", ApiMotionSources.REST_PATH), false);
    ApiMotionSources.validateConnection(connection("websocket", ApiMotionSources.WS_PATH), false);
    for (String path : List.of("agv.positionM", "$.items[999].position", "a.b.c.d.e.f.g.h")) ApiMotionSources.pathSyntax(path);
    for (String path : List.of("a.__proto__", "a.constructor", "a.prototype", "a[1000]", "a.b.c.d.e.f.g.h.i", "a.*", "a['x']")) assertThrows(ApiException.class, () -> ApiMotionSources.pathSyntax(path));
    for (String url : List.of("/api/v1/users", "http://admin:pass@example.com/state", "http://example.com/state?apiKey=private", "http://example.com/state#fragment", "http://example.com/state?%=bad", " https://example.com/state")) assertThrows(ApiException.class, () -> ApiMotionSources.validateConnection(connection("rest", url), false));
    var subscribe = connection("websocket", "wss://example.com/live").put("subscribeMessage", "{} {}"); assertThrows(ApiException.class, () -> ApiMotionSources.validateConnection(subscribe, false));
    var redacted = connection("rest", "").put("redacted", true); assertThrows(ApiException.class, () -> ApiMotionSources.validateConnection(redacted, true));
    var service = new ApiMotionSources(mock(TwinDriveDocuments.class), new SourceClient("https://example.com"), 8080);
    try {
      assertEquals("wss://example.com/live", service.uri(connection("websocket", "wss://example.com/live")).toString());
      assertEquals("http://127.0.0.1:8080" + ApiMotionSources.REST_PATH, service.uri(connection("rest", ApiMotionSources.REST_PATH)).toString());
      assertEquals("source_origin_denied", assertThrows(ApiException.class, () -> service.uri(connection("rest", "https://example.com:444/state"))).code);
    } finally { service.stop(); }
  }
  @Test void realHttpProbeRejectsRedirectOversizeAndInvalidJsonAndDoesNotForwardCredentials() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var body = new AtomicReference<>(sample(System.currentTimeMillis(), 1, 3).toString());
    server.createContext("/state", exchange -> {
      assertNull(exchange.getRequestHeaders().getFirst("Cookie")); assertNull(exchange.getRequestHeaders().getFirst("Origin"));
      byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", "/state"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
    server.start(); String origin = "http://127.0.0.1:" + server.getAddress().getPort();
    var service = new ApiMotionSources(mock(TwinDriveDocuments.class), new SourceClient(origin), 8080);
    try {
      var result = service.test(connection("rest", origin + "/state"));
      assertTrue(result.path("fields").findValuesAsText("path").contains("agv.positionM"));
      assertTrue(result.path("fields").findValuesAsText("type").contains("string"));
      assertFalse(result.has("sample"));
      assertEquals("source_http_error", assertThrows(ApiException.class, () -> service.test(connection("rest", origin + "/redirect"))).code);
      body.set("{} {}"); assertEquals("invalid_source_json", assertThrows(ApiException.class, () -> service.test(connection("rest", origin + "/state"))).code);
      body.set("{\"padding\":\"" + "x".repeat(ApiMotionSources.BODY_LIMIT) + "\"}"); assertEquals("source_payload_too_large", assertThrows(ApiException.class, () -> service.test(connection("rest", origin + "/state"))).code);
      body.set("{\"timestamp\":42}"); assertEquals("invalid_source_timestamp", assertThrows(ApiException.class, () -> service.test(connection("rest", origin + "/state"))).code);
    } finally { service.stop(); server.stop(0); }
  }
  @Test void snapshotsContainOnlyObservedFullSamplesAndErrorsAdvanceSequencePreservingActualPosition() {
    var service = new ApiMotionSources(mock(TwinDriveDocuments.class), new SourceClient(""), 8080);
    try {
      var document = document(connection("rest", ApiMotionSources.REST_PATH)); var observation = service.ensure("tenant", "project", document);
      assertEquals("idle", service.snapshot("tenant", "project", document).path("status").asText());
      assertTrue(service.snapshot("tenant", "project", document).path("points").isEmpty());
      long now = System.currentTimeMillis(); service.accept(observation, sample(now, 1, 3));
      assertEquals(3, service.snapshot("tenant", "project", document).path("points").path("position").path("value").asDouble());
      var missing = sample(now + 1, 2, 4); missing.remove("agv"); service.accept(observation, missing);
      var failure = service.snapshot("tenant", "project", document);
      assertEquals("source_point_missing", failure.path("error").asText()); assertEquals(2, failure.path("sequence").asLong());
      assertEquals(1, failure.path("retryCount").asInt()); assertEquals(3, failure.path("points").path("position").path("value").asDouble());
      assertEquals("error", failure.path("points").path("position").path("quality").asText());
      service.accept(observation, sample(now + 2, 3, 4)); assertEquals("running", service.snapshot("tenant", "project", document).path("status").asText());
      service.accept(observation, sample(now + 1, 2, 2)); assertEquals("source_sequence_regressed", service.snapshot("tenant", "project", document).path("error").asText());
      var next = document.deepCopy().put("revision", 2); assertTrue(service.snapshot("tenant", "project", next).path("points").isEmpty()); assertTrue(observation.retired);
    } finally { service.stop(); }
  }
  @Test void oldSocketCallbacksCannotOverwriteOrInterruptNewConnectionAttempt() {
    var service = new ApiMotionSources(mock(TwinDriveDocuments.class), new SourceClient(""), 8080);
    try {
      var document = document(connection("websocket", ApiMotionSources.WS_PATH)); var observation = service.ensure("tenant", "project", document);
      long now = System.currentTimeMillis(); observation.attempt = 1; service.accept(observation, sample(now, 1, 2), 1);
      service.failed(observation, new ApiException(502, "source_socket_closed", "closed"), 1);
      observation.attempt = 3; var socket = mock(java.net.http.WebSocket.class); observation.socket = socket;
      service.accept(observation, sample(now + 2, 3, 4), 3);
      long sequence = observation.snapshot.path("sequence").asLong();
      service.failed(observation, new IllegalStateException("late old error"), 1);
      service.accept(observation, sample(now + 10, 10, 0), 1);
      assertEquals(sequence, observation.snapshot.path("sequence").asLong()); assertEquals(4, observation.snapshot.path("points").path("position").path("value").asDouble());
      assertSame(socket, observation.socket); verify(socket, never()).abort(); assertEquals("running", observation.snapshot.path("status").asText());
    } finally { service.stop(); }
  }
  @Test void collectorReadsSavedTenantProjectEvenWithoutGatewayObservers() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/state", exchange -> { byte[] bytes = sample(System.currentTimeMillis(), 5, 2).toString().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }); server.start();
    String origin = "http://127.0.0.1:" + server.getAddress().getPort(); var documents = mock(TwinDriveDocuments.class); var document = document(connection("rest", origin + "/state"));
    when(documents.apiProjects()).thenReturn(List.of(new TwinDriveDocuments.AutomaticProject("tenant", "project"))); when(documents.stored("tenant", "project")).thenReturn(document);
    var service = new ApiMotionSources(documents, new SourceClient(origin), 8080);
    try {
      service.collect(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (service.observations.get(TwinDriveRuntime.key("tenant", "project")).snapshot.path("sequence").asLong() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
      var snapshot = service.snapshot("tenant", "project", document); assertEquals("running", snapshot.path("status").asText()); assertEquals(2, snapshot.path("points").path("position").path("value").asDouble());
      verify(documents).stored("tenant", "project");
    } finally { service.stop(); server.stop(0); }
  }
}
