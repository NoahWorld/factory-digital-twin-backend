package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RuntimeState {
  final StringRedisTemplate redis;
  final DataConfiguration data;

  public RuntimeState(StringRedisTemplate redis, DataConfiguration data) {
    this.redis = redis;
    this.data = data;
  }

  static String key(String tenant, String project) {
    return "twin:{" + tenant + ":" + project + "}";
  }

  static final DefaultRedisScript<String> PUBLISH =
      new DefaultRedisScript<>(
          """
          local id = redis.call('XADD', KEYS[2], 'MAXLEN', '~', 512, '*', 'sourceId', ARGV[1], 'data', ARGV[3])
          redis.call('HSET', KEYS[1], ARGV[1], ARGV[2], '_cursor', id)
          redis.call('EXPIRE', KEYS[1], 86400)
          redis.call('EXPIRE', KEYS[2], 86400)
          return id
          """,
          String.class);

  public String publish(String tenant, String project, String source, JsonNode payload) {
    return redis.execute(
        PUBLISH,
        List.of(key(tenant, project) + ":latest", key(tenant, project) + ":stream"),
        source,
        payload.toString(),
        event(tenant, project, source, payload).toString());
  }

  public ObjectNode snapshot(String tenant, String project) {
    var entries = redis.opsForHash().entries(key(tenant, project) + ":latest");
    ObjectNode sources = Json.obj();
    entries.forEach(
        (k, v) -> {
          if (!k.equals("_cursor"))
            sources.set(
                k.toString(), event(tenant, project, k.toString(), Json.parse(v.toString())));
        });
    return Json.obj(
        "schemaVersion",
        1,
        "type",
        "snapshot",
        "projectId",
        project,
        "cursor",
        entries.getOrDefault("_cursor", "0-0"),
        "sources",
        sources);
  }

  ObjectNode event(String tenant, String project, String source, JsonNode state) {
    // Only mapped business metrics leave the gateway; upstream JSON stays in the internal cache.
    var rows =
        data.p.db.queryForList(
            "SELECT b.body::text,a.asset_key FROM data_bindings b JOIN assets a ON a.id=b.asset_id"
                + " AND a.tenant_id=b.tenant_id WHERE b.tenant_id=? AND b.project_id=? AND"
                + " b.source_id=?",
            tenant,
            project,
            source);
    Map<String, ObjectNode> assets = new LinkedHashMap<>();
    for (var row : rows) {
      String key = (String) row.get("asset_key");
      JsonNode binding = Json.parse((String) row.get("body"));
      ObjectNode item =
          assets.computeIfAbsent(
              key,
              k ->
                  Json.obj(
                      "assetId",
                      k,
                      "timestamp",
                      state.path("sourceTimestamp").isNull()
                          ? state.path("collectedAt")
                          : state.path("sourceTimestamp"),
                      "values",
                      Json.obj(),
                      "quality",
                      "good",
                      "errors",
                      Json.M.createArrayNode()));
      String error = null;
      JsonNode value = Json.M.nullNode();
      if (!state.path("quality").asText().equals("good")) error = "source_offline";
      else {
        Instant received = Instant.parse(state.path("collectedAt").asText());
        int age = binding.path("staleAfterSeconds").asInt();
        if (received.plusSeconds(age).isBefore(Instant.now())
            || (!state.path("sourceTimestamp").isNull()
                && Instant.parse(state.path("sourceTimestamp").asText())
                    .plusSeconds(age)
                    .isBefore(Instant.now()))) error = "source_stale";
        else {
          value = SourceClient.value(state.path("payload"), binding.path("sourcePath").asText());
          boolean valid =
              switch (binding.path("valueType").asText()) {
                case "number" -> value.isNumber();
                case "boolean" -> value.isBoolean();
                case "string" -> value.isTextual();
                case "timestamp" -> value.isTextual() && validTimestamp(value.asText());
                default -> false;
              };
          if (!valid) error = "mapping_failed";
        }
      }
      if (error == null)
        ((ObjectNode) item.path("values")).set(binding.path("metricKey").asText(), value);
      else {
        item.put("quality", error.equals("source_stale") ? "stale" : "error");
        ((ArrayNode) item.path("errors"))
            .add(Json.obj("metricKey", binding.path("metricKey"), "error", error));
      }
    }
    return Json.obj(
        "sourceId",
        source,
        "collectedAt",
        state.path("collectedAt"),
        "quality",
        state.path("quality"),
        "assets",
        assets.values());
  }

  public ObjectNode asset(Auth.User u, String project, String asset) {
    data.p.access(u, project, false);
    ObjectNode assetBody = data.present(data.row(u, project, "assets", asset));
    var bindings = data.bindingRows(u, project, asset);
    if (bindings.isEmpty())
      throw new ApiException(409, "asset_has_no_bindings", "Asset has no data bindings.");
    ObjectNode values = Json.obj();
    List<ObjectNode> metrics = new ArrayList<>(), sources = new ArrayList<>();
    int stale = 86400, poll = 3600;
    Map<String, JsonNode> fetched = new HashMap<>();
    String collectedAt = null;
    for (var row : bindings) {
      JsonNode b = Json.parse(row.get("body").toString()),
          config = Json.parse(row.get("source_body").toString());
      String source = (String) row.get("source_id");
      JsonNode state =
          fetched.computeIfAbsent(
              source,
              k -> {
                Object raw = redis.opsForHash().get(key(u.tenant(), project) + ":latest", k);
                if (raw == null)
                  throw new ApiException(
                      503, "runtime_pending", "Collector has not produced a snapshot yet.");
                return Json.parse(raw.toString());
              });
      if (!state.path("quality").asText().equals("good"))
        throw new ApiException(
            502, "runtime_source_offline", state.path("error").asText("Source is offline."));
      int maxAge = b.path("staleAfterSeconds").asInt();
      Instant received = Instant.parse(state.path("collectedAt").asText());
      if (Duration.between(received, Instant.now()).getSeconds() > maxAge
          || (!state.path("sourceTimestamp").isNull()
              && Duration.between(
                          Instant.parse(state.path("sourceTimestamp").asText()), Instant.now())
                      .getSeconds()
                  > maxAge))
        throw new ApiException(
            503, "runtime_stale", "Source snapshot is stale; last received " + received);
      JsonNode value = SourceClient.value(state.path("payload"), b.path("sourcePath").asText());
      boolean valid =
          switch (b.path("valueType").asText()) {
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "string" -> value.isTextual();
            case "timestamp" -> value.isTextual() && validTimestamp(value.asText());
            default -> false;
          };
      if (!valid)
        throw new ApiException(
            502,
            "runtime_mapping_failed",
            "Missing or invalid value for metric " + b.path("metricKey").asText());
      values.set(b.path("metricKey").asText(), value);
      metrics.add(
          Json.obj(
              "bindingId",
              row.get("id"),
              "metricKey",
              b.path("metricKey"),
              "sourcePath",
              b.path("sourcePath"),
              "value",
              value,
              "valueType",
              b.path("valueType"),
              "unit",
              b.path("unit"),
              "staleAfterSeconds",
              maxAge));
      stale = Math.min(stale, maxAge);
      poll = Math.min(poll, config.path("config").path("intervalSeconds").asInt());
      collectedAt =
          collectedAt == null || received.isBefore(Instant.parse(collectedAt))
              ? received.toString()
              : collectedAt;
    }
    fetched.forEach(
        (id, state) ->
            sources.add(
                Json.obj(
                    "id",
                    id,
                    "name",
                    bindings.stream()
                        .filter(x -> x.get("source_id").equals(id))
                        .map(x -> Json.parse(x.get("source_body").toString()).path("name"))
                        .findFirst()
                        .orElseThrow(),
                    "collectedAt",
                    state.path("collectedAt"),
                    "sourceTimestamp",
                    state.path("sourceTimestamp"),
                    "durationMs",
                    state.path("durationMs"))));
    ObjectNode brief = Json.obj();
    for (String k : List.of("id", "assetId", "assetType", "modelNode", "name"))
      brief.set(k, assetBody.path(k));
    return Json.obj(
        "asset",
        brief,
        "timestamp",
        collectedAt,
        "values",
        values,
        "metrics",
        metrics,
        "sources",
        sources,
        "pollAfterSeconds",
        poll,
        "staleAfterSeconds",
        stale);
  }

  static boolean validTimestamp(String value) {
    try {
      Instant.parse(value);
      return true;
    } catch (java.time.format.DateTimeParseException e) {
      return false;
    }
  }
}
