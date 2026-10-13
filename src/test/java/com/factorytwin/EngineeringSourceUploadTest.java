package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class EngineeringSourceUploadTest {
  @Test @Timeout(20)
  void nonResettableMultipartSourceSurvivesS3RetryAndStagingIsCleaned() throws Exception {
    // Exceed the SDK's historical 128 KiB stream mark window. The first HTTP request
    // fails deliberately; the real UrlConnectionHttpClient must resend the entire file.
    byte[] payload = new byte[300 * 1024];
    new Random(41).nextBytes(payload);
    List<byte[]> requests = new CopyOnWriteArrayList<>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try (exchange) {
        requests.add(exchange.getRequestBody().readAllBytes());
        if (requests.size() == 1) {
          byte[] error = "<Error><Code>InternalError</Code><Message>Retry test</Message></Error>".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/xml");
          exchange.sendResponseHeaders(500, error.length);
          exchange.getResponseBody().write(error);
        } else {
          exchange.getResponseHeaders().set("ETag", "\"test-etag\"");
          exchange.sendResponseHeaders(200, -1);
        }
      }
    });
    server.start();
    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    var staged = new AtomicReference<Path>();
    try (var storage = new ObjectStorage(endpoint, endpoint, "us-east-1", "test-bucket", "test-access", "test-secret")) {
      var db = mock(JdbcTemplate.class);
      var auth = mock(Auth.class);
      var tx = mock(TransactionTemplate.class);
      var projects = spy(new Projects(db, auth, tx, mock(ProjectCovers.class), new Contracts()));
      var engine = mock(EngineeringProcess.class);
      var user = new Auth.User("user", "tenant", "user@example.invalid", "user", "User", "delivery_manager", true, true);
      doReturn(Json.obj()).when(projects).access(eq(user), eq("project"), anyBoolean());
      doNothing().when(projects).lock(user, "project");
      when(engine.available()).thenReturn(true);
      doAnswer(i -> { ((java.util.function.Consumer<TransactionStatus>) i.getArgument(0)).accept(null); return null; })
          .when(tx).executeWithoutResult(any());
      when(db.queryForObject(contains("SELECT count(*) FROM engineering_imports"), eq(Integer.class), eq("tenant"))).thenReturn(0);
      when(db.queryForList(contains("SELECT * FROM engineering_imports"), eq("tenant"), eq("project"), anyString()))
          .thenAnswer(i -> List.of(Map.of("id", i.getArgument(3), "project_id", "project", "status", "queued",
              "created_at", Instant.now(), "updated_at", Instant.now())));
      var source = new MockMultipartFile("file", "large.dxf", "application/octet-stream", payload) {
        @Override public InputStream getInputStream() {
          return new FilterInputStream(new ByteArrayInputStream(payload)) {
            @Override public boolean markSupported() { return false; }
            @Override public void mark(int limit) {}
            @Override public void reset() throws IOException { throw new IOException("Multipart stream cannot reset"); }
          };
        }
        @Override public void transferTo(Path destination) throws IOException {
          staged.set(destination);
          try (InputStream in = getInputStream()) { Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING); }
        }
      };
      var result = new EngineeringImports(projects, storage, engine).upload(user, "project", "Retry source", source);
      assertEquals("queued", result.path("status").asText());
      assertEquals(2, requests.size());
      for (byte[] request : requests) assertArrayEquals(payload, request);
      assertNotNull(staged.get());
      assertFalse(Files.exists(staged.get()), "Staged source must be removed after S3 completion");
      verify(db, never()).update(contains("status='failed'"), any(Object[].class));
    } finally {
      server.stop(0);
    }
  }
}
