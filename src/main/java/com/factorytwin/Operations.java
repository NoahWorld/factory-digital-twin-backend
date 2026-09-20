package com.factorytwin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

@RestController
public class Operations implements ApplicationRunner {
  final Projects p;
  final Resources resources;
  final RuntimeState runtime;
  final String mode, origins;
  final Logger log = LoggerFactory.getLogger(Operations.class);

  public Operations(
      Projects p,
      Resources resources,
      RuntimeState runtime,
      @Value("${twin.mode}") String mode,
      @Value("${twin.allowed-origins}") String origins) {
    this.p = p;
    this.resources = resources;
    this.runtime = runtime;
    this.mode = mode;
    this.origins = origins;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (mode.equals("api")) {
      resources.store.health();
      resources.store.cors(List.of(origins.split(",")));
    }
    log.info("backend_ready mode={} version=0.1.0", mode);
  }

  @GetMapping("/health")
  ResponseEntity<?> health() {
    Map<String, String> checks = new LinkedHashMap<>();
    boolean ok = true;
    try {
      p.db.queryForObject("SELECT 1", Integer.class);
      checks.put("database", "up");
    } catch (Exception e) {
      ok = false;
      checks.put("database", "down");
      log.error("health_database_failed", e);
    }
    try {
      runtime.redis.execute(
          (org.springframework.data.redis.core.RedisCallback<String>)
              connection -> connection.ping());
      checks.put("state", "up");
    } catch (Exception e) {
      ok = false;
      checks.put("state", "down");
      log.error("health_state_failed", e);
    }
    try {
      resources.store.health();
      checks.put("objectStorage", "up");
    } catch (Exception e) {
      ok = false;
      checks.put("objectStorage", "down");
      log.error("health_storage_failed", e);
    }
    return ResponseEntity.status(ok ? 200 : 503)
        .body(
            Json.obj(
                "status",
                ok ? "ok" : "degraded",
                "mode",
                mode,
                "version",
                "0.1.0",
                "checks",
                checks));
  }

  @GetMapping("/api/v1/capabilities")
  Object capabilities(HttpServletRequest r) {
    p.auth.require(r);
    return Json.obj(
        "backend",
        "java",
        "schemaVersion",
        1,
        "cookieSessions",
        true,
        "multipartUploads",
        true,
        "runtimeWebSocket",
        true,
        "runtimeWebSocketPath",
        "/api/v1/realtime",
        "upstreamWebSocket",
        false,
        "sceneBackgroundGeneration",
        false,
        "publication",
        false,
        "documentChunks",
        true);
  }

  @GetMapping("/api/v1/audit-events")
  Object audit(@RequestParam(defaultValue = "0") long before, HttpServletRequest r) {
    var u = p.auth.admin(r);
    return Json.obj(
        "events",
        p.db.queryForList(
            "SELECT id,user_id AS \"userId\",project_id AS \"projectId\",action,request_id AS"
                + " \"requestId\",details::text,created_at AS \"createdAt\" FROM audit_events WHERE"
                + " tenant_id=? AND (?=0 OR id<?) ORDER BY id DESC LIMIT 100",
            u.tenant(),
            before,
            before));
  }

  @PostMapping("/api/v1/projects/{id}/scene-backgrounds")
  Object background(@PathVariable String id, HttpServletRequest r) {
    p.access(p.auth.require(r), id, true);
    throw new ApiException(
        501,
        "scene_background_not_implemented",
        "Image-to-GLB background generation has not yet migrated to the Java worker.");
  }

  @DeleteMapping("/api/v1/projects/{id}")
  Object delete(@PathVariable String id, HttpServletRequest r) {
    var u = p.auth.require(r);
    return p.tx.execute(
        st -> {
          p.lock(u, id);
          p.owner(u, id);
          int linked =
              p.db.queryForObject(
                  "SELECT count(*) FROM documents WHERE tenant_id=? AND linked_project_id=?",
                  Integer.class,
                  u.tenant(),
                  id);
          int embedded =
              p.db.queryForObject(
                  "SELECT count(*) FROM document_items WHERE tenant_id=? AND project_id<>? AND"
                      + " body->'props'->>'sceneProjectId'=?",
                  Integer.class,
                  u.tenant(),
                  id,
                  id);
          if (linked + embedded > 0)
            throw new ApiException(
                409, "project_in_use", "Unlink referencing projects before deleting this project.");
          var objects =
              p.db.queryForList(
                  "SELECT * FROM resources WHERE tenant_id=? AND project_id=?", u.tenant(), id);
          if (objects.stream().anyMatch(x -> x.get("state").equals("processing")))
            throw new ApiException(
                409,
                "resource_processing",
                "Wait for resource processing before deleting this project.");
          for (var object : objects) resources.deleteObject(object);
          p.db.update("DELETE FROM resources WHERE tenant_id=? AND project_id=?", u.tenant(), id);
          p.db.update("DELETE FROM projects WHERE tenant_id=? AND id=?", u.tenant(), id);
          p.auth.audit(u, id, "project.delete", Json.obj("resources", objects.size()));
          return Json.obj(
              "deletedProjectId",
              id,
              "deletedImageObjectCount",
              objects.stream().filter(x -> x.get("kind").equals("image")).count(),
              "deletedMediaObjectCount",
              objects.stream().filter(x -> x.get("kind").equals("media")).count(),
              "deletedModelObjectCount",
              objects.stream().filter(x -> x.get("kind").equals("model")).count(),
              "warning",
              null);
        });
  }

  @Scheduled(fixedDelay = 3600000)
  public void prune() {
    if (mode.equals("worker")) {
      p.db.update("DELETE FROM sessions WHERE expires_at<now()");
      p.db.update("DELETE FROM login_attempts WHERE resets_at<now()");
    }
  }
}
