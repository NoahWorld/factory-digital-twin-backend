package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
public class Publications {
  private static final int MAX_SCOPED_PROJECTS = 32;
  final Projects projects;
  final Documents documents;
  final Resources resources;
  final DataConfiguration data;
  final RuntimeState runtime;
  final TwinDriveDocuments twinDrive;

  Publications(Projects projects, Documents documents, Resources resources,
      DataConfiguration data, RuntimeState runtime, TwinDriveDocuments twinDrive) {
    this.projects = projects;
    this.documents = documents;
    this.resources = resources;
    this.data = data;
    this.runtime = runtime;
    this.twinDrive = twinDrive;
  }

  record Publication(String id, String tenant, String project) {}

  String token(Publication publication) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(projects.auth.bootstrapToken.getBytes(StandardCharsets.UTF_8),
          "HmacSHA256"));
      byte[] signature = mac.doFinal(("project-publication/v1:" + publication.tenant + ":"
          + publication.project + ":" + publication.id).getBytes(StandardCharsets.UTF_8));
      return publication.id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot sign project publication", e);
    }
  }

  Publication active(String share) {
    if (share == null || !share.matches("[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}"))
      throw unavailable();
    String id = share.substring(0, 36);
    var rows = projects.db.queryForList("SELECT pub.id,pub.tenant_id,pub.project_id FROM"
        + " project_publications pub JOIN projects p ON p.tenant_id=pub.tenant_id"
        + " AND p.id=pub.project_id WHERE pub.id=? AND p.status='published'", id);
    if (rows.isEmpty()) throw unavailable();
    var row = rows.getFirst();
    Publication publication = new Publication((String) row.get("id"),
        (String) row.get("tenant_id"), (String) row.get("project_id"));
    if (!MessageDigest.isEqual(token(publication).getBytes(StandardCharsets.US_ASCII),
        share.getBytes(StandardCharsets.US_ASCII))) throw unavailable();
    return publication;
  }

  private static ApiException unavailable() {
    return new ApiException(404, "publication_not_found", "Publication is unavailable or was revoked.");
  }

  Auth.User reader(String share, String project) {
    Publication publication = active(share);
    Integer scoped = projects.db.queryForObject("SELECT count(*) FROM project_publication_scopes"
        + " WHERE tenant_id=? AND publication_id=? AND project_id=?", Integer.class,
        publication.tenant, publication.id, project);
    if (scoped == null || scoped != 1) throw unavailable();
    return Auth.User.publicationReader(publication.tenant, project);
  }

  ObjectNode info(Auth.User user, String project) {
    projects.access(user, project, true);
    var rows = projects.db.queryForList("SELECT id,tenant_id,project_id,created_at,document_revision"
        + " FROM project_publications WHERE tenant_id=? AND project_id=?", user.tenant(), project);
    if (rows.isEmpty()) return Json.obj("published", false, "project", projects.access(user, project, false));
    var row = rows.getFirst();
    Publication publication = new Publication((String) row.get("id"),
        (String) row.get("tenant_id"), (String) row.get("project_id"));
    return Json.obj("published", true, "shareToken", token(publication),
        "publishedAt", Json.timestamp(row.get("created_at")),
        "publishedRevision", row.get("document_revision"),
        "scopeCount", projects.db.queryForObject("SELECT count(*) FROM project_publication_scopes"
            + " WHERE tenant_id=? AND publication_id=?", Integer.class, user.tenant(), publication.id),
        "project", projects.access(user, project, false));
  }

  ObjectNode publish(Auth.User user, String project) {
    return projects.tx.execute(status -> {
      projects.access(user, project, true);
      projects.lock(user, project);
      ObjectNode root = projects.access(user, project, true);
      if (root.path("status").asText().equals("archived"))
        throw new ApiException(409, "project_archived", "Archived projects cannot be published.");
      Map<String, Long> scope = resolveScope(user, project);
      projects.db.update("DELETE FROM project_publications WHERE tenant_id=? AND project_id=?",
          user.tenant(), project);
      String id = Json.id();
      projects.db.update("INSERT INTO project_publications"
          + "(id,tenant_id,project_id,created_by,document_revision) VALUES(?,?,?,?,?)",
          id, user.tenant(), project, user.id(), scope.get(project));
      for (var entry : scope.entrySet())
        projects.db.update("INSERT INTO project_publication_scopes"
            + "(tenant_id,publication_id,project_id,document_revision) VALUES(?,?,?,?)",
            user.tenant(), id, entry.getKey(), entry.getValue());
      projects.db.update("UPDATE projects SET status='published',updated_at=now()"
          + " WHERE tenant_id=? AND id=?", user.tenant(), project);
      projects.auth.audit(user, project, "project.publish",
          Json.obj("publicationId", id, "revision", scope.get(project), "scope", scope.keySet()));
      return info(user, project);
    });
  }

  ObjectNode revoke(Auth.User user, String project) {
    return projects.tx.execute(status -> {
      projects.access(user, project, true);
      projects.lock(user, project);
      var affected = projects.db.queryForList("SELECT pub.id,pub.project_id"
          + " FROM project_publication_scopes scope JOIN project_publications pub"
          + " ON pub.tenant_id=scope.tenant_id AND pub.id=scope.publication_id"
          + " WHERE scope.tenant_id=? AND scope.project_id=? FOR UPDATE OF pub",
          user.tenant(), project);
      boolean ownPublication = affected.stream().anyMatch(row -> project.equals(row.get("project_id")));
      if (!ownPublication) throw unavailable();
      for (var row : affected) {
        String publicationId = (String) row.get("id"), root = (String) row.get("project_id");
        projects.db.update("DELETE FROM project_publications WHERE tenant_id=? AND id=?",
            user.tenant(), publicationId);
        projects.db.update("UPDATE projects SET status='draft',updated_at=now()"
            + " WHERE tenant_id=? AND id=?", user.tenant(), root);
        projects.auth.audit(user, root, "project.unpublish",
            Json.obj("publicationId", publicationId, "revokedByProjectId", project));
      }
      return Json.obj("published", false, "revokedCount", affected.size(),
          "project", projects.access(user, project, false));
    });
  }

  private Map<String, Long> resolveScope(Auth.User user, String root) {
    Map<String, Long> result = new LinkedHashMap<>();
    Deque<String> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      String id = pending.removeFirst();
      if (result.containsKey(id)) continue;
      if (result.size() == MAX_SCOPED_PROJECTS)
        throw new ApiException(409, "publication_scope_too_large",
            "A publication can contain at most 32 linked projects.");
      ObjectNode project = projects.access(user, id, true);
      result.put(id, project.path("documentRevision").asLong());
      var document = projects.db.queryForMap("SELECT linked_project_id FROM documents"
          + " WHERE tenant_id=? AND project_id=?", user.tenant(), id);
      Object linked = document.get("linked_project_id");
      if (linked != null) pending.addLast((String) linked);
      var items = documentItems(user, id);
      for (JsonNode item : items) {
        if (item.path("type").asText().equals("scene-3d")) {
          String referenced = item.path("props").path("sceneProjectId").asText("");
          if (!referenced.isBlank()) pending.addLast(referenced);
        }
      }
      for (String resourceId : referencedResourceIds(items)) {
        if (resources.contracts.builtin(resourceId) != null
            || resources.contracts.builtinResource("image", resourceId) != null) continue;
        Map<String, Object> resource = resources.row(user, id, resourceId);
        if (!"ready".equals(resource.get("state")))
          throw new ApiException(409, "publication_resource_not_ready",
              "Resource " + resourceId + " in project " + id + " is not ready.");
      }
    }
    return result;
  }

  private List<JsonNode> documentItems(Auth.User reader, String project) {
    return projects.db.query("SELECT body::text FROM document_items"
        + " WHERE tenant_id=? AND project_id=?", (row, index) -> Json.parse(row.getString(1)),
        reader.tenant(), project);
  }

  private static Set<String> referencedResourceIds(List<JsonNode> items) {
    Set<String> ids = new HashSet<>();
    for (JsonNode item : items) {
      if (item.path("resourceRefs").isArray())
        for (JsonNode reference : item.path("resourceRefs")) ids.add(reference.asText());
      if (item.hasNonNull("modelAssetId")) ids.add(item.path("modelAssetId").asText());
    }
    return ids;
  }

  Set<String> publicResourceIds(Auth.User reader, String project) {
    projects.access(reader, project, false);
    return referencedResourceIds(documentItems(reader, project));
  }

  ObjectNode publicProject(String share, String project) {
    ObjectNode value = projects.access(reader(share, project), project, false);
    if (!value.path("coverUrl").isNull())
      value.put("coverUrl", "/api/v1/publications/projects/" + project
          + "/cover.png?share=" + share + "&revision=" + value.path("coverRevision").asLong());
    return value;
  }

  ObjectNode publicProjects(String share) {
    Publication publication = active(share);
    var ids = projects.db.query("SELECT project_id FROM project_publication_scopes"
        + " WHERE tenant_id=? AND publication_id=? ORDER BY project_id",
        (row, index) -> row.getString(1), publication.tenant, publication.id);
    return Json.obj("projects", ids.stream().map(id -> publicProject(share, id)).toList(),
        "nextOffset", null);
  }
}

@RestController
class PublicationController {
  final Publications publication;

  PublicationController(Publications publication) {
    this.publication = publication;
  }

  @GetMapping("/api/v1/projects/{id}/publication")
  Object info(@PathVariable String id, HttpServletRequest request) {
    return publication.info(publication.projects.auth.require(request), id);
  }

  @PostMapping("/api/v1/projects/{id}/publication")
  Object publish(@PathVariable String id, HttpServletRequest request) {
    return publication.publish(publication.projects.auth.require(request), id);
  }

  @DeleteMapping("/api/v1/projects/{id}/publication")
  Object revoke(@PathVariable String id, HttpServletRequest request) {
    return publication.revoke(publication.projects.auth.require(request), id);
  }

  @GetMapping("/api/v1/publications")
  Object publicInfo(@RequestParam String share) {
    var active = publication.active(share);
    return Json.obj("project", publication.publicProject(share, active.project()));
  }

  @GetMapping("/api/v1/publications/projects")
  Object projects(@RequestParam String share) {
    return publication.publicProjects(share);
  }

  @GetMapping("/api/v1/publications/projects/{id}/{kind:canvas|scene}")
  Object document(@PathVariable String id, @PathVariable String kind, @RequestParam String share) {
    return publication.documents.read(publication.reader(share, id), id, kind);
  }

  @GetMapping("/api/v1/publications/projects/{id}/assets")
  Object assets(@PathVariable String id, @RequestParam String share) {
    Auth.User reader = publication.reader(share, id);
    var rows = publication.projects.db.queryForList("SELECT * FROM assets"
        + " WHERE tenant_id=? AND project_id=? ORDER BY created_at,id LIMIT 10001",
        reader.tenant(), id);
    if (rows.size() > 10000)
      throw new ApiException(413, "pagination_required", "Project has more than 10000 assets.");
    return Json.obj("assets", rows.stream().map(publication.data::present).toList(),
        "nextOffset", null);
  }

  @GetMapping("/api/v1/publications/projects/{id}/assets/{asset}/runtime-state")
  Object runtime(@PathVariable String id, @PathVariable String asset, @RequestParam String share) {
    return Json.obj("runtimeState", publication.runtime.asset(publication.reader(share, id), id, asset));
  }

  @GetMapping("/api/v1/publications/projects/{id}/{kind:model|image|media}-assets")
  Object resources(@PathVariable String id, @PathVariable String kind, @RequestParam String share) {
    Auth.User reader = publication.reader(share, id);
    Set<String> allowed = publication.publicResourceIds(reader, id);
    return Json.obj(kind + "Assets", publication.resources.list(reader, id, kind).stream()
        .filter(resource -> allowed.contains(resource.path("id").asText())).toList());
  }

  @GetMapping("/api/v1/publications/projects/{project}/{kind:model|image|media}-assets/{id}/content")
  ResponseEntity<?> content(@PathVariable String project, @PathVariable String kind,
      @PathVariable String id, @RequestParam String share) {
    Auth.User reader = publication.reader(share, project);
    if (!publication.publicResourceIds(reader, project).contains(id))
      throw new ApiException(404, "publication_resource_not_found",
          "Resource is not used by this published project.");
    var builtin = publication.resources.contracts.builtinResource(kind, id);
    if (builtin != null)
      return ResponseEntity.status(302).header(HttpHeaders.LOCATION,
          builtin.path("contentPath").asText()).build();
    var row = publication.resources.row(reader, project, id);
    if (!"ready".equals(row.get("state")) || !kind.equals(row.get("kind")))
      throw new ApiException(409, "resource_not_ready", "Resource is not ready for display.");
    return ResponseEntity.status(302).header(HttpHeaders.LOCATION,
        publication.resources.store.download((String) row.get("object_key"))).build();
  }

  @GetMapping(value = "/api/v1/publications/projects/{id}/cover.png",
      produces = MediaType.IMAGE_PNG_VALUE)
  ResponseEntity<byte[]> cover(@PathVariable String id, @RequestParam String share) {
    Auth.User reader = publication.reader(share, id);
    var cover = publication.projects.covers.read(reader.tenant(), id);
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.noStore()).body(cover.png());
  }

  @GetMapping("/api/v1/publications/projects/{id}/twin-drive")
  Object twinDrive(@PathVariable String id, @RequestParam String share) {
    return publication.twinDrive.read(publication.reader(share, id), id);
  }
}
