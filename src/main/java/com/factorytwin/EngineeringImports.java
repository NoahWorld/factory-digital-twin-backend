package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EngineeringImports {
  static final long MAX_SOURCE_BYTES = 100L * 1024 * 1024;
  static final int MAX_OPTIONS_BYTES = 2 * 1024 * 1024;
  static final Set<String> ROLES = Set.of("floor", "wall", "column", "equipment", "ignore");
  static final Set<String> UNITS = Set.of("mm", "cm", "m", "in", "ft");
  final Projects p;
  final ObjectStorage store;
  final EngineeringProcess engine;
  final Logger log = LoggerFactory.getLogger(EngineeringImports.class);

  EngineeringImports(Projects p, ObjectStorage store, EngineeringProcess engine) {
    this.p = p;
    this.store = store;
    this.engine = engine;
  }

  ObjectNode capabilities() {
    return Json.obj("available", engine.available(), "formats", List.of("ifc", "dxf"),
        "maxSourceBytes", MAX_SOURCE_BYTES, "maxOutputBytes", FileInspection.limit("model", "model.glb"),
        "reason", engine.unavailableReason(), "message", engine.unavailableMessage());
  }

  void requireEngine() {
    if (!engine.available()) throw new ApiException(503, "engineering_unavailable", engine.unavailableMessage());
  }

  static String format(String filename) {
    Json.require(filename != null && filename.length() <= 240 && !filename.matches(".*[/\\\\\\p{Cntrl}].*"),
        "A plain CAD/BIM filename of at most 240 characters is required.");
    int dot = filename.lastIndexOf('.');
    if (dot <= 0 || !Set.of("ifc", "dxf").contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT)))
      throw new ApiException(415, "engineering_format_unsupported", "Supported files: IFC and DXF. Export RVT to IFC or DWG to DXF before uploading.");
    return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  ObjectNode present(Map<String, Object> row) {
    var result = Json.obj("id", row.get("id"), "projectId", row.get("project_id"),
        "name", row.get("name"), "fileName", row.get("filename"), "format", row.get("format"),
        "status", row.get("status"), "stage", row.get("stage"), "message", row.get("message"),
        "progress", row.get("progress"), "createdAt", Json.timestamp(row.get("created_at")),
        "updatedAt", Json.timestamp(row.get("updated_at")), "resourceId", row.get("resource_id"));
    for (String key : List.of("inspection", "options", "result", "error"))
      result.set(key, row.get(key) == null ? Json.M.nullNode() : Json.parse(row.get(key).toString()));
    return result;
  }

  Map<String, Object> row(Auth.User u, String project, String id) {
    p.access(u, project, false);
    var rows = p.db.queryForList("SELECT * FROM engineering_imports WHERE tenant_id=? AND project_id=? AND id=?",
        u.tenant(), project, id);
    if (rows.isEmpty()) throw new ApiException(404, "engineering_import_not_found", "Import not found in this project.");
    return rows.getFirst();
  }

  List<ObjectNode> list(Auth.User u, String project) {
    p.access(u, project, false);
    return p.db.queryForList("SELECT * FROM engineering_imports WHERE tenant_id=? AND project_id=? ORDER BY CASE WHEN status IN ('queued','inspecting','converting') THEN 0 ELSE 1 END,created_at DESC LIMIT 100",
        u.tenant(), project).stream().map(this::present).toList();
  }

  ObjectNode upload(Auth.User u, String project, String name, MultipartFile source) throws IOException {
    p.access(u, project, true);
    requireEngine();
    String filename = source.getOriginalFilename(), type = format(filename);
    String checkedName = Json.text(Json.obj("name", name), "name", 1, 100);
    if (source.isEmpty() || source.getSize() > MAX_SOURCE_BYTES)
      throw new ApiException(413, "engineering_source_size", "IFC/DXF source must be nonempty and at most 100 MiB.");
    String id = Json.id(), key = u.tenant() + "/" + project + "/engineering/" + id + "/source." + type;
    p.tx.executeWithoutResult(st -> {
      p.lock(u, project);
      p.access(u, project, true);
      reserveQueue(u.tenant());
      p.db.update("INSERT INTO engineering_imports(id,tenant_id,project_id,name,filename,format,source_key,source_bytes,status,stage,message) VALUES(?,?,?,?,?,?,?,?,'queued','uploading','正在保存源文件')",
          id, u.tenant(), project, checkedName, filename, type, key, source.getSize());
    });
    Path staged = null;
    try {
      // S3 signing and retries may read the request body more than once. Servlet multipart
      // streams need not support reset, so retain a file the SDK can reopen for every read.
      staged = Files.createTempFile("twin-engineering-upload-", ".source");
      source.transferTo(staged);
      if (Files.size(staged) != source.getSize()) throw new IOException("Engineering source byte count changed while staging upload.");
      store.client.putObject(b -> b.bucket(store.bucket).key(key).contentType("application/octet-stream"), software.amazon.awssdk.core.sync.RequestBody.fromFile(staged));
    } catch (Exception e) {
      p.db.update("UPDATE engineering_imports SET status='failed',stage='failed',message='源文件保存失败，请重新上传',error=?::jsonb,updated_at=now() WHERE id=? AND stage='uploading'",
          Json.obj("code", "engineering_upload_failed", "message", "源文件保存失败，请重新上传。任务 ID：" + id).toString(), id);
      log.error("engineering_upload_failed import={} project={}", id, project, e);
      throw e;
    } finally {
      if (staged != null) {
        try { Files.deleteIfExists(staged); }
        catch (IOException e) { log.error("engineering_upload_cleanup_failed import={} path={}", id, staged, e); }
      }
    }
    p.db.update("UPDATE engineering_imports SET stage='queued',message='源文件已保存，等待后台检查',updated_at=now() WHERE id=? AND stage='uploading'", id);
    p.auth.audit(u, project, "engineering.upload", Json.obj("importId", id, "format", type, "bytes", source.getSize()));
    return present(row(u, project, id));
  }

  // All submissions lock this row, making the tenant-wide limit atomic across projects.
  void reserveQueue(String tenant) {
    p.db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", tenant);
    Integer count = p.db.queryForObject("SELECT count(*) FROM engineering_imports WHERE tenant_id=? AND status IN ('queued','inspecting','converting')", Integer.class, tenant);
    if (count >= 10) throw new ApiException(429, "engineering_queue_full", "At most 10 active engineering imports per tenant; wait for one to complete.");
  }

  static ObjectNode validateOptions(JsonNode input, JsonNode inspection, String format) {
    Json.fields(input, "schemaVersion", "name", "units", "layers", "center");
    Json.require(input.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_OPTIONS_BYTES,
        "Conversion options exceed 2 MiB; reduce layer mappings or names.");
    Json.integer(input, "schemaVersion", 1, 1);
    Json.text(input, "name", 1, 100);
    Json.require(input.path("center").isBoolean(), "center must be a boolean.");
    Json.require(input.path("layers").isArray() && input.path("layers").size() <= 2000, "layers must contain at most 2000 layer mappings.");
    Json.require(format.equals("ifc") || UNITS.contains(input.path("units").asText()), "Select CAD drawing units: mm, cm, m, in or ft.");
    if (format.equals("ifc")) Json.require(input.path("layers").isEmpty(), "IFC geometry uses native object definitions, not CAD layer extrusion.");
    Set<String> expected = new HashSet<>(), mapped = new HashSet<>();
    for (JsonNode layer : inspection.path("layers")) expected.add(layer.path("name").asText());
    boolean modeled = false;
    for (JsonNode layer : input.path("layers")) {
      Json.fields(layer, "name", "role", "height", "base");
      Json.text(layer, "name", 1, 255);
      String name = layer.path("name").asText(), role = Json.text(layer, "role", 1, 20);
      Json.require(expected.contains(name), "Unknown CAD layer: " + name);
      Json.require(mapped.add(name), "Duplicate CAD layer mapping: " + name);
      Json.require(ROLES.contains(role), "Unknown layer role: " + role);
      finite(layer, "height", 0, 1000);
      Json.require(role.equals("ignore") || layer.path("height").asDouble() > 0, "Model height must be greater than zero meters.");
      finite(layer, "base", -10000, 10000);
      if (!role.equals("ignore")) modeled = true;
    }
    if (format.equals("dxf")) {
      Json.require(expected.equals(mapped), "Every CAD layer must be mapped or explicitly ignored.");
      Json.require(modeled, "Select at least one CAD layer to model.");
    }
    return ((ObjectNode) input).deepCopy();
  }

  static void finite(JsonNode input, String key, double min, double max) {
    JsonNode value = input.path(key);
    Json.require(value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= min && value.asDouble() <= max,
        key + " must be finite and between " + min + " and " + max + " meters.");
  }

  ObjectNode convert(Auth.User u, String project, String id, JsonNode input) {
    p.access(u, project, true);
    requireEngine();
    return p.tx.execute(st -> {
      p.lock(u, project);
      p.access(u, project, true);
      var job = row(u, project, id);
      if (!Set.of("needs_configuration", "failed").contains(job.get("status")) || job.get("inspection") == null)
        throw new ApiException(409, "engineering_state_conflict", "Only an inspected import awaiting configuration or a failed conversion can be submitted.");
      var options = validateOptions(input, Json.parse(job.get("inspection").toString()), (String) job.get("format"));
      reserveQueue(u.tenant());
      p.db.update("UPDATE engineering_imports SET status='queued',mode='convert',stage='queued',message='等待后台生成 GLB',progress=0,attempts=0,error=NULL,options=?::jsonb,name=?,updated_at=now() WHERE id=?",
          options.toString(), options.path("name").asText(), id);
      p.auth.audit(u, project, "engineering.convert", Json.obj("importId", id, "options", options));
      return present(row(u, project, id));
    });
  }

  // Caller holds the project lock. This fences uploads/conversion submission during deletion.
  void deleteProjectSources(String tenant, String project) {
    var jobs = p.db.queryForList("SELECT id,status,source_key FROM engineering_imports WHERE tenant_id=? AND project_id=?", tenant, project);
    if (jobs.stream().anyMatch(j -> Set.of("queued", "inspecting", "converting").contains(j.get("status"))))
      throw new ApiException(409, "engineering_import_active", "Wait for active BIM/CAD imports before deleting this project.");
    for (var job : jobs) store.client.deleteObject(b -> b.bucket(store.bucket).key((String) job.get("source_key")));
  }
}

@RestController
class EngineeringImportController {
  final EngineeringImports s;
  EngineeringImportController(EngineeringImports s) { this.s = s; }

  @GetMapping("/api/v1/engineering-imports/capabilities")
  Object capabilities(HttpServletRequest r) {
    s.p.auth.require(r);
    return s.capabilities();
  }

  @GetMapping("/api/v1/projects/{project}/engineering-imports")
  Object list(@PathVariable String project, HttpServletRequest r) {
    return Json.obj("imports", s.list(s.p.auth.require(r), project));
  }

  @GetMapping("/api/v1/projects/{project}/engineering-imports/{id}")
  Object get(@PathVariable String project, @PathVariable String id, HttpServletRequest r) {
    return Json.obj("import", s.present(s.row(s.p.auth.require(r), project, id)));
  }

  @PostMapping(value = "/api/v1/projects/{project}/engineering-imports", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Object upload(@PathVariable String project, @RequestParam String name, @RequestParam("file") MultipartFile file, HttpServletRequest r) throws IOException {
    return Json.obj("import", s.upload(s.p.auth.require(r), project, name, file));
  }

  @PostMapping("/api/v1/projects/{project}/engineering-imports/{id}/convert")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Object convert(@PathVariable String project, @PathVariable String id, @RequestBody JsonNode input, HttpServletRequest r) {
    return Json.obj("import", s.convert(s.p.auth.require(r), project, id, input));
  }
}
