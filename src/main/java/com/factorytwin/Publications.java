package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class Publications {
  private static final Logger log = LoggerFactory.getLogger(Publications.class);
  final Projects projects;
  private final ObjectStorage store;
  private final Contracts contracts;
  private final SourceClient sources;

  Publications(Projects projects, ObjectStorage store, Contracts contracts, SourceClient sources) {
    this.projects = projects;
    this.store = store;
    this.contracts = contracts;
    this.sources = sources;
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String hash(JsonNode node) {
    try {
      return sha(Json.M.writeValueAsBytes(node));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot hash publication snapshot", e);
    }
  }

  private List<ObjectNode> records(Auth.User user, String project, String table) {
    return projects.db.query(
        "SELECT id,body::text,created_at,updated_at FROM " + table
            + " WHERE tenant_id=? AND project_id=? ORDER BY id",
        (row, ignored) -> {
          ObjectNode body = (ObjectNode) Json.parse(row.getString(2));
          body.put("id", row.getString(1));
          body.put("projectId", project);
          body.put("createdAt", Json.timestamp(row.getTimestamp(3)));
          body.put("updatedAt", Json.timestamp(row.getTimestamp(4)));
          return body;
        }, user.tenant(), project);
  }

  private List<ObjectNode> bindings(Auth.User user, String project) {
    return projects.db.query(
        "SELECT id,asset_id,source_id,body::text FROM data_bindings"
            + " WHERE tenant_id=? AND project_id=? ORDER BY id",
        (row, ignored) -> {
          ObjectNode body = (ObjectNode) Json.parse(row.getString(4));
          body.put("id", row.getString(1));
          body.put("assetRecordId", row.getString(2));
          body.put("dataSourceId", row.getString(3));
          return body;
        }, user.tenant(), project);
  }

  private ObjectNode capture(Auth.User user, String root) {
    projects.access(user, root, false);
    Map<String, ObjectNode> captured = new LinkedHashMap<>();
    Set<String> pending = new LinkedHashSet<>();
    pending.add(root);
    Set<String> resourceIds = new LinkedHashSet<>();
    while (!pending.isEmpty()) {
      String projectId = pending.iterator().next();
      pending.remove(projectId);
      if (captured.containsKey(projectId)) continue;
      if (captured.size() >= 32)
        throw new ApiException(413, "publication_dependency_limit", "Publication links more than 32 projects.");
      JsonNode project = projects.access(user, projectId, false);
      String kind = project.path("projectType").asText().equals("2d") ? "canvas" : "scene";
      ObjectNode document = (ObjectNode) new Documents(projects, contracts, projects.covers)
          .read(user, projectId, kind).path(kind);
      List<ObjectNode> assets = records(user, projectId, "assets");
      List<ObjectNode> dataSources = records(user, projectId, "data_sources");
      List<ObjectNode> bindings = bindings(user, projectId);
      for (JsonNode item : document.path(kind.equals("canvas") ? "nodes" : "instances")) {
        if (kind.equals("canvas")) {
          for (JsonNode ref : item.path("resourceRefs")) resourceIds.add(ref.asText());
          if (item.path("type").asText().equals("scene-3d")) {
            String linked = item.path("props").path("sceneProjectId").asText("");
            if (!linked.isBlank() && !captured.containsKey(linked)) pending.add(linked);
          }
        } else resourceIds.add(item.path("modelAssetId").asText());
      }
      if (kind.equals("scene")) {
        String linked = document.path("linked2dProjectId").asText("");
        if (!linked.isBlank() && !captured.containsKey(linked)) pending.add(linked);
      }
      captured.put(projectId, Json.obj(
          "project", Json.obj("id", projectId, "name", project.path("name"),
              "projectType", project.path("projectType")),
          "document", document,
          "assets", assets,
          "dataSources", dataSources,
          "dataBindings", bindings));
    }
    ArrayNode resourceManifest = Json.M.createArrayNode();
    for (String id : resourceIds.stream().sorted().toList()) {
      if (contracts.builtinResource("model", id) != null
          || contracts.builtinResource("image", id) != null) continue;
      var rows = projects.db.queryForList(
          "SELECT id,project_id,kind,object_key,byte_size,sha256,state FROM resources"
              + " WHERE tenant_id=? AND id=?", user.tenant(), id);
      if (rows.isEmpty() || !captured.containsKey(rows.getFirst().get("project_id"))
          || !"ready".equals(rows.getFirst().get("state"))
          || rows.getFirst().get("sha256") == null)
        throw new ApiException(409, "publication_resource_invalid",
            "Referenced resource is missing or not ready: " + id);
      Map<String, Object> row = rows.getFirst();
      resourceManifest.add(Json.obj("id", id, "projectId", row.get("project_id"),
          "kind", row.get("kind"), "objectKey", row.get("object_key"),
          "byteSize", row.get("byte_size"), "sha256", row.get("sha256")));
    }
    return Json.obj("schemaVersion", 1, "rootProjectId", root,
        "projects", captured, "resources", resourceManifest);
  }

  private void verifyResources(JsonNode snapshot) {
    for (JsonNode resource : snapshot.path("resources")) {
      String id = resource.path("id").asText();
      long expectedSize = resource.path("byteSize").asLong();
      try (InputStream in = store.client.getObject(b -> b.bucket(store.bucket)
          .key(resource.path("objectKey").asText()))) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        long size = 0;
        int count;
        while ((count = in.read(buffer)) >= 0) {
          size += count;
          if (size > expectedSize)
            throw new ApiException(409, "publication_resource_integrity_failed",
                "Resource size changed: " + id);
          digest.update(buffer, 0, count);
        }
        if (size != expectedSize || !HexFormat.of().formatHex(digest.digest())
            .equals(resource.path("sha256").asText()))
          throw new ApiException(409, "publication_resource_integrity_failed",
              "Resource bytes changed: " + id);
      } catch (ApiException e) {
        throw e;
      } catch (Exception e) {
        throw new ApiException(503, "publication_resource_unavailable",
            "Cannot verify resource " + id + ": " + e.getMessage());
      }
    }
  }

  private long pointerRevision(Auth.User user, String project) {
    var rows = projects.db.queryForList(
        "SELECT pointer_revision FROM publication_current WHERE tenant_id=? AND project_id=?",
        user.tenant(), project);
    return rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("pointer_revision")).longValue();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ObjectNode draft(Auth.User user, String project) {
    projects.access(user, project, true);
    ObjectNode snapshot = capture(user, project);
    return Json.obj("draftHash", hash(snapshot),
        "documentRevision", snapshot.path("projects").path(project).path("document").path("revision"),
        "projectCount", snapshot.path("projects").size(),
        "resourceCount", snapshot.path("resources").size(),
        "pointerRevision", pointerRevision(user, project));
  }

  @Transactional(isolation = Isolation.SERIALIZABLE)
  public ObjectNode create(Auth.User user, String project, String title, String expectedDraftHash) {
    projects.lock(user, project);
    projects.access(user, project, true);
    ObjectNode snapshot = capture(user, project);
    String actualHash = hash(snapshot);
    if (!actualHash.equals(expectedDraftHash))
      throw new ApiException(409, "publication_draft_changed",
          "The project or a linked project changed after preflight. Review the draft again.");
    verifyResources(snapshot);
    String id = Json.id();
    long revision = snapshot.path("projects").path(project).path("document").path("revision").asLong();
    projects.db.update(
        "INSERT INTO publication_versions(id,tenant_id,project_id,document_revision,title,snapshot,created_by)"
            + " VALUES(?,?,?,?,?,?::jsonb,?)",
        id, user.tenant(), project, revision, title, snapshot.toString(), user.id());
    for (JsonNode resource : snapshot.path("resources"))
      projects.db.update("INSERT INTO publication_resources(tenant_id,version_id,resource_id) VALUES(?,?,?)",
          user.tenant(), id, resource.path("id").asText());
    projects.auth.audit(user, project, "publication.create",
        Json.obj("versionId", id, "draftHash", actualHash, "resources", snapshot.path("resources").size()));
    log.info("publication_created project={} version={} revision={} resources={}",
        project, id, revision, snapshot.path("resources").size());
    return Json.obj("versionId", id, "title", title, "documentRevision", revision,
        "draftHash", actualHash, "pointerRevision", pointerRevision(user, project));
  }

  @Transactional(readOnly = true)
  public ObjectNode list(Auth.User user, String project) {
    projects.access(user, project, false);
    var pointer = projects.db.queryForList(
        "SELECT version_id,pointer_revision,updated_at FROM publication_current"
            + " WHERE tenant_id=? AND project_id=?", user.tenant(), project);
    List<ObjectNode> versions = projects.db.query(
        "SELECT id,title,document_revision,created_by,created_at FROM publication_versions"
            + " WHERE tenant_id=? AND project_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
        (row, ignored) -> Json.obj("id", row.getString("id"), "title", row.getString("title"),
            "documentRevision", row.getLong("document_revision"),
            "createdBy", row.getString("created_by"),
            "createdAt", Json.timestamp(row.getTimestamp("created_at"))),
        user.tenant(), project);
    return Json.obj("activeVersionId", pointer.isEmpty() ? null : pointer.getFirst().get("version_id"),
        "pointerRevision", pointerRevision(user, project), "versions", versions);
  }

  private Map<String, Object> versionRow(Auth.User user, String project, String id) {
    projects.access(user, project, false);
    var rows = projects.db.queryForList(
        "SELECT * FROM publication_versions WHERE tenant_id=? AND project_id=? AND id=?",
        user.tenant(), project, id);
    if (rows.isEmpty()) throw new ApiException(404, "publication_not_found", "Publication version not found.");
    return rows.getFirst();
  }

  private ObjectNode publicSnapshot(JsonNode privateSnapshot) {
    ObjectNode copy = privateSnapshot.deepCopy();
    copy.path("projects").forEach(project -> {
      ((ObjectNode) project).remove(List.of("dataSources", "dataBindings"));
    });
    for (JsonNode resource : copy.path("resources")) ((ObjectNode) resource).remove("objectKey");
    return copy;
  }

  @Transactional(readOnly = true)
  public ObjectNode version(Auth.User user, String project, String id) {
    var row = versionRow(user, project, id);
    return Json.obj("version", Json.obj("id", id, "title", row.get("title"),
        "documentRevision", row.get("document_revision"),
        "createdBy", row.get("created_by"), "createdAt", Json.timestamp(row.get("created_at")),
        "snapshot", publicSnapshot(Json.parse(row.get("snapshot").toString()))));
  }

  @Transactional(readOnly = true)
  public ObjectNode active(Auth.User user, String project) {
    projects.access(user, project, false);
    var rows = projects.db.queryForList(
        "SELECT version_id,pointer_revision FROM publication_current WHERE tenant_id=? AND project_id=?",
        user.tenant(), project);
    if (rows.isEmpty() || rows.getFirst().get("version_id") == null)
      throw new ApiException(404, "publication_not_active", "This project has no active publication.");
    ObjectNode response = version(user, project, (String) rows.getFirst().get("version_id"));
    response.put("pointerRevision", ((Number) rows.getFirst().get("pointer_revision")).longValue());
    return response;
  }

  private void verifySources(JsonNode snapshot) {
    for (JsonNode project : snapshot.path("projects")) {
      for (JsonNode source : project.path("dataSources")) {
        // Keep rollback independent of transient upstream outages. Runtime polling reports failures.
        sources.validate(source.path("config"));
      }
    }
  }

  @Transactional
  public ObjectNode activate(Auth.User user, String project, String id, long expectedPointerRevision) {
    projects.lock(user, project);
    projects.access(user, project, true);
    var row = versionRow(user, project, id);
    projects.db.update("INSERT INTO publication_current(tenant_id,project_id) VALUES(?,?)"
        + " ON CONFLICT(project_id) DO NOTHING", user.tenant(), project);
    long current = pointerRevision(user, project);
    if (current != expectedPointerRevision)
      throw new ApiException(409, "publication_pointer_conflict",
          "Active version changed. Reload the version list before activating or rolling back.");
    JsonNode snapshot = Json.parse(row.get("snapshot").toString());
    verifyResources(snapshot);
    verifySources(snapshot);
    int changed = projects.db.update(
        "UPDATE publication_current SET version_id=?,pointer_revision=pointer_revision+1,"
            + " updated_by=?,updated_at=now() WHERE tenant_id=? AND project_id=? AND pointer_revision=?",
        id, user.id(), user.tenant(), project, expectedPointerRevision);
    if (changed != 1)
      throw new ApiException(409, "publication_pointer_conflict", "Active version changed during activation.");
    projects.db.update("UPDATE projects SET status='published',updated_at=now() WHERE tenant_id=? AND id=?",
        user.tenant(), project);
    projects.auth.audit(user, project, "publication.activate",
        Json.obj("versionId", id, "pointerRevision", current + 1));
    log.info("publication_activated project={} version={} pointerRevision={}", project, id, current + 1);
    return Json.obj("activeVersionId", id, "pointerRevision", current + 1);
  }

  @Transactional(readOnly = true)
  public String content(Auth.User user, String project, String versionId, String resourceId) {
    versionRow(user, project, versionId);
    var rows = projects.db.queryForList(
        "SELECT r.object_key FROM publication_resources pr JOIN resources r ON r.id=pr.resource_id"
            + " WHERE pr.tenant_id=? AND pr.version_id=? AND pr.resource_id=?",
        user.tenant(), versionId, resourceId);
    if (rows.isEmpty()) throw new ApiException(404, "publication_resource_not_found",
        "Resource is not part of this publication.");
    return store.download((String) rows.getFirst().get("object_key"));
  }

  @Transactional(readOnly = true)
  public ObjectNode runtimeAsset(Auth.User user, String project, String versionId,
      String snapshotProjectId, String assetId) {
    JsonNode snapshot = Json.parse(versionRow(user, project, versionId).get("snapshot").toString());
    JsonNode selected = snapshot.path("projects").path(snapshotProjectId);
    if (selected.isMissingNode()) throw new ApiException(404, "publication_project_not_found",
        "Project is not included in the publication.");
    JsonNode asset = null;
    for (JsonNode candidate : selected.path("assets"))
      if (assetId.equals(candidate.path("id").asText())) asset = candidate;
    if (asset == null) throw new ApiException(404, "publication_asset_not_found",
        "Asset is not included in the publication.");
    Map<String, JsonNode> sourceById = new LinkedHashMap<>();
    for (JsonNode source : selected.path("dataSources")) sourceById.put(source.path("id").asText(), source);
    Map<String, JsonNode> collected = new LinkedHashMap<>();
    ObjectNode values = Json.obj();
    List<ObjectNode> metrics = new ArrayList<>(), sourceDetails = new ArrayList<>();
    int stale = 86400, poll = 3600;
    Instant earliest = null;
    for (JsonNode binding : selected.path("dataBindings")) {
      if (!assetId.equals(binding.path("assetRecordId").asText())) continue;
      String sourceId = binding.path("dataSourceId").asText();
      JsonNode source = sourceById.get(sourceId);
      if (source == null) throw new ApiException(409, "publication_binding_invalid",
          "Snapshot binding refers to missing source " + sourceId);
      JsonNode state = collected.computeIfAbsent(sourceId,
          ignored -> sources.collect(source.path("config")));
      int age = binding.path("staleAfterSeconds").asInt();
      Instant received = Instant.parse(state.path("collectedAt").asText());
      if (received.plusSeconds(age).isBefore(Instant.now())
          || (!state.path("sourceTimestamp").isNull()
              && Instant.parse(state.path("sourceTimestamp").asText())
                  .plusSeconds(age).isBefore(Instant.now())))
        throw new ApiException(503, "runtime_stale", "Published source snapshot is stale.");
      JsonNode value = SourceClient.value(state.path("payload"), binding.path("sourcePath").asText());
      boolean valid = switch (binding.path("valueType").asText()) {
        case "number" -> value.isNumber();
        case "boolean" -> value.isBoolean();
        case "string" -> value.isTextual();
        case "timestamp" -> value.isTextual() && RuntimeState.validTimestamp(value.asText());
        default -> false;
      };
      if (!valid) throw new ApiException(502, "runtime_mapping_failed",
          "Published mapping failed: " + binding.path("metricKey").asText());
      values.set(binding.path("metricKey").asText(), value);
      metrics.add(Json.obj("bindingId", binding.path("id"), "metricKey", binding.path("metricKey"),
          "sourcePath", binding.path("sourcePath"), "value", value,
          "valueType", binding.path("valueType"), "unit", binding.path("unit"),
          "sourceId", sourceId, "timestamp", RuntimeState.metricTimestamp(state),
          "collectedAt", state.path("collectedAt"), "quality", "good",
          "staleAfterSeconds", age));
      stale = Math.min(stale, age);
      poll = Math.min(poll, source.path("config").path("intervalSeconds").asInt());
      if (earliest == null || received.isBefore(earliest)) earliest = received;
    }
    if (metrics.isEmpty()) throw new ApiException(409, "asset_has_no_bindings",
        "Published asset has no data bindings.");
    for (Map.Entry<String, JsonNode> entry : collected.entrySet()) {
      JsonNode state = entry.getValue();
      sourceDetails.add(Json.obj("id", entry.getKey(), "name", sourceById.get(entry.getKey()).path("name"),
          "collectedAt", state.path("collectedAt"),
          "sourceTimestamp", state.path("sourceTimestamp"),
          "durationMs", state.path("durationMs")));
    }
    ObjectNode brief = Json.obj();
    for (String key : List.of("id", "assetId", "assetType", "modelNode", "name"))
      brief.set(key, asset.path(key));
    return Json.obj("runtimeState", Json.obj("asset", brief, "timestamp", earliest.toString(),
        "values", values, "metrics", metrics, "sources", sourceDetails,
        "pollAfterSeconds", poll, "staleAfterSeconds", stale));
  }
}

@RestController
@RequestMapping("/api/v1/projects/{project}/publications")
class PublicationController {
  final Publications publications;
  final Auth auth;

  PublicationController(Publications publications, Auth auth) {
    this.publications = publications;
    this.auth = auth;
  }

  @GetMapping("/draft")
  Object draft(@PathVariable String project, HttpServletRequest request) {
    return publications.draft(auth.require(request), project);
  }

  @GetMapping
  Object list(@PathVariable String project, HttpServletRequest request) {
    return publications.list(auth.require(request), project);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Object create(@PathVariable String project, @RequestBody ObjectNode body, HttpServletRequest request) {
    Json.fields(body, "title", "expectedDraftHash");
    String title = Json.text(body, "title", 1, 100);
    String hash = Json.text(body, "expectedDraftHash", 64, 64);
    Json.require(hash.matches("[0-9a-f]{64}"), "Expected a SHA-256 draft hash.");
    return publications.create(auth.require(request), project, title, hash);
  }

  @GetMapping("/active")
  Object active(@PathVariable String project, HttpServletRequest request) {
    return publications.active(auth.require(request), project);
  }

  @GetMapping("/{id}")
  Object version(@PathVariable String project, @PathVariable String id, HttpServletRequest request) {
    return publications.version(auth.require(request), project, id);
  }

  @PostMapping("/{id}/activate")
  Object activate(@PathVariable String project, @PathVariable String id,
      @RequestBody ObjectNode body, HttpServletRequest request) {
    Json.fields(body, "expectedPointerRevision");
    long expected = Json.integer(body, "expectedPointerRevision", 0, Long.MAX_VALUE);
    return publications.activate(auth.require(request), project, id, expected);
  }

  @GetMapping("/{id}/resources/{resourceId}/content")
  ResponseEntity<Void> content(@PathVariable String project, @PathVariable String id,
      @PathVariable String resourceId, HttpServletRequest request) {
    String url = publications.content(auth.require(request), project, id, resourceId);
    return ResponseEntity.status(302).header("Location", url).build();
  }

  @GetMapping("/{id}/projects/{snapshotProjectId}/assets/{assetId}/runtime-state")
  Object runtime(@PathVariable String project, @PathVariable String id,
      @PathVariable String snapshotProjectId, @PathVariable String assetId, HttpServletRequest request) {
    return publications.runtimeAsset(auth.require(request), project,
        id, snapshotProjectId, assetId);
  }
}
