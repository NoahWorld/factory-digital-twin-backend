package com.factorytwin;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
public class ProjectCovers {
  private static final Logger log = LoggerFactory.getLogger(ProjectCovers.class);
  final JdbcTemplate db;

  public ProjectCovers(JdbcTemplate db) {
    this.db = db;
  }

  record Cover(long revision, long sourceRevision, byte[] png) {}

  /** Called inside the successful document-save transaction; never draws a substitute image. */
  @Transactional
  public void refresh(String tenantId, String projectId) {
    // Lock cover rows in stable order without taking dependent project/document locks. A concurrent
    // upload either precedes this invalidation or observes the incremented revision and conflicts.
    var affected = db.queryForList(
        "SELECT pc.project_id FROM project_covers pc WHERE pc.tenant_id=? AND (pc.project_id=?"
            + " OR (EXISTS (SELECT 1 FROM projects source WHERE source.tenant_id=pc.tenant_id"
            + " AND source.id=? AND source.project_type='3d') AND pc.project_id IN ("
            + " SELECT di.project_id FROM document_items di JOIN projects p"
            + " ON p.tenant_id=di.tenant_id AND p.id=di.project_id WHERE di.tenant_id=?"
            + " AND p.project_type='2d' AND di.body->>'type'='scene-3d'"
            + " AND di.body->'props'->>'sceneProjectId'=?))) ORDER BY pc.project_id FOR UPDATE OF pc",
        String.class, tenantId, projectId, projectId, tenantId, projectId);
    if (!affected.contains(projectId)) {
      throw new IllegalStateException("Project cover row is missing for " + projectId);
    }
    for (String affectedId : affected) {
      db.update("UPDATE project_covers SET status='pending',revision=revision+1,updated_at=now()"
          + " WHERE tenant_id=? AND project_id=?", tenantId, affectedId);
      log.info("Project cover pending projectId={} sourceProjectId={} reason={}", affectedId,
          projectId, affectedId.equals(projectId) ? "document_saved" : "referenced_scene_saved");
    }
  }

  public void lockRevision(String tenantId, String projectId, long expectedCoverRevision) {
    Long current = db.queryForObject(
        "SELECT revision FROM project_covers WHERE tenant_id=? AND project_id=? FOR UPDATE",
        Long.class, tenantId, projectId);
    if (current == null) throw new IllegalStateException("Project cover row is missing for " + projectId);
    if (current != expectedCoverRevision) {
      throw new ApiException(409, "cover_revision_conflict", "Screenshot cover revision "
          + expectedCoverRevision + " is not current cover revision " + current
          + ". A document or referenced scene changed; capture again.");
    }
  }

  /** Caller holds the project and document row locks, in that order. */
  public long store(String tenantId, String projectId, long sourceRevision, byte[] png) {
    Long revision = db.queryForObject(
        "UPDATE project_covers SET status='ready',png=?,source_revision=?,revision=revision+1,"
            + " updated_at=now() WHERE tenant_id=? AND project_id=? RETURNING revision",
        Long.class, png, sourceRevision, tenantId, projectId);
    if (revision == null) {
      throw new IllegalStateException("Project cover update returned no revision for " + projectId);
    }
    log.info("Project cover uploaded projectId={} sourceRevision={} coverRevision={} bytes={}",
        projectId, sourceRevision, revision, png.length);
    return revision;
  }

  public Cover read(String tenantId, String projectId) {
    var rows = db.query(
        "SELECT revision,source_revision,png FROM project_covers"
            + " WHERE tenant_id=? AND project_id=? AND png IS NOT NULL",
        (row, index) -> new Cover(row.getLong("revision"), row.getLong("source_revision"), row.getBytes("png")),
        tenantId, projectId);
    if (rows.isEmpty()) {
      throw new ApiException(404, "project_cover_pending", "A project screenshot has not been generated yet.");
    }
    return rows.getFirst();
  }
}

@RestController
@RequestMapping("/api/v1/projects/{id}")
class ProjectCoverController {
  final Projects projects;
  final ProjectCovers covers;

  ProjectCoverController(Projects projects, ProjectCovers covers) {
    this.projects = projects;
    this.covers = covers;
  }

  @PutMapping(value = "/cover", consumes = MediaType.IMAGE_PNG_VALUE)
  Object upload(@PathVariable String id, @RequestParam(required = false) String sourceRevision,
      @RequestParam(required = false) String expectedCoverRevision, HttpServletRequest r)
      throws IOException {
    var user = projects.auth.require(r);
    projects.access(user, id, true);
    long expected = revision(sourceRevision, "sourceRevision");
    long expectedCover = revision(expectedCoverRevision, "expectedCoverRevision");
    // Bound the stream independently of Content-Length and before allocating or decoding PNG data.
    byte[] png = r.getInputStream().readNBytes(ProjectCoverPng.MAX_BYTES + 1);
    ProjectCoverPng.validate(png);
    return projects.tx.execute(st -> {
      projects.lock(user, id);
      projects.access(user, id, true);
      Long current = projects.db.queryForObject(
          "SELECT revision FROM documents WHERE tenant_id=? AND project_id=? FOR UPDATE",
          Long.class, user.tenant(), id);
      if (current == null) throw new IllegalStateException("Project document is missing for " + id);
      if (current != expected) {
        throw new ApiException(409, "revision_conflict",
            "Screenshot source revision " + expected + " is not current document revision " + current + ". Capture the saved document again.");
      }
      covers.lockRevision(user.tenant(), id, expectedCover);
      long revision = covers.store(user.tenant(), id, expected, png);
      projects.auth.audit(user, id, "project.cover.upload",
          Json.obj("sourceRevision", expected, "coverRevision", revision, "bytes", png.length));
      return Json.obj("project", projects.access(user, id, false));
    });
  }

  static long revision(String value, String field) {
    if (value == null || !value.matches("0|[1-9][0-9]{0,15}")) {
      throw new ApiException(400, "invalid_input", field + " must be a non-negative safe integer.");
    }
    long revision = Long.parseLong(value);
    if (revision > 9007199254740991L) {
      throw new ApiException(400, "invalid_input", field + " exceeds the safe integer range.");
    }
    return revision;
  }

  @GetMapping(value = "/cover.png", produces = MediaType.IMAGE_PNG_VALUE)
  ResponseEntity<byte[]> cover(@PathVariable String id, HttpServletRequest r) {
    var user = projects.auth.require(r);
    // Authorization must precede both the image and conditional-cache response.
    projects.access(user, id, false);
    var cover = covers.read(user.tenant(), id);
    String etag = "\"project-cover-" + id + "-" + cover.revision() + "\"";
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.IMAGE_PNG);
    headers.setCacheControl("private, no-cache");
    headers.setETag(etag);
    headers.setVary(java.util.List.of("Cookie"));
    headers.set("X-Content-Type-Options", "nosniff");
    if (matchesEtag(r.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
      return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
    }
    headers.setContentLength(cover.png().length);
    return new ResponseEntity<>(cover.png(), headers, HttpStatus.OK);
  }

  static boolean matchesEtag(String value, String etag) {
    if (value == null) return false;
    for (String candidate : value.split(",")) {
      String tag = candidate.trim();
      if (tag.equals("*") || tag.equals(etag) || tag.equals("W/" + etag)) return true;
    }
    return false;
  }
}
