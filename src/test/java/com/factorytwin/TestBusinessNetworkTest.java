package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.*;
import org.springframework.boot.autoconfigure.security.servlet.*;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.http.server.*;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;

class TestBusinessNetworkTest {
  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class})
  @EnableWebSocket @EnableScheduling
  @Import({TestBusiness.class, TestBusinessController.class, TestBusinessWebSocket.class})
  static class TestApplication {
    @Bean RequestFilter requestFilter() { return new RequestFilter("http://browser.example", "api"); }
  }
  @Test void realRestAndWebSocketHaveSameClockAndAdapterUsesActualSocketWithoutLogin() throws Exception {
    try (var context = new SpringApplication(TestApplication.class).run("--server.port=0",
        "--spring.config.location=optional:classpath:/motion-source-test.properties", "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      var documents = mock(TwinDriveDocuments.class); var service = new ApiMotionSources(documents, new SourceClient(""), port);
      var config = ApiMotionSourcesTest.connection("websocket", ApiMotionSources.WS_PATH);
      try {
        var http = HttpClient.newHttpClient(); var message = new CompletableFuture<String>();
        var socket = http.newWebSocketBuilder().header("Origin", "http://browser.example")
            .buildAsync(URI.create("ws://127.0.0.1:" + port + ApiMotionSources.WS_PATH), new WebSocket.Listener() {
              final StringBuilder body = new StringBuilder();
              @Override public void onOpen(WebSocket socket) { socket.request(1); }
              @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
                body.append(text); if (last) message.complete(body.toString()); else socket.request(1); return CompletableFuture.completedFuture(null);
              }
              @Override public void onError(WebSocket socket, Throwable error) { message.completeExceptionally(error); }
            }).get(2, TimeUnit.SECONDS);
        try {
          var wsState = Json.parse(message.get(2, TimeUnit.SECONDS));
          var restResponse = http.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ApiMotionSources.REST_PATH)).GET().build(), HttpResponse.BodyHandlers.ofString());
          assertEquals(200, restResponse.statusCode()); var restState = Json.parse(restResponse.body());
          for (var state : List.of(wsState, restState)) {
            assertEquals(Json.parse(TestBusiness.stateAt(java.time.Instant.parse(state.path("timestamp").asText()).toEpochMilli()).toString()), state,
                "real REST and WS must expose every joint/cargo field from the identical backend clock");
            assertTrue(state.path("robot").has("shoulderDeg")); assertTrue(state.path("cargo").has("attachment"));
          }
        } finally { socket.abort(); }
        ObjectNode ws = service.test(config); ObjectNode rest = service.test(ApiMotionSourcesTest.connection("rest", ApiMotionSources.REST_PATH));
        assertTrue(Math.abs(java.time.Instant.parse(ws.path("timestamp").asText()).toEpochMilli() - java.time.Instant.parse(rest.path("timestamp").asText()).toEpochMilli()) < 2000);
        assertTrue(ws.path("fields").findValuesAsText("path").contains("agv.positionM"));
        assertTrue(ws.path("fields").findValuesAsText("path").containsAll(List.of("agv.wheelAngleDeg", "robot.shoulderDeg", "robot.elbowDeg", "robot.wristDeg", "gripper.openingM", "cargo.yawDeg", "cycle.phaseCode")));
        var document = ApiMotionSourcesTest.document(config); when(documents.apiProjects()).thenReturn(List.of(new TwinDriveDocuments.AutomaticProject("tenant", "project"))); when(documents.stored("tenant", "project")).thenReturn(document);
        service.collect(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4); ObjectNode snapshot;
        do { snapshot = service.snapshot("tenant", "project", document); if (snapshot.path("sequence").asLong() >= 2) break; Thread.sleep(20); } while (System.nanoTime() < deadline);
        assertEquals("running", snapshot.path("status").asText(), snapshot.toString()); assertTrue(snapshot.path("sequence").asLong() >= 2, snapshot.toString());
        assertEquals("good", snapshot.path("points").path("position").path("quality").asText());
        var denied = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", "http://evil.example").buildAsync(URI.create("ws://127.0.0.1:" + port + ApiMotionSources.WS_PATH), new WebSocket.Listener() {});
        var error = assertThrows(ExecutionException.class, () -> denied.get(2, TimeUnit.SECONDS));
        assertInstanceOf(WebSocketHandshakeException.class, error.getCause()); assertEquals(403, ((WebSocketHandshakeException) error.getCause()).getResponse().statusCode());
      } finally { service.stop(); }
    }
  }
  @Test void fixedInternalOriginRequiresActualLoopbackRemoteAddress() throws Exception {
    var handler = new TestBusinessWebSocket(new TestBusiness(), new RequestFilter("http://browser.example", "api"));
    var servlet = new MockHttpServletRequest(); servlet.setLocalPort(8080);
    var request = mock(ServletServerHttpRequest.class); var response = mock(ServerHttpResponse.class); var headers = new org.springframework.http.HttpHeaders(); headers.setOrigin("http://127.0.0.1:8080"); when(request.getHeaders()).thenReturn(headers); when(request.getServletRequest()).thenReturn(servlet);
    when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 54321));
    assertFalse(handler.interceptor().beforeHandshake(request, response, handler, new HashMap<>())); verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getLoopbackAddress(), 54321)); var attributes = new HashMap<String, Object>();
    assertTrue(handler.interceptor().beforeHandshake(request, response, handler, attributes)); assertEquals(true, attributes.get("internal"));
    headers.setOrigin("http://127.0.0.1:8081");
    assertFalse(handler.interceptor().beforeHandshake(request, response, handler, new HashMap<>()));
  }
}
