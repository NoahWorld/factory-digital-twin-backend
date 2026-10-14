package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

/** Project-owned immutable source slots and durable GPU reconstruction jobs. */
@Service
public class SiteCaptures {
  static final long IMAGE_BYTES = 8L * 1024 * 1024, VIDEO_BYTES = 100L * 1024 * 1024,
      TOTAL_BYTES = 500L * 1024 * 1024, OUTPUT_BYTES = 200L * 1024 * 1024;
  static final Set<String> ACTIVE = Set.of("queued", "running");
  final Projects p;
  final ObjectStorage store;
  final Logger log = LoggerFactory.getLogger(SiteCaptures.class);
  SiteCaptures(Projects p, ObjectStorage store) { this.p = p; this.store = store; }

  ObjectNode capabilities() {
    var ready = p.db.queryForList("SELECT id FROM site_capture_workers WHERE available=true AND heartbeat_at>now()-interval '90 seconds' LIMIT 1");
    boolean available = !ready.isEmpty();
    return Json.obj("available", available, "reason", available ? null : "GPU 重建服务未就绪。可先上传素材，连接通过检查的 NVIDIA GPU 工作节点后再开始生成。",
        "maxImages", 120, "minImages", 8, "maxImageBytes", IMAGE_BYTES, "maxVideoBytes", VIDEO_BYTES,
        "maxTotalBytes", TOTAL_BYTES, "maxOutputBytes", OUTPUT_BYTES);
  }

  void requireReady() {
    var caps = capabilities();
    if (!caps.path("available").asBoolean()) throw new ApiException(503, "site_capture_unavailable", caps.path("reason").asText());
  }

  static ObjectNode validateDraft(JsonNode input) {
    Json.fields(input, "name", "inputKind", "files", "clientRequestId");
    String name = Json.text(input, "name", 1, 100), kind = Json.text(input, "inputKind", 1, 10);
    String request = Json.text(input, "clientRequestId", 36, 36);
    try { Json.require(UUID.fromString(request).toString().equalsIgnoreCase(request), "clientRequestId must be a UUID."); }
    catch (IllegalArgumentException e) { throw new ApiException(400, "invalid_input", "clientRequestId must be a UUID."); }
    Json.require(Set.of("images", "video").contains(kind), "inputKind must be images or video.");
    var files = input.path("files");
    Json.require(files.isArray() && (kind.equals("video") ? files.size() == 1 : files.size() >= 8 && files.size() <= 120), "Use one video or 8–120 photos.");
    Set<String> names = new HashSet<>();
    long bytes = 0;
    var checked = Json.M.createArrayNode();
    for (var file : files) {
      Json.fields(file, "filename", "byteSize", "contentType");
      String filename = Json.text(file, "filename", 1, 240);
      String ext = FileInspection.extension(filename), mime = FileInspection.MIMES.get(ext);
      Json.require(kind.equals("video") ? Set.of("mp4", "webm").contains(ext) : Set.of("png", "jpg", "jpeg", "webp").contains(ext), "File format does not match inputKind.");
      Json.require(names.add(filename.toLowerCase(Locale.ROOT)), "Duplicate source filenames are not allowed.");
      Json.require(mime.equals(Json.text(file, "contentType", 1, 80)), "contentType must match the filename.");
      long size = Json.integer(file, "byteSize", 1, kind.equals("video") ? VIDEO_BYTES : IMAGE_BYTES);
      bytes += size;
      checked.add(Json.obj("filename", filename, "byteSize", size, "contentType", mime));
    }
    Json.require(bytes <= TOTAL_BYTES, "Combined source size exceeds 500 MiB.");
    return Json.obj("name", name, "inputKind", kind, "clientRequestId", request.toLowerCase(Locale.ROOT), "files", checked);
  }

  Map<String, Object> row(Auth.User user, String project, String id) {
    p.access(user, project, false);
    var rows = p.db.queryForList("SELECT * FROM site_captures WHERE tenant_id=? AND project_id=? AND id=?", user.tenant(), project, id);
    if (rows.isEmpty()) throw new ApiException(404, "site_capture_not_found", "此项目中不存在该写实漫游任务。");
    return rows.getFirst();
  }
  List<Map<String, Object>> sources(String id) {
    return p.db.queryForList("SELECT * FROM site_capture_sources WHERE capture_id=? ORDER BY source_index", id);
  }
  ObjectNode present(Map<String, Object> row) {
    return Json.obj("id", row.get("id"), "name", row.get("name"), "inputKind", row.get("input_kind"),
        "status", row.get("status"), "stage", row.get("stage"), "completed", row.get("completed"), "total", row.get("total"),
        "files", sources((String) row.get("id")).stream().map(f -> Json.obj("index", f.get("source_index"), "filename", f.get("filename"),
            "byteSize", f.get("byte_size"), "contentType", f.get("content_type"), "uploaded", f.get("uploaded"))).toList(),
        "createdAt", Json.timestamp(row.get("created_at")), "updatedAt", Json.timestamp(row.get("updated_at")),
        "errorCode", row.get("error_code"), "errorMessage", row.get("error_message"),
        "result", row.get("result") == null ? null : Json.parse(row.get("result").toString()));
  }
  List<ObjectNode> list(Auth.User user, String project) {
    p.access(user, project, false);
    return p.db.queryForList("SELECT * FROM site_captures WHERE tenant_id=? AND project_id=? ORDER BY created_at DESC LIMIT 100", user.tenant(), project).stream().map(this::present).toList();
  }

  ObjectNode draft(Auth.User user, String project, JsonNode input) {
    p.access(user, project, true);
    ObjectNode checked = validateDraft(input);
    return p.tx.execute(st -> {
      p.lock(user, project); p.access(user, project, true);
      var existing = p.db.queryForList("SELECT * FROM site_captures WHERE tenant_id=? AND project_id=? AND client_request_id=?", user.tenant(), project, checked.path("clientRequestId").asText());
      if (!existing.isEmpty()) {
        var previous = existing.getFirst();
        if (!Json.parse(previous.get("request_json").toString()).equals(Json.parse(checked.toString()))) throw new ApiException(409, "site_capture_request_conflict", "同一请求标识已用于不同的采集内容。");
        return present(previous);
      }
      reserve(user.tenant(), true);
      String id = Json.id();
      p.db.update("INSERT INTO site_captures(id,tenant_id,project_id,name,input_kind,client_request_id,request_json,status,stage,total) VALUES(?,?,?,?,?,?,?::jsonb,'uploading','uploading',?)",
          id, user.tenant(), project, checked.path("name").asText(), checked.path("inputKind").asText(), checked.path("clientRequestId").asText(), checked.toString(), checked.path("files").size());
      int index = 0;
      for (var file : checked.path("files")) p.db.update("INSERT INTO site_capture_sources(capture_id,source_index,filename,byte_size,content_type) VALUES(?,?,?,?,?)",
          id, index++, file.path("filename").asText(), file.path("byteSize").asLong(), file.path("contentType").asText());
      p.auth.audit(user, project, "site_capture.create", Json.obj("captureId", id, "inputKind", checked.path("inputKind"), "files", index));
      return present(row(user, project, id));
    });
  }
  void reserve(String tenant, boolean includeDrafts) {
    p.db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", tenant);
    Integer count = p.db.queryForObject("SELECT count(*) FROM site_captures WHERE tenant_id=? AND status IN " + (includeDrafts ? "('uploading','queued','running')" : "('queued','running')"), Integer.class, tenant);
    if (count >= 10) throw new ApiException(429, "site_capture_queue_full", "最多保留 10 个未完成任务，请完成或删除已有任务。");
  }

  ObjectNode upload(Auth.User user, String project, String id, int index, HttpServletRequest request) throws IOException {
    p.access(user, project, true);
    String token = Json.id();
    var source = p.tx.execute(st -> {
      p.lock(user, project); p.access(user, project, true);
      var job = row(user, project, id);
      if (!job.get("status").equals("uploading")) throw conflict();
      var slots = p.db.queryForList("SELECT * FROM site_capture_sources WHERE capture_id=? AND source_index=? FOR UPDATE", id, index);
      if (slots.isEmpty()) throw new ApiException(404, "site_capture_not_found", "素材序号不存在。");
      var slot = slots.getFirst();
      if (Boolean.TRUE.equals(slot.get("uploaded"))) return slot;
      int acquired = p.db.update("UPDATE site_capture_sources SET upload_owner=?,upload_until=now()+interval '10 minutes',object_key=? WHERE capture_id=? AND source_index=? AND (upload_until IS NULL OR upload_until<now())",
          token, user.tenant()+"/"+project+"/site-captures/"+id+"/source-"+index+"-"+token, id, index);
      if (acquired != 1) throw new ApiException(409, "site_capture_upload_busy", "该素材仍在上传，请等待当前上传结束。");
      p.db.update("INSERT INTO site_capture_objects(capture_id,object_key) VALUES(?,?)", id, user.tenant()+"/"+project+"/site-captures/"+id+"/source-"+index+"-"+token);
      return slot;
    });
    boolean alreadyUploaded = Boolean.TRUE.equals(source.get("uploaded"));
    long expected = ((Number) source.get("byte_size")).longValue();
    String key = user.tenant()+"/"+project+"/site-captures/"+id+"/source-"+index+"-"+token;
    Path staged = null;
    boolean published = false;
    try {
      if (request.getContentLengthLong() >= 0 && request.getContentLengthLong() != expected) throw new ApiException(413, "site_capture_source_size", "上传字节数与声明不一致。");
      if (!Objects.equals(request.getContentType(), source.get("content_type"))) throw new ApiException(415, "site_capture_source_signature", "上传类型与声明不一致。");
      staged = Files.createTempFile("twin-capture-upload-", ".source");
      try (InputStream in = request.getInputStream(); OutputStream out = Files.newOutputStream(staged)) {
        byte[] buffer = new byte[65536]; long count = 0; int n;
        while ((n = in.read(buffer)) != -1) {
          count += n;
          if (count > expected) throw new ApiException(413, "site_capture_source_size", "上传文件超出声明大小。");
          out.write(buffer, 0, n);
        }
        if (count != expected) throw new ApiException(413, "site_capture_source_size", "上传文件不完整，请重试。");
      }
      ObjectNode inspection;
      try { inspection = FileInspection.inspect(staged, ((String) source.get("content_type")).startsWith("image/") ? "image" : "media", (String) source.get("filename")); }
      catch (ApiException e) { throw new ApiException(415, "site_capture_source_signature", "文件签名与声明格式不符，请使用有效的原始照片或视频。"); }
      if (alreadyUploaded) {
        if (!inspection.path("sha256").asText().equals(source.get("sha256"))) throw new ApiException(409, "site_capture_request_conflict", "该素材槽已保存不同内容，请创建新的采集任务。");
        return present(row(user, project, id));
      }
      // A slow request body must not start a PUT after its reservation expired or was canceled.
      // Two minutes cover the storage client's hard 60-second API call timeout.
      int ownsWrite = p.db.update("UPDATE site_capture_sources SET upload_until=now()+interval '2 minutes' WHERE capture_id=? AND source_index=? AND upload_owner=? AND upload_until>now() AND EXISTS(SELECT 1 FROM site_captures WHERE id=? AND status='uploading')", id, index, token, id);
      if (ownsWrite != 1) throw conflict();
      store.client.putObject(b -> b.bucket(store.bucket).key(key).contentType((String) source.get("content_type")), software.amazon.awssdk.core.sync.RequestBody.fromFile(staged));
      published = Boolean.TRUE.equals(p.tx.execute(st -> {
        p.lock(user, project); p.access(user, project, true);
        if (!row(user, project, id).get("status").equals("uploading")) return false;
        int changed = p.db.update("UPDATE site_capture_sources SET uploaded=true,sha256=?,upload_owner=NULL,upload_until=NULL WHERE capture_id=? AND source_index=? AND upload_owner=? AND upload_until>now()",
            inspection.path("sha256").asText(), id, index, token);
        if (changed != 1) return false;
        p.db.update("UPDATE site_captures SET completed=(SELECT count(*) FROM site_capture_sources WHERE capture_id=? AND uploaded),updated_at=now() WHERE id=?", id, id);
        return true;
      }));
      if (!published) throw conflict();
      log.info("site_capture_source_uploaded capture={} project={} index={} bytes={}", id, project, index, expected);
      return present(row(user, project, id));
    } catch (IOException | RuntimeException e) {
      log.warn("site_capture_source_upload_failed capture={} project={} index={}", id, project, index, e);
      throw e;
    } finally {
      if (!published && !alreadyUploaded) {
        // Clear only our reservation. Other requests cannot start before this cleanup finishes.
        try { store.client.deleteObject(b -> b.bucket(store.bucket).key(key)); }
        catch (Exception e) { log.error("site_capture_source_cleanup_failed capture={} index={}", id, index, e); }
        p.db.update("UPDATE site_capture_sources SET upload_owner=NULL,upload_until=NULL WHERE capture_id=? AND source_index=? AND upload_owner=?", id, index, token);
      }
      if (staged != null) Files.deleteIfExists(staged);
    }
  }

  ObjectNode start(Auth.User user, String project, String id, boolean retry) {
    p.access(user, project, true);
    return p.tx.execute(st -> {
      p.lock(user, project); p.access(user, project, true);
      var job = row(user, project, id);
      if (ACTIVE.contains(job.get("status")) || job.get("status").equals("succeeded")) return present(job);
      if (!(retry ? job.get("status").equals("failed") : job.get("status").equals("uploading"))) throw conflict();
      if (sources(id).stream().anyMatch(f -> !Boolean.TRUE.equals(f.get("uploaded")))) throw new ApiException(409, "site_capture_sources_incomplete", "请先上传全部声明的素材。");
      requireReady(); reserve(user.tenant(), false);
      p.db.update("UPDATE site_captures SET status='queued',stage='queued',completed=0,total=0,attempts=0,error_code=NULL,error_message=NULL,updated_at=now() WHERE id=?", id);
      p.auth.audit(user, project, "site_capture.start", Json.obj("captureId", id));
      return present(row(user, project, id));
    });
  }
  ObjectNode cancel(Auth.User user, String project, String id) {
    p.access(user, project, true);
    return p.tx.execute(st -> {
      p.lock(user, project); p.access(user, project, true);
      var job = row(user, project, id);
      if (job.get("status").equals("canceled")) return present(job);
      if (!Set.of("uploading", "queued", "running").contains(job.get("status"))) throw conflict();
      // Cancellation is immediate, but an in-flight S3 write retains its cleanup ownership.
      // The worker releases this lease after cleanup; a crashed worker expires naturally.
      int changed = p.db.update("UPDATE site_captures SET status='canceled',stage='canceled',updated_at=now() WHERE id=? AND status IN ('uploading','queued','running')", id);
      if (changed != 1) throw conflict();
      p.auth.audit(user, project, "site_capture.cancel", Json.obj("captureId", id));
      return present(row(user, project, id));
    });
  }
  void assertDeletable(List<Map<String, Object>> jobs) {
    for (var job : jobs) {
      if (ACTIVE.contains(job.get("status"))) throw new ApiException(409, "site_capture_active", "请先取消或等待写实重建任务结束。");
      Integer workers = p.db.queryForObject("SELECT count(*) FROM site_captures WHERE id=? AND lease_owner IS NOT NULL AND lease_until>now()", Integer.class, job.get("id"));
      if (workers > 0) throw new ApiException(409, "site_capture_active", "任务已取消，后台正在释放资源，请稍后再删除。");
      Integer uploads = p.db.queryForObject("SELECT count(*) FROM site_capture_sources WHERE capture_id=? AND upload_until>now()", Integer.class, job.get("id"));
      if (uploads > 0) throw new ApiException(409, "site_capture_upload_busy", "素材正在上传，请等待后再删除。");
    }
  }
  void deleteObjects(List<Map<String, Object>> jobs) {
    for (var job : jobs) {
      for (var object : p.db.queryForList("SELECT object_key FROM site_capture_objects WHERE capture_id=?", job.get("id"))) store.client.deleteObject(b -> b.bucket(store.bucket).key((String) object.get("object_key")));
    }
  }
  void delete(Auth.User user, String project, String id) {
    p.access(user, project, true);
    p.tx.executeWithoutResult(st -> {
      p.lock(user, project); p.access(user, project, true);
      var jobs = List.of(row(user, project, id)); assertDeletable(jobs); deleteObjects(jobs);
      p.db.update("DELETE FROM site_captures WHERE id=?", id);
      p.auth.audit(user, project, "site_capture.delete", Json.obj("captureId", id));
    });
  }
  void deleteProjectSources(String tenant, String project) {
    var jobs = p.db.queryForList("SELECT * FROM site_captures WHERE tenant_id=? AND project_id=?", tenant, project);
    assertDeletable(jobs); deleteObjects(jobs);
  }
  void content(Auth.User user, String project, String id, HttpServletResponse response) throws IOException {
    var job = row(user, project, id);
    if (!job.get("status").equals("succeeded") || job.get("output_key") == null || job.get("result") == null) throw conflict();
    long expected = Json.parse(job.get("result").toString()).path("byteSize").asLong();
    if (expected <= 0 || expected > OUTPUT_BYTES) throw new ApiException(502, "site_capture_result_invalid", "已保存的模型大小无效，请联系管理员检查任务。");
    var request = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder().bucket(store.bucket).key((String) job.get("output_key")).build();
    try (var source = store.client.getObject(request)) {
      if (!Objects.equals(source.response().contentLength(), expected)) throw new ApiException(502, "site_capture_result_invalid", "模型存储字节数与任务记录不一致，请联系管理员检查任务。");
      response.setContentType("application/octet-stream");
      response.setContentLengthLong(expected);
      response.setHeader("Cache-Control", "private, no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      byte[] buffer = new byte[65536]; long remaining = expected;
      OutputStream out = response.getOutputStream();
      while (remaining > 0) {
        int count = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (count < 0) throw new EOFException("Capture output truncated: " + id);
        out.write(buffer, 0, count); remaining -= count;
      }
      if (source.read() != -1) throw new IOException("Capture output exceeded recorded length: " + id);
    } catch (IOException | RuntimeException error) {
      log.error("site_capture_content_failed capture={} project={}", id, project, error);
      throw error;
    }
  }
  static ApiException conflict() { return new ApiException(409, "site_capture_state_conflict", "任务状态已变化，请刷新后重试。"); }
}

@RestController
class SiteCaptureController {
  final SiteCaptures s;
  SiteCaptureController(SiteCaptures s) { this.s = s; }
  @GetMapping("/api/v1/site-captures/capabilities") Object capabilities(HttpServletRequest r) { s.p.auth.require(r); return s.capabilities(); }
  @GetMapping("/api/v1/projects/{project}/site-captures") Object list(@PathVariable String project, HttpServletRequest r) { return Json.obj("captures", s.list(s.p.auth.require(r), project)); }
  @GetMapping("/api/v1/projects/{project}/site-captures/{id}") Object get(@PathVariable String project, @PathVariable String id, HttpServletRequest r) { return Json.obj("capture", s.present(s.row(s.p.auth.require(r), project, id))); }
  @PostMapping("/api/v1/projects/{project}/site-captures") @ResponseStatus(HttpStatus.CREATED) Object draft(@PathVariable String project, @RequestBody JsonNode input, HttpServletRequest r) { return Json.obj("capture", s.draft(s.p.auth.require(r), project, input)); }
  @PutMapping("/api/v1/projects/{project}/site-captures/{id}/sources/{index}") Object upload(@PathVariable String project, @PathVariable String id, @PathVariable int index, HttpServletRequest r) throws IOException { return Json.obj("capture", s.upload(s.p.auth.require(r), project, id, index, r)); }
  @PostMapping("/api/v1/projects/{project}/site-captures/{id}/start") @ResponseStatus(HttpStatus.ACCEPTED) Object start(@PathVariable String project, @PathVariable String id, HttpServletRequest r) { return Json.obj("capture", s.start(s.p.auth.require(r), project, id, false)); }
  @PostMapping("/api/v1/projects/{project}/site-captures/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED) Object retry(@PathVariable String project, @PathVariable String id, HttpServletRequest r) { return Json.obj("capture", s.start(s.p.auth.require(r), project, id, true)); }
  @PostMapping("/api/v1/projects/{project}/site-captures/{id}/cancel") Object cancel(@PathVariable String project, @PathVariable String id, HttpServletRequest r) { return Json.obj("capture", s.cancel(s.p.auth.require(r), project, id)); }
  @DeleteMapping("/api/v1/projects/{project}/site-captures/{id}") Object delete(@PathVariable String project, @PathVariable String id, HttpServletRequest r) { s.delete(s.p.auth.require(r), project, id); return Json.obj("deletedCaptureId", id); }
  @GetMapping("/api/v1/projects/{project}/site-captures/{id}/content") void content(@PathVariable String project, @PathVariable String id, HttpServletRequest r, HttpServletResponse response) throws IOException { s.content(s.p.auth.require(r), project, id, response); }
}
