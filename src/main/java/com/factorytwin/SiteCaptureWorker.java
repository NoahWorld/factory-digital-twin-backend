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
@ConditionalOnProperty(name="twin.mode", havingValue="capture-worker")
public class SiteCaptureWorker {
  static final Set<String> STAGES = Set.of("validating", "extracting", "matching", "reconstructing", "training", "exporting");
  final SiteCaptures s;
  final Projects p;
  final SiteCaptureProcess process;
  final String nodeId = Json.id();
  final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "site-capture-converter"));
  final ExecutorService healthExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "site-capture-health"));
  volatile Future<?> active, health;
  volatile boolean ready;
  final Logger log = LoggerFactory.getLogger(SiteCaptureWorker.class);
  SiteCaptureWorker(SiteCaptures s, SiteCaptureProcess process) { this.s = s; this.p = s.p; this.process = process; }

  @Scheduled(fixedDelay=30000)
  public synchronized void probe() {
    if (health != null && !health.isDone()) return;
    if (health != null) report(health, "health");
    health = healthExecutor.submit(() -> {
      ready = false;
      ObjectNode check = process.probe();
      p.db.update("INSERT INTO site_capture_workers(id,available,reason,heartbeat_at) VALUES(?,?,?,now()) ON CONFLICT(id) DO UPDATE SET available=EXCLUDED.available,reason=EXCLUDED.reason,heartbeat_at=now()",
          nodeId, check.path("available").asBoolean(), check.path("reason").isNull() ? null : check.path("reason").asText());
      ready = check.path("available").asBoolean();
      p.db.update("DELETE FROM site_capture_workers WHERE heartbeat_at<now()-interval '1 day'");
    });
  }
  @Scheduled(fixedDelay=1000)
  public synchronized void poll() {
    if (active != null && !active.isDone()) return;
    if (active != null) report(active, "task");
    active = executor.submit(this::next);
  }
  void report(Future<?> task, String kind) {
    try { task.get(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); log.error("site_capture_worker_interrupted kind={}", kind, e); }
    catch (ExecutionException | CancellationException e) { log.error("site_capture_worker_task_failed kind={}", kind, e); }
  }
  void next() {
    p.db.update("UPDATE site_captures SET status='failed',stage='failed',error_code='site_capture_worker_interrupted',error_message='工作节点中断三次，请检查服务资源和日志后重试。',lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE status='running' AND lease_until<now() AND attempts>=3");
    if (!ready) return;
    String lease = Json.id();
    var job = p.tx.execute(st -> {
      var candidates = p.db.queryForList("SELECT * FROM site_captures WHERE (status='queued' OR (status='running' AND lease_until<now())) AND attempts<3 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");
      if (candidates.isEmpty()) return null;
      var selected = candidates.getFirst();
      p.db.update("UPDATE site_captures SET status='running',stage='downloading',completed=0,total=0,attempts=attempts+1,lease_owner=?,lease_until=now()+interval '2 minutes',updated_at=now() WHERE id=?", lease, selected.get("id"));
      return selected;
    });
    if (job != null) execute(job, lease);
  }
  void heartbeat(String id, String lease) {
    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Capture worker interrupted.");
    int changed = p.db.update("UPDATE site_captures SET lease_until=now()+interval '2 minutes',updated_at=now() WHERE id=? AND status='running' AND lease_owner=? AND lease_until>now()", id, lease);
    if (changed != 1) throw new IllegalStateException("Capture lease lost or canceled: " + id);
  }
  void progress(String id, String lease, JsonNode event) {
    String stage = Json.text(event, "stage", 1, 30);
    Json.require(STAGES.contains(stage), "Unexpected reconstruction stage.");
    long completed = Json.integer(event, "completed", 0, 10000000), total = Json.integer(event, "total", 0, 10000000);
    Json.require(completed <= total, "Invalid reconstruction progress range.");
    int changed = p.db.update("UPDATE site_captures SET stage=?,completed=?,total=?,lease_until=now()+interval '2 minutes',updated_at=now() WHERE id=? AND status='running' AND lease_owner=? AND lease_until>now()",
        stage, completed, total, id, lease);
    if (changed != 1) throw new IllegalStateException("Capture progress lease lost: " + id);
  }
  void execute(Map<String, Object> job, String lease) {
    String id = (String) job.get("id"), key = job.get("tenant_id")+"/"+job.get("project_id")+"/site-captures/"+id+"/"+lease+".ply";
    Path temp = null; boolean uploaded = false, committed = false;
    try {
      log.info("site_capture_started capture={} project={} attempt={}", id, job.get("project_id"), ((Number) job.get("attempts")).intValue()+1);
      temp = Files.createTempDirectory("twin-capture-");
      Path inputs = Files.createDirectory(temp.resolve("sources")), output = Files.createDirectory(temp.resolve("output"));
      var manifestSources = Json.M.createArrayNode();
      for (var source : s.sources(id)) {
        heartbeat(id, lease);
        String name = "source-"+source.get("source_index")+"."+FileInspection.extension((String) source.get("filename"));
        Path path = inputs.resolve(name);
        s.store.client.getObject(b -> b.bucket(s.store.bucket).key((String) source.get("object_key")), path);
        if (Files.size(path) != ((Number) source.get("byte_size")).longValue()) throw new IllegalStateException("Capture source byte count changed.");
        ObjectNode checked = FileInspection.inspect(path, job.get("input_kind").equals("images") ? "image" : "media", (String) source.get("filename"));
        if (!checked.path("sha256").asText().equals(source.get("sha256"))) throw new IllegalStateException("Capture source digest changed.");
        manifestSources.add(Json.obj("path", "sources/"+name, "filename", source.get("filename")));
      }
      Path manifest = temp.resolve("manifest.json");
      Files.writeString(manifest, Json.obj("version", 1, "inputKind", job.get("input_kind"), "sources", manifestSources, "options", Json.obj()).toString());
      JsonNode result = process.execute(id, manifest, output, event -> progress(id, lease, event), () -> heartbeat(id, lease));
      ObjectNode checked = SiteCaptureResult.validate(output, result);
      heartbeat(id, lease);
      // Record the attempt object before publishing; project cleanup knows every interrupted attempt key.
      p.db.update("INSERT INTO site_capture_objects(capture_id,object_key) SELECT id,? FROM site_captures WHERE id=? AND status='running' AND lease_owner=? AND lease_until>now()", key, id, lease);
      heartbeat(id, lease);
      uploaded = true;
      s.store.client.putObject(b -> b.bucket(s.store.bucket).key(key).contentType("application/octet-stream"), RequestBody.fromFile(output.resolve("scene.ply")));
      heartbeat(id, lease);
      int changed = p.db.update("UPDATE site_captures SET status='succeeded',stage='succeeded',completed=1,total=1,result=?::jsonb,output_key=?,lease_owner=NULL,lease_until=NULL,error_code=NULL,error_message=NULL,updated_at=now() WHERE id=? AND status='running' AND lease_owner=? AND lease_until>now()",
          checked.toString(), key, id, lease);
      if (changed != 1) throw new IllegalStateException("Capture result lease lost: " + id);
      committed = true;
      log.info("site_capture_succeeded capture={} bytes={} gaussians={}", id, checked.path("byteSize"), checked.path("gaussianCount"));
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.error("site_capture_failed capture={} project={}", id, job.get("project_id"), e);
      ObjectNode failure = failure(e, id);
      p.db.update("UPDATE site_captures SET status='failed',stage='failed',error_code=?,error_message=?,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE id=? AND status='running' AND lease_owner=? AND lease_until>now()",
          failure.path("code").asText(), failure.path("message").asText(), id, lease);
    } finally {
      if (uploaded && !committed) {
        try { s.store.client.deleteObject(b -> b.bucket(s.store.bucket).key(key)); }
        catch (Exception e) { log.error("site_capture_output_cleanup_failed capture={} key={}", id, key, e); }
      }
      // Keep canceled-job ownership until bounded storage I/O and cleanup are finished.
      // DELETE must never erase tracked keys while this executor can still finish a PUT.
      releaseCanceledLease(id, lease);
      if (temp != null) {
        try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        catch (Exception e) { log.error("site_capture_temp_cleanup_failed capture={} path={}", id, temp, e); }
      }
    }
  }
  void releaseCanceledLease(String id, String lease) {
    p.db.update("UPDATE site_captures SET lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE id=? AND status='canceled' AND lease_owner=?", id, lease);
  }
  static ObjectNode failure(Exception error, String id) {
    if (error instanceof ApiException api && (SiteCaptureProcess.ERRORS.containsKey(api.code) || Set.of("site_capture_timeout", "site_capture_output_too_large", "site_capture_result_invalid").contains(api.code))) return Json.obj("code", api.code, "message", api.getMessage());
    return Json.obj("code", "site_capture_worker_failed", "message", "后台重建失败，请使用任务 ID 检查日志："+id);
  }
  @PreDestroy void stop() {
    ready = false; executor.shutdownNow(); healthExecutor.shutdownNow();
    p.db.update("UPDATE site_capture_workers SET available=false,heartbeat_at=now(),reason='工作节点已停止' WHERE id=?", nodeId);
  }
}
