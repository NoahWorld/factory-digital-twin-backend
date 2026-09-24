package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@Service
public class Documents {
  final Projects p;
  final Contracts contracts;
  final ProjectCovers covers;

  public Documents(Projects p, Contracts c, ProjectCovers covers) {
    this.p = p;
    this.contracts = c;
    this.covers = covers;
  }

  static ObjectNode theme() {
    return Json.obj(
        "mode",
        "dark",
        "presetId",
        "deep-blue",
        "backgroundPattern",
        "circuit",
        "fontFamily",
        "industrial",
        "glowIntensity",
        .65,
        "panelRadius",
        6,
        "backgroundColor",
        "#04131f",
        "surfaceColor",
        "#08273b",
        "textColor",
        "#e9f8ff",
        "accentColor",
        "#55d8ff",
        "borderColor",
        "#276f8d");
  }

  static ObjectNode settings(Contracts contracts) {
    return contracts.normalizeSceneSettings(Json.obj(
        "animationSpeed",
        1,
        "autoRotate",
        false,
        "backgroundColor",
        "#071525",
        "backgroundOpacity",
        1,
        "cameraFov",
        42,
        "cameraView",
        "isometric",
        "environmentLightColor",
        "#daf4ff",
        "environmentLightIntensity",
        2.1,
        "keyLightColor",
        "#ffffff",
        "keyLightIntensity",
        2.4,
        "modelScale",
        1,
        "playAnimations",
        true,
        "rotationSpeed",
        .35,
        "showGrid",
        true));
  }

  void kind(JsonNode project, String kind) {
    Json.require(
        (kind.equals("canvas") ? "2d" : "3d").equals(project.path("projectType").asText()),
        "Project type does not support this document.");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ObjectNode read(Auth.User u, String id, String kind) {
    JsonNode project = p.access(u, id, false);
    kind(project, kind);
    var row =
        p.db.queryForMap(
            "SELECT * FROM documents WHERE tenant_id=? AND project_id=?", u.tenant(), id);
    var items =
        p.db.query(
            "SELECT body::text FROM document_items WHERE tenant_id=? AND project_id=? ORDER BY"
                + " sort_order,id",
            (r, i) -> Json.parse(r.getString(1)),
            u.tenant(),
            id);
    ObjectNode doc =
        Json.obj(
            "projectId",
            id,
            "revision",
            row.get("revision"),
            "updatedAt",
            Json.timestamp(row.get("updated_at")));
    if (kind.equals("canvas")) {
      doc.put("width", 1920).put("height", 1080);
      doc.set("theme", Json.parse(row.get("settings").toString()));
      doc.set("nodes", Json.M.valueToTree(items));
    } else {
      var stored = (ObjectNode) Json.parse(row.get("settings").toString());
      doc.set("settings", contracts.sceneSettingsFromStorage(stored));
      doc.set("fluids", contracts.sceneFluidsFromStorage(stored));
      doc.set("instances", Json.M.valueToTree(items));
      doc.set("linked2dProjectId", Json.M.valueToTree(row.get("linked_project_id")));
    }
    boolean editable =
        u.admin() || Set.of("owner", "editor").contains(project.path("projectRole").asText());
    return Json.obj(
        "project",
        project,
        kind,
        doc,
        "editable",
        editable,
        "limits",
        kind.equals("scene") ? contracts.limits : null);
  }

  @Transactional
  public ObjectNode patch(Auth.User u, String id, String kind, ObjectNode b) {
    if (kind.equals("canvas")) contracts.normalizeCanvasPatch(b);
    else contracts.normalizeScenePatch(b);
    contracts.validate(kind.equals("canvas") ? "CanvasPatch" : "StandaloneScenePatch", b);
    if (kind.equals("scene") && b.has("fluids")) contracts.fluids(b.get("fluids"));
    long expected = Json.integer(b, "expectedRevision", 0, 9007199254740991L);
    p.access(u, id, true);
    p.lock(u, id);
    JsonNode project = p.access(u, id, true);
    kind(project, kind);
    var row =
        p.db.queryForMap(
            "SELECT * FROM documents WHERE tenant_id=? AND project_id=? FOR UPDATE",
            u.tenant(),
            id);
    long revision = ((Number) row.get("revision")).longValue();
    if (revision != expected)
      throw new ApiException(
          409,
          "revision_conflict",
          "Document changed: expected "
              + expected
              + ", current "
              + revision
              + ". Reload and merge your draft.");
    String itemsKey = kind.equals("canvas") ? "upsertNodes" : "upsertInstances",
        deleteKey = kind.equals("canvas") ? "deleteNodeIds" : "deleteInstanceIds",
        settingsKey = kind.equals("canvas") ? "theme" : "settings";
    JsonNode upserts = b.path(itemsKey), deletes = b.path(deleteKey);
    Json.require(upserts.size() + deletes.size() <= 100, "A patch can change at most 100 items.");
    Json.require(
        upserts.size() + deletes.size() > 0 || b.has(settingsKey) || b.has("linked2dProjectId") || b.has("fluids"),
        "A patch must contain a change.");
    Set<String> changed = new HashSet<>();
    for (JsonNode item : upserts) {
      String itemId = item.path("id").asText();
      Json.require(changed.add(itemId), "Duplicate patch item.");
      if (kind.equals("canvas")) {
        contracts.canvasNode(item);
        for (JsonNode ref : item.path("resourceRefs"))
          resource(
              u,
              id,
              ref.asText(),
              item.path("type").asText().equals("model-3d") ? "model" : "image");
        for (JsonNode ref : item.path("dataBindingRefs"))
          Json.require(
              p.db.queryForObject(
                      "SELECT count(*) FROM data_bindings WHERE tenant_id=? AND project_id=? AND"
                          + " id=?",
                      Integer.class,
                      u.tenant(),
                      id,
                      ref.asText())
                  == 1,
              "Binding does not belong to this project.");
        if (item.path("type").asText().equals("scene-3d")
            && !item.path("props").path("sceneProjectId").isNull()) {
          JsonNode linked = p.access(u, item.path("props").path("sceneProjectId").asText(), false);
          Json.require(
              linked.path("projectType").asText().equals("3d"),
              "Scene reference requires a 3D project.");
        }
      } else {
        contracts.instance((ObjectNode) item);
        resource(u, id, item.path("modelAssetId").asText(), "model");
      }
      int order = item.path(kind.equals("canvas") ? "zIndex" : "sortOrder").asInt();
      p.db.update(
          "INSERT INTO document_items(tenant_id,project_id,id,sort_order,body)"
              + " VALUES(?,?,?,?,?::jsonb) ON CONFLICT(project_id,id) DO UPDATE SET"
              + " sort_order=excluded.sort_order,body=excluded.body",
          u.tenant(),
          id,
          itemId,
          order,
          item.toString());
    }
    for (JsonNode d : deletes) {
      Contracts.identifier(d.asText());
      Json.require(changed.add(d.asText()), "Duplicate patch item.");
      p.db.update(
          "DELETE FROM document_items WHERE tenant_id=? AND project_id=? AND id=?",
          u.tenant(),
          id,
          d.asText());
    }
    if (b.has(settingsKey) || (kind.equals("scene") && b.has("fluids"))) {
      JsonNode storedSettings = kind.equals("scene")
          ? contracts.sceneStorageAfterPatch((ObjectNode) Json.parse(row.get("settings").toString()), b)
          : b.path(settingsKey);
      Contracts.appearance(storedSettings);
      p.db.update(
          "UPDATE documents SET settings=?::jsonb WHERE tenant_id=? AND project_id=?",
          storedSettings.toString(),
          u.tenant(),
          id);
    }
    if (kind.equals("scene") && b.has("linked2dProjectId")) {
      String linked =
          b.path("linked2dProjectId").isNull() ? null : b.path("linked2dProjectId").asText();
      if (linked != null)
        Json.require(
            p.access(u, linked, false).path("projectType").asText().equals("2d"),
            "Linked project must be 2D.");
      p.db.update(
          "UPDATE documents SET linked_project_id=? WHERE tenant_id=? AND project_id=?",
          linked,
          u.tenant(),
          id);
    }
    if (kind.equals("scene")) budget(u, id);
    else
      Json.require(
          p.db.queryForObject(
                  "SELECT count(*) FROM document_items WHERE tenant_id=? AND project_id=?",
                  Integer.class,
                  u.tenant(),
                  id)
              <= 10000,
          "Canvas exceeds the current 10,000 node storage budget; split the project.");
    var finalItems = p.db.query(
        "SELECT body::text FROM document_items WHERE tenant_id=? AND project_id=?",
        (result, index) -> Json.parse(result.getString(1)), u.tenant(), id);
    String linkedProjectId = kind.equals("scene")
        ? (b.has("linked2dProjectId") ? (b.path("linked2dProjectId").isNull() ? null : b.path("linked2dProjectId").asText()) : (String) row.get("linked_project_id"))
        : null;
    TwinActions.validate(p, contracts, u, id, kind, linkedProjectId, finalItems);
    if (kind.equals("scene"))
      TwinDriveDocuments.guardScene(p, u, id,
          b.has("settings") ? b.path("settings") : Json.parse(row.get("settings").toString()), finalItems);
    p.db.update(
        "UPDATE documents SET revision=revision+1,updated_at=now() WHERE tenant_id=? AND"
            + " project_id=? AND revision=?",
        u.tenant(),
        id,
        expected);
    p.db.update("UPDATE projects SET updated_at=now() WHERE tenant_id=? AND id=?", u.tenant(), id);
    covers.refresh(u.tenant(), id);
    p.auth.audit(
        u,
        id,
        "document.patch",
        Json.obj(
            "kind",
            kind,
            "revision",
            expected + 1,
            "upserts",
            upserts.size(),
            "deletes",
            deletes.size(),
            "fluidsReplaced",
            b.has("fluids"),
            "fluidCount",
            b.has("fluids") ? b.path("fluids").size() : null));
    return read(u, id, kind);
  }

  public JsonNode resource(Auth.User u, String project, String id, String kind) {
    JsonNode builtin = contracts.builtinResource(kind, id);
    if (builtin != null) return builtin;
    var rows =
        p.db.queryForList(
            "SELECT byte_size,inspection FROM resources WHERE tenant_id=? AND project_id=? AND id=?"
                + " AND kind=? AND state='ready'",
            u.tenant(),
            project,
            id,
            kind);
    if (rows.isEmpty())
      throw new ApiException(
          400,
          "invalid_resource_reference",
          "Resource is missing, not ready, or belongs to another project: " + id);
    var row = rows.getFirst();
    return Json.obj(
        "id",
        id,
        "byteSize",
        row.get("byte_size"),
        "inspection",
        Json.parse(row.get("inspection").toString()));
  }

  void budget(Auth.User u, String id) {
    var items =
        p.db.query(
            "SELECT body::text FROM document_items WHERE tenant_id=? AND project_id=?",
            (r, i) -> Json.parse(r.getString(1)),
            u.tenant(),
            id);
    Json.require(items.size() <= 128, "Scene exceeds 128 instances.");
    Set<String> unique = new HashSet<>();
    long bytes = 0, meshes = 0, animated = 0;
    boolean playAnimations =
        Json.parse(
                p.db.queryForObject(
                    "SELECT settings::text FROM documents WHERE tenant_id=? AND project_id=?",
                    String.class,
                    u.tenant(),
                    id))
            .path("playAnimations")
            .asBoolean();
    for (JsonNode item : items) {
      String rid = item.path("modelAssetId").asText();
      JsonNode model = resource(u, id, rid, "model");
      if (unique.add(rid)) bytes += model.path("byteSize").asLong();
      meshes += model.path("inspection").path("meshCount").asLong();
      if (playAnimations
          && item.path("animation").path("enabled").asBoolean(true)
          && model.path("inspection").path("animationCount").asInt() > 0) animated++;
    }
    Json.require(
        unique.size() <= 24 && bytes <= 150L * 1024 * 1024 && meshes <= 6000 && animated <= 24,
        "Scene exceeds model/byte/mesh/animation budget.");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ObjectNode chunk(Auth.User u, String id, long revision, int offset, int limit) {
    p.access(u, id, false);
    long current =
        p.db.queryForObject(
            "SELECT revision FROM documents WHERE tenant_id=? AND project_id=?",
            Long.class,
            u.tenant(),
            id);
    if (current != revision)
      throw new ApiException(
          409, "revision_conflict", "Manifest revision is no longer current; reload the manifest.");
    Json.require(offset >= 0 && limit >= 1 && limit <= 200, "Chunk offset/limit out of range.");
    var items =
        p.db.query(
            "SELECT body::text FROM document_items WHERE tenant_id=? AND project_id=? ORDER BY"
                + " sort_order,id LIMIT ? OFFSET ?",
            (r, i) -> Json.parse(r.getString(1)),
            u.tenant(),
            id,
            limit,
            offset);
    return Json.obj(
        "projectId", id, "revision", revision, "items", items, "offset", offset, "limit", limit);
  }
}

@RestController
@RequestMapping("/api/v1/projects/{id}")
class DocumentController {
  final Documents d;
  final Projects projects;
  final Contracts contracts;

  DocumentController(Documents d, Projects projects, Contracts contracts) {
    this.d = d;
    this.projects = projects;
    this.contracts = contracts;
  }

  @GetMapping("/{kind:canvas|scene}")
  Object read(@PathVariable String id, @PathVariable String kind, HttpServletRequest r) {
    return d.read(projects.auth.require(r), id, kind);
  }

  @PatchMapping("/{kind:canvas|scene}")
  Object patch(
      @PathVariable String id,
      @PathVariable String kind,
      @RequestBody ObjectNode b,
      HttpServletRequest r) {
    return d.patch(projects.auth.require(r), id, kind, b);
  }

  @GetMapping("/manifest")
  Object manifest(@PathVariable String id, HttpServletRequest r) {
    var u = projects.auth.require(r);
    projects.access(u, id, false);
    var row =
        projects.db.queryForMap(
            "SELECT project_id AS \"projectId\",revision,kind,settings,(SELECT count(*) FROM"
                + " document_items i WHERE i.project_id=d.project_id AND i.tenant_id=d.tenant_id)"
                + " AS \"itemCount\" FROM documents d WHERE tenant_id=? AND project_id=?",
            u.tenant(),
            id);
    row.put("settings", Json.parse(row.get("settings").toString()));
    if (row.get("kind").equals("scene")) {
      var stored = (ObjectNode) row.get("settings");
      row.put("settings", contracts.sceneSettingsFromStorage(stored));
      row.put("fluids", contracts.sceneFluidsFromStorage(stored));
    }
    return row;
  }

  @GetMapping("/document-items")
  Object chunks(
      @PathVariable String id,
      @RequestParam long revision,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit,
      HttpServletRequest r) {
    return d.chunk(projects.auth.require(r), id, revision, offset, limit);
  }
}
