package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@Service
public class Projects {
  final JdbcTemplate db;
  final Auth auth;
  final TransactionTemplate tx;
  final ProjectCovers covers;
  final Contracts contracts;

  public Projects(JdbcTemplate db, Auth auth, TransactionTemplate tx, ProjectCovers covers, Contracts contracts) {
    this.db = db;
    this.auth = auth;
    this.tx = tx;
    this.covers = covers;
    this.contracts = contracts;
  }

  static final String SELECT =
      "SELECT p.*,pm.role AS member_role,pc.revision AS cover_revision,pc.source_revision AS cover_source_revision,"
          + " pc.status AS cover_status,(pc.png IS NOT NULL) AS cover_has_png,d.revision AS document_revision"
          + " FROM projects p LEFT JOIN project_members pm ON"
          + " pm.tenant_id=p.tenant_id AND pm.project_id=p.id AND pm.user_id=? LEFT JOIN project_covers pc ON"
          + " pc.tenant_id=p.tenant_id AND pc.project_id=p.id"
          + " LEFT JOIN documents d ON d.tenant_id=p.tenant_id AND d.project_id=p.id WHERE p.tenant_id=?"
          + " AND (? OR pm.user_id IS NOT NULL)";

  public ObjectNode present(Map<String, Object> row) {
    Object coverRevision = row.get("cover_revision");
    if (!(coverRevision instanceof Number)) {
      throw new IllegalStateException("Project cover row is missing for " + row.get("id"));
    }
    Object documentRevision = row.get("document_revision");
    if (!(documentRevision instanceof Number)) {
      throw new IllegalStateException("Project document row is missing for " + row.get("id"));
    }
    Object sourceRevision = row.get("cover_source_revision");
    boolean ready = "ready".equals(row.get("cover_status"))
        && Boolean.TRUE.equals(row.get("cover_has_png"))
        && sourceRevision instanceof Number
        && ((Number) sourceRevision).longValue() == ((Number) documentRevision).longValue();
    return Json.obj(
        "id",
        row.get("id"),
        "name",
        row.get("name"),
        "status",
        row.get("status"),
        "projectType",
        row.get("project_type"),
        "projectRole",
        row.get("member_role"),
        "createdAt",
        Json.timestamp(row.get("created_at")),
        "updatedAt",
        Json.timestamp(row.get("updated_at")),
        "coverUrl",
        Boolean.TRUE.equals(row.get("cover_has_png")) ? "/api/v1/projects/"
            + row.get("id")
            + "/cover.png?revision="
            + ((Number) coverRevision).longValue() : null,
        "coverStatus", ready ? "ready" : "pending",
        "documentRevision", ((Number) documentRevision).longValue(),
        "coverSourceRevision", sourceRevision,
        "coverRevision", ((Number) coverRevision).longValue());
  }

  public ObjectNode access(Auth.User u, String id, boolean write) {
    var rows = db.queryForList(SELECT + " AND p.id=?", u.id(), u.tenant(), u.admin(), id);
    if (rows.isEmpty())
      throw new ApiException(404, "project_not_found", "Project not found or inaccessible.");
    var row = rows.getFirst();
    if (write
        && !u.admin()
        && !Set.of("owner", "editor").contains(String.valueOf(row.get("member_role"))))
      throw new ApiException(
          403, "project_write_forbidden", "Project edit permission is required.");
    return present(row);
  }

  public void owner(Auth.User u, String id) {
    ObjectNode p = access(u, id, true);
    if (!u.admin() && !p.path("projectRole").asText().equals("owner"))
      throw new ApiException(
          403, "project_owner_required", "Project owner permission is required.");
  }

  public void lock(Auth.User u, String id) {
    access(u, id, false);
    var rows =
        db.queryForList(
            "SELECT id FROM projects WHERE tenant_id=? AND id=? FOR UPDATE", u.tenant(), id);
    if (rows.isEmpty()) throw new ApiException(404, "project_not_found", "Project not found.");
  }
}

@RestController
@RequestMapping("/api/v1/projects")
class ProjectController {
  final Projects p;

  ProjectController(Projects p) {
    this.p = p;
  }

  @GetMapping
  Object list(
      HttpServletRequest r,
      @RequestParam(required = false) Integer limit,
      @RequestParam(defaultValue = "0") int offset) {
    var u = p.auth.require(r);
    int size = limit == null ? 1000 : limit;
    Json.require(
        size >= 1 && size <= 1000 && offset >= 0, "Invalid pagination; limit must be 1..1000.");
    var rows =
        p.db.queryForList(
            Projects.SELECT + " ORDER BY p.updated_at DESC,p.id LIMIT ? OFFSET ?",
            u.id(),
            u.tenant(),
            u.admin(),
            size + 1,
            offset);
    boolean more = rows.size() > size;
    if (more && limit == null)
      throw new ApiException(
          413,
          "pagination_required",
          "More than 1000 projects; request explicit limit and offset.");
    return Json.obj(
        "projects",
        rows.stream().limit(size).map(p::present).toList(),
        "nextOffset",
        more ? offset + size : null);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Object create(@RequestBody JsonNode b, HttpServletRequest r) {
    var u = p.auth.require(r);
    if (u.role().equals("viewer"))
      throw new ApiException(
          403, "project_create_forbidden", "Project creation permission is required.");
    String name = Json.text(b, "name", 2, 100);
    String type = b.path("projectType").asText("2d");
    Json.require(Set.of("2d", "3d").contains(type), "projectType must be 2d or 3d.");
    return p.tx.execute(
        st -> {
          String id = Json.id();
          p.db.update(
              "INSERT INTO projects(id,tenant_id,name,project_type) VALUES(?,?,?,?)",
              id,
              u.tenant(),
              name,
              type);
          p.db.update(
              "INSERT INTO project_members(tenant_id,project_id,user_id,role)"
                  + " VALUES(?,?,?,'owner')",
              u.tenant(),
              id,
              u.id());
          p.db.update(
              "INSERT INTO documents(tenant_id,project_id,kind,settings) VALUES(?,?,?,?::jsonb)",
              u.tenant(),
              id,
              type.equals("2d") ? "canvas" : "scene",
              (type.equals("2d") ? Documents.theme() : Documents.settings(p.contracts)).toString());
          p.auth.audit(u, id, "project.create", Json.obj("projectType", type));
          return Json.obj("project", p.access(u, id, false));
        });
  }

  @GetMapping("/{id}")
  Object get(@PathVariable String id, HttpServletRequest r) {
    return Json.obj("project", p.access(p.auth.require(r), id, false));
  }

  @PatchMapping("/{id}")
  Object rename(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    var u = p.auth.require(r);
    String name = Json.text(b, "name", 2, 100);
    return p.tx.execute(
        st -> {
          p.lock(u, id);
          p.access(u, id, true);
          p.db.update(
              "UPDATE projects SET name=?,updated_at=now() WHERE id=? AND tenant_id=?",
              name,
              id,
              u.tenant());
          p.auth.audit(u, id, "project.rename", Json.obj("name", name));
          return Json.obj("project", p.access(u, id, false));
        });
  }

  @GetMapping("/{id}/members")
  Object members(@PathVariable String id, HttpServletRequest r) {
    var u = p.auth.require(r);
    p.owner(u, id);
    return Json.obj(
        "members",
        p.db.queryForList(
            "SELECT user_id AS \"userId\",role FROM project_members WHERE tenant_id=? AND"
                + " project_id=?",
            u.tenant(),
            id));
  }

  @PutMapping("/{id}/members/{userId}")
  Object member(
      @PathVariable String id,
      @PathVariable String userId,
      @RequestBody JsonNode b,
      HttpServletRequest r) {
    var u = p.auth.require(r);
    String role = Json.text(b, "role", 1, 10);
    Json.require(Set.of("owner", "editor", "viewer").contains(role), "Invalid project role.");
    return p.tx.execute(
        st -> {
          p.lock(u, id);
          p.owner(u, id);
          Json.require(!u.id().equals(userId), "Use another owner to change your own membership.");
          p.db.update(
              "INSERT INTO project_members(tenant_id,project_id,user_id,role) VALUES(?,?,?,?) ON"
                  + " CONFLICT(project_id,user_id) DO UPDATE SET role=excluded.role",
              u.tenant(),
              id,
              userId,
              role);
          p.auth.audit(u, id, "member.set", Json.obj("userId", userId, "role", role));
          return Json.obj("userId", userId, "role", role);
        });
  }

  @DeleteMapping("/{id}/members/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@PathVariable String id, @PathVariable String userId, HttpServletRequest r) {
    var u = p.auth.require(r);
    p.tx.executeWithoutResult(
        st -> {
          p.lock(u, id);
          p.owner(u, id);
          Json.require(!u.id().equals(userId), "Cannot remove your own membership.");
          p.db.update(
              "DELETE FROM project_members WHERE tenant_id=? AND project_id=? AND user_id=?",
              u.tenant(),
              id,
              userId);
          p.auth.audit(u, id, "member.remove", Json.obj("userId", userId));
        });
  }
}
