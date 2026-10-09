package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** The control configuration is versioned independently from scene presentation and covers. */
@Service
public class TwinDriveDocuments {
  static final int MAX_AUTOMATIC_PROJECTS = 128;
  private final Projects p;
  private final Contracts contracts;
  private final SourceClient sourceClient;

  public TwinDriveDocuments(Projects p, Contracts contracts, SourceClient sourceClient) {
    this.p = p;
    this.contracts = contracts;
    this.sourceClient = sourceClient;
  }

  static ObjectNode emptyConfig() {
    return Json.obj("version", 1, "enabled", false, "source", "api", "connection", Json.obj("protocol", "rest", "url", "", "timestampPath", "timestamp", "intervalMs", 500, "timeoutMs", 5000), "points", List.of(),
        "bindings", List.of(), "colliders", List.of(), "collisionRules", List.of(), "procedures", List.of());
  }

  ObjectNode stored(String tenant, String project) {
    var rows = p.db.queryForList(
        "SELECT revision,config::text FROM twin_drive_documents WHERE tenant_id=? AND project_id=?",
        tenant, project);
    return rows.isEmpty() ? Json.obj("projectId", project, "revision", 0, "config", emptyConfig())
        : Json.obj("projectId", project, "revision", rows.getFirst().get("revision"),
            "config", Json.parse((String) rows.getFirst().get("config")));
  }

  record AutomaticProject(String tenant, String project) {}

  /** Internal scheduler scope comes exclusively from persisted tenant/project pairs, never a synthetic user. */
  List<AutomaticProject> apiProjects() {
    var rows = p.db.query("SELECT d.tenant_id,d.project_id FROM twin_drive_documents d JOIN projects p"
        + " ON p.tenant_id=d.tenant_id AND p.id=d.project_id WHERE d.config->>'enabled'='true'"
        + " AND d.config->>'source'='api' ORDER BY d.tenant_id,d.project_id LIMIT ?",
        (r, i) -> new AutomaticProject(r.getString(1), r.getString(2)), MAX_AUTOMATIC_PROJECTS + 1);
    if (rows.size() > MAX_AUTOMATIC_PROJECTS)
      throw new ApiException(503, "motion_source_capacity_exceeded", "API source project capacity exceeds 128; disable excess configurations.");
    return rows;
  }

  public ObjectNode read(Auth.User u, String project) {
    var access = p.access(u, project, false);
    Json.require(access.path("projectType").asText().equals("3d"), "Data-driven motion requires a 3D project.");
    var doc = stored(u.tenant(), project);
    doc.put("editable", u.admin() || Set.of("owner", "editor").contains(access.path("projectRole").asText()));
    if (!doc.path("editable").asBoolean()) redact(doc);
    return doc;
  }

  static void redact(ObjectNode document) {
    if (document.path("config").path("connection").isObject()) {
      ObjectNode connection = (ObjectNode) document.path("config").path("connection");
      String url = connection.path("url").asText();
      if (!Set.of(ApiMotionSources.REST_PATH, ApiMotionSources.WS_PATH).contains(url)) connection.put("url", "").put("redacted", true);
      connection.remove("subscribeMessage");
    }
  }

  @Transactional
  public ObjectNode save(Auth.User u, String project, ObjectNode input) {
    Json.fields(input, "expectedRevision", "config");
    if (input.path("config").path("source").asText().equals("simulator"))
      throw new ApiException(410, "legacy_simulation_removed", "Platform simulation was removed; connect a business REST or WebSocket source.");
    contracts.validate("TwinDrivePatch", input);
    long expected = Json.integer(input, "expectedRevision", 0, 9007199254740990L);
    p.access(u, project, true);
    p.lock(u, project);
    var previous = read(u, project);
    if (previous.path("revision").asLong() != expected)
      throw new ApiException(409, "twin_revision_conflict", "Data-drive configuration changed; reload and merge your draft.");
    JsonNode config = input.path("config");
    validate(config);
    validateOrigin(config.path("connection"));
    validateReferences(u, project, config);
    if (config.path("enabled").asBoolean()) {
      // Serialize admission across tenants/API replicas; subsequent ticks remain tenant scoped.
      p.db.queryForList("SELECT pg_advisory_xact_lock(73190621001)");
      Integer active = p.db.queryForObject("SELECT count(*) FROM twin_drive_documents WHERE project_id<>?"
          + " AND config->>'enabled'='true' AND config->>'source'='api'", Integer.class, project);
      Json.require(active != null && active < MAX_AUTOMATIC_PROJECTS, "At most 128 API source projects may run at once.");
    }
    p.db.update("INSERT INTO twin_drive_documents(tenant_id,project_id,revision,config) VALUES(?,?,?,?::jsonb)"
        + " ON CONFLICT(project_id) DO UPDATE SET revision=excluded.revision,config=excluded.config,updated_at=now()",
        u.tenant(), project, expected + 1, config.toString());
    p.auth.audit(u, project, "twin_drive.save", Json.obj("revision", expected + 1, "points", config.path("points").size(),
        "bindings", config.path("bindings").size(), "collisionRules", config.path("collisionRules").size()));
    return read(u, project);
  }

  void validateReferences(Auth.User u, String project, JsonNode config) {
    var items = p.db.query("SELECT body::text FROM document_items WHERE tenant_id=? AND project_id=?",
        (r, i) -> Json.parse(r.getString(1)), u.tenant(), project);
    JsonNode settings = Json.parse(p.db.queryForObject(
        "SELECT settings::text FROM documents WHERE tenant_id=? AND project_id=?", String.class, u.tenant(), project));
    validateSceneReferences(config, settings, items);
    if (!config.path("source").asText().equals("api")) for (JsonNode point : config.path("points")) {
      Integer count = p.db.queryForObject("SELECT count(*) FROM assets WHERE tenant_id=? AND project_id=? AND asset_key=?",
          Integer.class, u.tenant(), project, point.path("assetId").asText());
      Json.require(count != null && count == 1, "Point " + point.path("id").asText() + " references an unknown business asset.");
    }
  }

  void validateOrigin(JsonNode connection) {
    String address = connection.path("url").asText();
    if (address.isEmpty() || address.equals(ApiMotionSources.REST_PATH) || address.equals(ApiMotionSources.WS_PATH)) return;
    java.net.URI uri = java.net.URI.create(address);
    String scheme = switch (uri.getScheme()) { case "ws" -> "http"; case "wss" -> "https"; default -> uri.getScheme(); };
    if (!sourceClient.allowed.contains(scheme + "://" + uri.getRawAuthority()))
      throw new ApiException(403, "source_origin_denied", "Source origin is not in RUNTIME_ALLOWED_ORIGINS.");
  }

  public void authorizeSourceTest(Auth.User user, String project) {
    Json.require(p.access(user, project, true).path("projectType").asText().equals("3d"), "Source testing requires edit access to a 3D project.");
  }

  static void guardScene(Projects p, Auth.User u, String project, JsonNode settings, List<JsonNode> items) {
    var rows = p.db.queryForList("SELECT config::text FROM twin_drive_documents WHERE tenant_id=? AND project_id=?",
        u.tenant(), project);
    if (!rows.isEmpty()) validateSceneReferences(Json.parse((String) rows.getFirst().get("config")), settings, items);
  }

  static void validateSceneReferences(JsonNode config, JsonNode settings, List<JsonNode> items) {
    Map<String, JsonNode> byId = new HashMap<>();
    items.forEach(item -> byId.put(item.path("id").asText(), item));
    for (String section : List.of("bindings", "colliders")) for (JsonNode item : config.path(section)) {
      JsonNode target = item.path("target"), instance = byId.get(target.path("instanceId").asText());
      Json.require(instance != null, section + " " + item.path("id").asText() + " references a missing model instance.");
      Json.require(instance.path("modelAssetId").equals(target.path("modelAssetId")),
          "Model resource changed for " + item.path("id").asText() + "; rebind the model node explicitly.");
      if (config.path("enabled").asBoolean() && section.equals("bindings")
          && settings.path("playAnimations").asBoolean() && instance.path("animation").path("enabled").asBoolean(true))
        throw new ApiException(409, "twin_animation_conflict",
            "Disable the bound instance's native animation before enabling data-driven motion: " + item.path("id").asText());
    }
  }

  static Map<String, JsonNode> unique(JsonNode array, String label, int maximum) {
    Json.require(array.isArray() && array.size() <= maximum, label + " exceeds its item budget.");
    Map<String, JsonNode> result = new LinkedHashMap<>();
    for (JsonNode item : array) {
      String id = Json.text(item, "id", 1, 120);
      Contracts.identifier(id);
      Json.require(result.put(id, item) == null, "Duplicate " + label + " id: " + id);
      Json.text(item, "label", 1, 120);
    }
    return result;
  }

  static double finite(JsonNode value, String label) {
    Json.require(value.isNumber() && Double.isFinite(value.asDouble()), label + " must be finite.");
    return value.asDouble();
  }

  static void vector(JsonNode value, String label, boolean positive) {
    Json.require(value.isArray() && value.size() == 3, label + " requires three coordinates.");
    for (JsonNode v : value) {
      double x = finite(v, label);
      Json.require(Math.abs(x) <= 1e6 && (!positive || x > 0), label + " is outside bounds.");
    }
  }

  static void target(JsonNode target) {
    Contracts.identifier(Json.text(target, "instanceId", 1, 120));
    Json.text(target, "modelAssetId", 1, 120);
    Json.text(target, "nodeName", 1, 256);
  }

  /** Semantic checks are deliberately shared in behavior with shared/twin-drive.ts. */
  static void validate(JsonNode config) {
    if (!config.path("source").asText().equals("api")) throw new ApiException(410, "legacy_simulation_removed", "Platform simulation was removed; connect a business API.");
    ApiMotionSources.validateConnection(config.path("connection"), !config.path("enabled").asBoolean() && config.path("points").isEmpty());
    Json.require(!config.has("simulation") && config.path("procedures").isArray() && config.path("procedures").isEmpty(), "API sources do not use platform simulation procedures.");
    Json.require(!config.path("enabled").asBoolean() || (!config.path("points").isEmpty() && !config.path("bindings").isEmpty()), "Enable API motion only after binding at least one model action.");
    Json.require(config.toString().getBytes(StandardCharsets.UTF_8).length <= 512 * 1024, "Data-drive configuration exceeds 512 KiB.");
    if (config.has("description")) Json.require(config.path("description").isTextual()
        && config.path("description").asText().length() <= 4000, "Description must be a string of at most 4000 characters.");
    var points = unique(config.path("points"), "points", 128);
    var bindings = unique(config.path("bindings"), "bindings", 128);
    var colliders = unique(config.path("colliders"), "colliders", 64);
    var rules = unique(config.path("collisionRules"), "collision rules", 128);
    var procedures = unique(config.path("procedures"), "procedures", 16);
    Set<String> metrics = new HashSet<>(), driven = new HashSet<>(), topics = new HashSet<>();
    for (JsonNode point : points.values()) {
      String asset = Json.text(point, "assetId", 0, 80); if (!asset.isEmpty()) Contracts.identifier(asset);
      Contracts.identifier(Json.text(point, "metricKey", 1, 80));
      Json.text(point, "unit", 0, 32);
      ApiMotionSources.pathSyntax(Json.text(point, "sourcePath", 1, 256));
      Json.require(!point.has("topic"), "API sources use mapped fields, not topic subscriptions.");
      if (point.has("topic")) {
        String topic = Json.text(point, "topic", 1, 200);
        Json.require(topic.matches("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$"), "Point topic must be an exact topic without wildcards.");
        Json.require(topics.add(topic), "Duplicate point topic: " + topic);
      }
      Json.require(metrics.add(point.path("assetId").asText() + "/" + point.path("metricKey").asText()), "Duplicate business asset metric.");
      double min = finite(point.path("min"), "point.min"), max = finite(point.path("max"), "point.max"), initial = finite(point.path("initialValue"), "initialValue");
      double speed = finite(point.path("maxSpeed"), "maxSpeed"), stale = finite(point.path("staleAfterMs"), "staleAfterMs");
      Json.require(min < max && Math.max(Math.abs(min), Math.abs(max)) <= 1e6 && Double.isFinite(max - min) && Math.abs(initial) <= 1e6 && speed >= 0 && speed <= 1e6
          && point.path("staleAfterMs").isIntegralNumber() && stale >= 500 && stale <= 60000, "Invalid point range, compatibility value budget or stale interval.");
      if (point.has("valueLabels")) {
        JsonNode valueLabels = point.path("valueLabels");
        Json.require(valueLabels.isArray() && valueLabels.size() >= 1 && valueLabels.size() <= 64, "Feedback labels require 1..64 values.");
        Set<Long> values = new HashSet<>();
        for (JsonNode label : valueLabels) {
          Json.require(label.isObject(), "Feedback labels must be objects.");
          Json.fields((ObjectNode) label, "value", "label");
          Json.require(label.path("value").isIntegralNumber() && label.path("value").asDouble() >= min && label.path("value").asDouble() <= max
              && values.add(label.path("value").asLong()), "Feedback label values must be unique integers within the point range.");
          String text = Json.text(label, "label", 1, 120);
          Json.require(!text.isBlank() && text.chars().noneMatch(c -> c < 32 || c >= 127 && c <= 159), "Feedback labels must be readable text.");
        }
      }
    }
    for (JsonNode binding : bindings.values()) {
      target(binding.path("target"));
      Json.require(driven.add(binding.path("target").path("instanceId").asText() + "/" + binding.path("target").path("nodeName").asText()), "A model node cannot be driven by multiple bindings.");
      Json.require(points.containsKey(binding.path("pointId").asText()), "Binding references an unknown point.");
      double valueScale = finite(binding.path("valueScale"), "valueScale");
      double valueOffset = finite(binding.path("valueOffset"), "valueOffset");
      JsonNode boundPoint = points.get(binding.path("pointId").asText());
      Json.require(Double.isFinite(boundPoint.path("min").asDouble() * valueScale + valueOffset)
          && Double.isFinite(boundPoint.path("max").asDouble() * valueScale + valueOffset),
          "Binding engineering range overflows after applying valueScale/valueOffset.");
      vector(binding.path("axis"), "axis", false); vector(binding.path("pivot"), "pivot", false);
      String kind = binding.path("kind").asText();
      if (Set.of("translation", "rotation").contains(kind)) {
        double norm = 0; for (JsonNode n : binding.path("axis")) norm += n.asDouble() * n.asDouble();
        Json.require(Math.abs(Math.sqrt(norm) - 1) <= 1e-5, "Motion axis must be a unit vector.");
      }
      Set<String> ancestors = new HashSet<>(); ancestors.add(binding.path("id").asText());
      JsonNode current = binding;
      while (!current.path("parentBindingId").isNull()) {
        String parent = current.path("parentBindingId").asText();
        Json.require(ancestors.add(parent), "Motion binding parent cycle.");
        current = bindings.get(parent);
        Json.require(current != null && current.path("target").path("instanceId").equals(binding.path("target").path("instanceId")), "Parent binding must belong to the same model instance.");
      }
      JsonNode poses = binding.path("poses");
      if (kind.equals("pose")) {
        Json.require(poses.size() >= 2 && poses.size() <= 64, "Pose bindings require 2..64 engineering-value poses.");
        double previous = Double.NEGATIVE_INFINITY;
        for (JsonNode pose : poses) {
          double value = finite(pose.path("value"), "pose.value");
          Json.require(value > previous, "Pose values must strictly increase."); previous = value;
          vector(pose.path("position"), "pose.position", false); vector(pose.path("rotation"), "pose.rotation", false); vector(pose.path("scale"), "pose.scale", false);
          for (JsonNode n : pose.path("scale")) Json.require(n.asDouble() >= 0, "Pose scale cannot be negative.");
        }
      } else Json.require(poses.isEmpty(), "Non-pose binding must not contain poses.");
    }
    for (JsonNode collider : colliders.values()) {
      target(collider.path("target")); vector(collider.path("center"), "collider.center", false); vector(collider.path("size"), "collider.size", true);
    }
    Set<Set<String>> pairs = new HashSet<>();
    for (JsonNode rule : rules.values()) {
      String first = rule.path("first").asText(), second = rule.path("second").asText();
      Json.require(!first.equals(second) && colliders.containsKey(first) && colliders.containsKey(second), "Invalid collision pair.");
      Json.require(pairs.add(Set.of(first, second)), "Duplicate collision pair.");
    }
    for (JsonNode procedure : procedures.values()) {
      var steps = unique(procedure.path("steps"), "procedure steps", 64);
      Json.require(!steps.isEmpty(), "Procedure requires at least one step.");
      for (JsonNode step : steps.values()) {
        Json.require(finite(step.path("tolerance"), "tolerance") >= 0, "Tolerance cannot be negative.");
        Json.integer(step, "timeoutMs", 1000, 600000);
        validateValues(step.path("targets"), points);
      }
    }
    if (config.has("simulation")) {
      JsonNode simulation = config.path("simulation");
      Json.fields(simulation, "enabled", "procedureId", "repeat");
      Json.require(simulation.path("enabled").isBoolean() && simulation.path("repeat").isBoolean(), "Simulation enabled/repeat must be booleans.");
      String procedureId = Json.text(simulation, "procedureId", 0, 120);
      if (!procedureId.isEmpty()) Contracts.identifier(procedureId);
      if (simulation.path("enabled").asBoolean()) {
        Json.require(config.path("enabled").asBoolean() && !points.isEmpty() && !bindings.isEmpty(), "Automatic simulation requires enabled data drive, points and bindings.");
        Json.require(procedures.containsKey(procedureId), "Automatic simulation references an unknown procedure.");
        Json.require(topics.size() == points.size(), "Every automatic simulation point requires an explicit topic.");
      }
    }
  }

  static void validateValues(JsonNode values, Map<String, JsonNode> points) {
    Json.require(values.isArray() && !values.isEmpty() && values.size() <= 128, "Targets require 1..128 point values.");
    Set<String> seen = new HashSet<>();
    for (JsonNode value : values) {
      Json.fields(value, "pointId", "value");
      String id = Json.text(value, "pointId", 1, 120);
      JsonNode point = points.get(id);
      Json.require(point != null && seen.add(id), "Unknown or duplicate point: " + id);
      double number = finite(value.path("value"), "value");
      Json.require(number >= point.path("min").asDouble() && number <= point.path("max").asDouble(), "Point value outside configured range: " + id);
    }
  }

  static void guardAssetRename(Projects p, Auth.User user, String project, String previous, String next) {
    if (previous.equals(next)) return;
    var rows = p.db.queryForList("SELECT config::text FROM twin_drive_documents WHERE tenant_id=? AND project_id=?", user.tenant(), project);
    if (!rows.isEmpty()) for (JsonNode point : Json.parse((String) rows.getFirst().get("config")).path("points"))
      if (point.path("assetId").asText().equals(previous))
        throw new ApiException(409, "twin_asset_referenced", "Rebind data-drive point " + point.path("id").asText() + " before renaming its business asset ID.");
  }
}

@RestController
@RequestMapping("/api/v1/projects/{project}/twin-drive")
class TwinDriveController {
  final TwinDriveDocuments documents;
  final Auth auth;
  final ApiMotionSources sources;
  TwinDriveController(TwinDriveDocuments documents, Auth auth, ApiMotionSources sources) { this.documents = documents; this.auth = auth; this.sources = sources; }
  @GetMapping Object read(@PathVariable String project, HttpServletRequest request) {
    return documents.read(auth.require(request), project);
  }
  @PutMapping Object save(@PathVariable String project, @RequestBody ObjectNode input, HttpServletRequest request) {
    return documents.save(auth.require(request), project, input);
  }
  @PostMapping("/test-source") Object test(@PathVariable String project, @RequestBody ObjectNode input, HttpServletRequest request) {
    Json.fields(input, "connection");
    documents.authorizeSourceTest(auth.require(request), project);
    return sources.test(input.path("connection"));
  }
}
