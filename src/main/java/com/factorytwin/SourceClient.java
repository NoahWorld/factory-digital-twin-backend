package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SourceClient {
  final Set<String> allowed;
  final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public SourceClient(@Value("${twin.runtime-allowed-origins}") String origins) {
    allowed = new HashSet<>(Arrays.asList(origins.split(",")));
    allowed.remove("");
  }

  public void validate(JsonNode cfg) {
    String address = Json.text(cfg, "url", 1, 2048);
    URI uri;
    try {
      uri = URI.create(address);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "invalid_source_url", "Invalid source URL.");
    }
    Json.require(
        Set.of("http", "https").contains(uri.getScheme())
            && uri.getHost() != null
            && uri.getRawUserInfo() == null
            && uri.getFragment() == null,
        "Source URL must be HTTP(S) without credentials or a fragment.");
    String origin = uri.getScheme() + "://" + uri.getRawAuthority();
    if (!allowed.contains(origin))
      throw new ApiException(
          403,
          "source_origin_denied",
          "Source origin is not in RUNTIME_ALLOWED_ORIGINS: " + origin);
    if (uri.getQuery() != null)
      Json.require(
          !uri.getQuery().matches("(?i).*(token|password|secret|api.?key|authorization)=.*"),
          "Credentials cannot be placed in the URL.");
    if (!cfg.path("credentialRef").isNull() && !cfg.path("credentialRef").isMissingNode())
      throw new ApiException(
          501,
          "credential_provider_not_configured",
          "Configure a credential provider before using credentialRef.");
    if (!cfg.path("timestampPath").isNull() && !cfg.path("timestampPath").isMissingNode())
      pathSyntax(cfg.path("timestampPath").asText());
  }

  static final Pattern PART =
      Pattern.compile(
          "\\.([A-Za-z_][A-Za-z0-9_-]*)|\\[(\\d+)\\]|\\[\"([^\"\\\\]+)\"\\]|\\['([^'\\\\]+)'\\]");

  public static void pathSyntax(String path) {
    Json.require(
        path.startsWith("$") && path.length() <= 240,
        "Only bounded JSON field paths are accepted.");
    Matcher matcher = PART.matcher(path);
    int pos = 1;
    while (matcher.find()) {
      Json.require(matcher.start() == pos, "Unsupported JSON field path syntax.");
      pos = matcher.end();
    }
    Json.require(pos == path.length(), "Unsupported JSON field path syntax.");
  }

  public static JsonNode value(JsonNode root, String path) {
    pathSyntax(path);
    JsonNode current = root;
    Matcher m = PART.matcher(path);
    while (m.find()) {
      if (m.group(2) != null) {
        try {
          current = current.path(Integer.parseInt(m.group(2)));
        } catch (NumberFormatException e) {
          throw new ApiException(400, "invalid_source_path", "Array index is too large.");
        }
      } else
        current =
            current.path(
                m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(4));
    }
    return current;
  }

  public ObjectNode collect(JsonNode cfg) {
    validate(cfg);
    int timeout = cfg.path("timeoutMs").asInt();
    long started = System.nanoTime();
    try {
      // Streaming subscriber aborts before allocating beyond 256 KiB; future timeout covers body
      // receipt too.
      var response =
          http.sendAsync(
                  HttpRequest.newBuilder(URI.create(cfg.path("url").asText()))
                      .timeout(Duration.ofMillis(timeout))
                      .header("Accept", "application/json")
                      .GET()
                      .build(),
                  info -> new LimitedBodySubscriber())
              .get(timeout, TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200)
        throw new ApiException(
            502,
            "source_http_error",
            "Source returned HTTP " + response.statusCode() + "; redirects are not followed.");
      byte[] bytes = response.body();
      JsonNode payload = Json.M.readTree(bytes);
      Json.require(
          payload != null && (payload.isObject() || payload.isArray()),
          "Source payload must be a JSON object or array.");
      String timestamp = null;
      if (!cfg.path("timestampPath").isNull() && !cfg.path("timestampPath").isMissingNode()) {
        JsonNode v = value(payload, cfg.path("timestampPath").asText());
        if (!v.isTextual())
          throw new ApiException(
              502,
              "invalid_source_timestamp",
              "Configured source timestamp must be an ISO-8601 string.");
        timestamp = Instant.parse(v.asText()).toString();
        if (Instant.parse(timestamp).isAfter(Instant.now().plusSeconds(300)))
          throw new ApiException(
              502,
              "source_clock_error",
              "Source timestamp is more than five minutes in the future.");
      }
      return Json.obj(
          "quality",
          "good",
          "collectedAt",
          Instant.now().toString(),
          "sourceTimestamp",
          timestamp,
          "durationMs",
          (System.nanoTime() - started) / 1_000_000,
          "responseBytes",
          bytes.length,
          "payload",
          payload);
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "collection_interrupted", "Source collection was interrupted.");
    } catch (Exception e) {
      throw new ApiException(
          502,
          "source_connection_failed",
          "Source collection failed: "
              + e.getClass().getSimpleName()
              + " "
              + Objects.toString(e.getMessage(), ""));
    }
  }

  public ObjectNode probe(String id, JsonNode source) {
    ObjectNode sample = collect(source.path("config"));
    List<ObjectNode> fields = new ArrayList<>();
    flatten(sample.path("payload"), "$", fields, 0);
    return Json.obj(
        "dataSource",
        Json.obj("id", id, "name", source.path("name"), "sourceType", source.path("sourceType")),
        "collectedAt",
        sample.path("collectedAt"),
        "sourceTimestamp",
        sample.path("sourceTimestamp"),
        "sourceAgeSeconds",
        sample.path("sourceTimestamp").isNull()
            ? null
            : Duration.between(
                    Instant.parse(sample.path("sourceTimestamp").asText()), Instant.now())
                .toSeconds(),
        "durationMs",
        sample.path("durationMs"),
        "responseBytes",
        sample.path("responseBytes"),
        "fields",
        fields.stream().limit(100).toList(),
        "fieldsTruncated",
        fields.size() > 100);
  }

  void flatten(JsonNode n, String path, List<ObjectNode> fields, int depth) {
    if (fields.size() > 100 || depth > 12) return;
    if (n.isObject())
      n.fields()
          .forEachRemaining(
              e -> flatten(e.getValue(), path + "[\"" + e.getKey() + "\"]", fields, depth + 1));
    else if (n.isArray()) {
      for (int i = 0; i < n.size() && fields.size() <= 100; i++)
        flatten(n.get(i), path + "[" + i + "]", fields, depth + 1);
    } else
      fields.add(
          Json.obj(
              "path",
              path,
              "valueType",
              n.isNumber() ? "number" : n.isBoolean() ? "boolean" : n.isNull() ? "null" : "string",
              "sample",
              n));
  }

  static class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> future = new CompletableFuture<>();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() {
      return future;
    }

    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    public void onNext(List<java.nio.ByteBuffer> items) {
      for (var item : items) {
        if (bytes.size() + item.remaining() > 256 * 1024) {
          subscription.cancel();
          future.completeExceptionally(new IOException("Source response exceeds 256 KiB"));
          return;
        }
        byte[] chunk = new byte[item.remaining()];
        item.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      future.completeExceptionally(error);
    }

    public void onComplete() {
      future.complete(bytes.toByteArray());
    }
  }
}
