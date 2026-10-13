package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;

@Component
@ConditionalOnProperty(name = "twin.mode", havingValue = "worker")
public class EngineeringWorker {
  final EngineeringImports s;
  final Projects p;
  final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "engineering-converter"));
  volatile Future<?> active;
  final Logger log = LoggerFactory.getLogger(EngineeringWorker.class);

  EngineeringWorker(EngineeringImports s) { this.s = s; this.p = s.p; }

  @Scheduled(fixedDelay = 1000)
  public synchronized void poll() {
    if (active != null && !active.isDone()) return;
    if (active != null) {
      try { active.get(); }
      catch (Exception e) { log.error("engineering_worker_task_failed", e); }
    }
    active = executor.submit(this::next);
  }

  void next() {
    p.db.update("UPDATE engineering_imports SET status='failed',stage='failed',message='上传中断，请重新上传',error=?::jsonb,updated_at=now() WHERE stage='uploading' AND updated_at<now()-interval '10 minutes'",
        Json.obj("code", "engineering_upload_interrupted", "message", "源文件上传中断，请重新上传。").toString());
    p.db.update("UPDATE engineering_imports SET status='failed',stage='failed',message='后台任务中断次数过多',error=?::jsonb,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE status IN ('inspecting','converting') AND lease_until<now() AND attempts>=3",
        Json.obj("code", "engineering_worker_interrupted", "message", "后台任务中断三次，请检查转换服务资源和日志后重试。").toString());
    if (!s.engine.available()) return;
    String lease = Json.id();
    var job = p.tx.execute(st -> {
      var rows = p.db.queryForList("SELECT * FROM engineering_imports WHERE ((status='queued' AND stage<>'uploading') OR (status IN ('inspecting','converting') AND lease_until<now())) AND attempts<3 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");
      if (rows.isEmpty()) return null;
      var row = rows.getFirst();
      boolean inspect = row.get("mode").equals("inspect");
      p.db.update("UPDATE engineering_imports SET status=?,stage=?,message=?,progress=0,attempts=attempts+1,lease_owner=?,lease_until=now()+interval '2 minutes',updated_at=now() WHERE id=?",
          inspect ? "inspecting" : "converting", "downloading", "正在读取已保存的工程文件", lease, row.get("id"));
      return row;
    });
    if (job != null) execute(job, lease);
  }

  void heartbeat(String id, String lease) {
    int changed = p.db.update("UPDATE engineering_imports SET lease_until=now()+interval '2 minutes',updated_at=now() WHERE id=? AND lease_owner=? AND lease_until>now() AND status IN ('inspecting','converting')", id, lease);
    if (changed != 1) throw new IllegalStateException("Engineering lease lost for " + id);
  }

  void progress(String id, String lease, JsonNode event) {
    String stage = Json.text(event, "stage", 1, 80), message = Json.text(event, "message", 1, 1000);
    int value = (int) Json.integer(event, "progress", 0, 100);
    int changed = p.db.update("UPDATE engineering_imports SET stage=?,message=?,progress=?,lease_until=now()+interval '2 minutes',updated_at=now() WHERE id=? AND lease_owner=? AND lease_until>now() AND status IN ('inspecting','converting')",
        stage, message, Math.min(95, value), id, lease);
    if (changed != 1) throw new IllegalStateException("Engineering lease lost for " + id);
  }

  void execute(Map<String, Object> job, String lease) {
    String id = (String) job.get("id"), mode = (String) job.get("mode");
    Path temp = null;
    String outputKey = null;
    boolean committed = false;
    try {
      log.info("engineering_started import={} project={} mode={} attempt={}", id, job.get("project_id"), mode, ((Number) job.get("attempts")).intValue() + 1);
      temp = Files.createTempDirectory("twin-engineering-");
      Path source = temp.resolve("source." + job.get("format")), output = Files.createDirectory(temp.resolve("output")), options = temp.resolve("options.json");
      s.store.client.getObject(b -> b.bucket(s.store.bucket).key((String) job.get("source_key")), source);
      Json.require(Files.size(source) == ((Number) job.get("source_bytes")).longValue(), "Stored engineering source size changed.");
      heartbeat(id, lease);
      // PostgreSQL JSONB adds formatting; keep the same compact UTF-8 budget validated at submission.
      Files.writeString(options, job.get("options") == null ? "{}" : Json.parse(job.get("options").toString()).toString());
      JsonNode result = s.engine.execute(id, mode, source, output, options,
          event -> progress(id, lease, event), () -> heartbeat(id, lease));
      heartbeat(id, lease);
      if (mode.equals("inspect")) {
        validateInspection(result, (String) job.get("format"));
        int n = p.db.update("UPDATE engineering_imports SET status='needs_configuration',stage='needs_configuration',message='检查完成，请确认单位、图层和建模参数',progress=100,inspection=?::jsonb,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE id=? AND lease_owner=? AND lease_until>now()",
            result.toString(), id, lease);
        if (n != 1) throw new IllegalStateException("Engineering inspection lease lost for " + id);
      } else {
        Path model = output.resolve("model.glb");
        if (!Files.isRegularFile(model)) throw new ApiException(422, "engineering_model_missing", "转换未生成 GLB 文件。");
        if (Files.size(model) > FileInspection.limit("model", "model.glb"))
          throw new ApiException(413, "engineering_output_too_large", "生成的 GLB 超过 25 MiB，请按楼层、区域拆分源文件或精简几何后重试。");
        ObjectNode checked = FileInspection.inspect(model, "model", "model.glb");
        String resource = Json.id();
        outputKey = job.get("tenant_id") + "/" + job.get("project_id") + "/" + resource;
        String key = outputKey;
        s.store.client.putObject(b -> b.bucket(s.store.bucket).key(key).contentType("model/gltf-binary"), RequestBody.fromFile(model));
        heartbeat(id, lease);
        p.tx.executeWithoutResult(st -> {
          int n = p.db.update("UPDATE engineering_imports SET status='completed',stage='completed',message='GLB 已生成并加入模型资源库',progress=100,result=?::jsonb,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE id=? AND lease_owner=? AND lease_until>now()",
              result.toString(), id, lease);
          if (n != 1) throw new IllegalStateException("Engineering conversion lease lost for " + id);
          p.db.update("INSERT INTO resources(id,tenant_id,project_id,kind,object_key,filename,content_type,byte_size,state,sha256,inspection) VALUES(?,?,?,'model',?,?,'model/gltf-binary',?,'ready',?,?::jsonb)",
              resource, job.get("tenant_id"), job.get("project_id"), key, outputFilename((String) job.get("name")), checked.path("byteSize").asLong(), checked.path("sha256").asText(), checked.path("inspection").toString());
          p.db.update("UPDATE engineering_imports SET resource_id=? WHERE id=?", resource, id);
        });
        committed = true;
      }
      log.info("engineering_succeeded import={} mode={} objects={}", id, mode, result.path("objectCount"));
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.error("engineering_failed import={} project={} mode={}", id, job.get("project_id"), mode, e);
      ObjectNode error = error(e, id);
      p.db.update("UPDATE engineering_imports SET status='failed',stage='failed',message=?,error=?::jsonb,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE id=? AND lease_owner=?",
          error.path("message").asText(), error.toString(), id, lease);
    } finally {
      if (outputKey != null && !committed) {
        try { String key = outputKey; s.store.client.deleteObject(b -> b.bucket(s.store.bucket).key(key)); }
        catch (Exception e) { log.error("engineering_output_cleanup_failed import={} key={}", id, outputKey, e); }
      }
      if (temp != null) {
        try (var paths = Files.walk(temp)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (Exception e) { log.error("engineering_temp_cleanup_failed import={} path={}", id, temp, e); }
      }
    }
  }

  static String outputFilename(String name) { return name.replaceAll("[/\\\\\\p{Cntrl}]", "_") + ".glb"; }

  static void validateInspection(JsonNode result, String format) {
    Json.require(result.path("schemaVersion").asInt() == 1 && result.path("format").asText().equals(format)
        && result.path("layers").isArray() && result.path("layers").size() <= 2000
        && result.path("warnings").isArray() && result.path("objectCount").isIntegralNumber(), "Invalid engineering inspection result.");
  }

  static ObjectNode error(Exception e, String id) {
    if (e instanceof EngineeringProcess.ConversionFailure failure)
      return Json.obj("code", failure.code, "message", failure.getMessage(), "details", failure.details);
    if (e instanceof ApiException failure) return Json.obj("code", failure.code, "message", failure.getMessage());
    return Json.obj("code", "engineering_worker_failed", "message", "后台转换失败，请使用任务 ID 检查服务器日志：" + id);
  }

  @PreDestroy
  void stop() { executor.shutdownNow(); }
}
