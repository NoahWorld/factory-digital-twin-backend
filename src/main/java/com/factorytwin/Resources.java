package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.model.*;

@Service
public class Resources {
  final Projects p;
  final ObjectStorage store;
  final Contracts contracts;

  public Resources(Projects p, ObjectStorage s, Contracts c) {
    this.p = p;
    this.store = s;
    this.contracts = c;
  }

  public Map<String, Object> row(Auth.User u, String project, String id) {
    p.access(u, project, false);
    var rows =
        p.db.queryForList(
            "SELECT * FROM resources WHERE tenant_id=? AND project_id=? AND id=?",
            u.tenant(),
            project,
            id);
    if (rows.isEmpty()) throw new ApiException(404, "resource_not_found", "Resource not found.");
    return rows.getFirst();
  }

  public ObjectNode usage(Auth.User u, String project, String id) {
    var items =
        p.db.query(
            "SELECT id,body::text FROM document_items WHERE tenant_id=? AND project_id=? AND"
                + " (body->'resourceRefs' ?? ? OR body->>'modelAssetId'=?)",
            (rs, i) ->
                Json.obj(
                    "id",
                    rs.getString(1),
                    "type",
                    Json.parse(rs.getString(2)).path("type").asText("standalone-3d-instance")),
            u.tenant(),
            project,
            id,
            id);
    return Json.obj("count", items.size(), "nodes", items.stream().limit(20).toList());
  }

  public ObjectNode present(Auth.User u, Map<String, Object> row) {
    String filename = (String) row.get("filename"),
        ext = FileInspection.extension(filename),
        kind = (String) row.get("kind");
    ObjectNode n =
        Json.obj(
            "id",
            row.get("id"),
            "projectId",
            row.get("project_id"),
            "originalFilename",
            filename,
            "format",
            ext.equals("jpg") ? "jpeg" : ext,
            "contentType",
            row.get("content_type"),
            "byteSize",
            row.get("byte_size"),
            "sha256",
            row.get("sha256"),
            "source",
            "upload",
            "state",
            row.get("state"),
            "error",
            row.get("error"),
            "usage",
            usage(u, (String) row.get("project_id"), (String) row.get("id")),
            "createdAt",
            Json.timestamp(row.get("created_at")));
    if (kind.equals("model")) {
      n.set(
          "inspection",
          row.get("inspection") == null
              ? Json.M.nullNode()
              : Json.parse(row.get("inspection").toString()));
      n.putNull("sourceImageAssetId").putNull("generation");
    }
    if (kind.equals("media"))
      n.put(
          "mediaType", ((String) row.get("content_type")).startsWith("video/") ? "video" : "audio");
    return n;
  }

  public List<ObjectNode> list(Auth.User u, String project, String kind) {
    p.access(u, project, false);
    List<ObjectNode> result = new ArrayList<>();
    if (kind.equals("image"))
      for (JsonNode image : contracts.builtinImages) {
        ObjectNode n = image.deepCopy();
        n.put("projectId", project).put("source", "system").put("state", "ready");
        n.set("usage", usage(u, project, n.path("id").asText()));
        result.add(n);
      }
    if (kind.equals("model"))
      for (JsonNode model : contracts.builtins) {
        ObjectNode n = model.deepCopy();
        n.put("projectId", project).put("source", "system").put("state", "ready");
        n.set("usage", usage(u, project, n.path("id").asText()));
        n.putNull("sourceImageAssetId").putNull("generation");
        result.add(n);
      }
    for (var row :
        p.db.queryForList(
            "SELECT * FROM resources WHERE tenant_id=? AND project_id=? AND kind=? AND"
                + " state='ready' ORDER BY created_at DESC",
            u.tenant(),
            project,
            kind)) result.add(present(u, row));
    return result;
  }

  public ObjectNode upload(
      Auth.User u, String project, String kind, String filename, InputStream in)
      throws IOException {
    p.access(u, project, true);
    long max = FileInspection.limit(kind, filename);
    Path file = Files.createTempFile("twin-upload-", ".bin");
    String id = Json.id(), key = u.tenant() + "/" + project + "/" + id;
    try {
      try (OutputStream out = Files.newOutputStream(file)) {
        byte[] buffer = new byte[65536];
        long size = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
          size += n;
          if (size > max)
            throw new ApiException(
                413, "file_too_large", "File exceeds its type-specific size budget.");
          out.write(buffer, 0, n);
        }
      }
      ObjectNode checked = FileInspection.inspect(file, kind, filename);
      // Store a durable processing record first; failures stay visible and can be deleted/retried.
      p.tx.executeWithoutResult(
          st -> {
            p.lock(u, project);
            p.access(u, project, true);
            p.db.update(
                "INSERT INTO"
                    + " resources(id,tenant_id,project_id,kind,object_key,filename,content_type,byte_size,state)"
                    + " VALUES(?,?,?,?,?,?,?,?,'processing')",
                id,
                u.tenant(),
                project,
                kind,
                key,
                filename,
                checked.path("contentType").asText(),
                checked.path("byteSize").asLong());
          });
      try {
        store.client.putObject(
            b -> b.bucket(store.bucket).key(key).contentType(checked.path("contentType").asText()),
            software.amazon.awssdk.core.sync.RequestBody.fromFile(file));
        ready(id, checked);
      } catch (RuntimeException e) {
        p.db.update(
            "UPDATE resources SET state='failed',error=? WHERE id=?",
            e.getClass().getSimpleName() + ": " + e.getMessage(),
            id);
        throw e;
      }
      p.auth.audit(
          u,
          project,
          "resource.upload",
          Json.obj("resourceId", id, "bytes", checked.path("byteSize")));
      return present(u, row(u, project, id));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  public void ready(String id, JsonNode checked) {
    p.db.update(
        "UPDATE resources SET state='ready',sha256=?,inspection=?::jsonb,error=NULL,upload_id=NULL"
            + " WHERE id=?",
        checked.path("sha256").asText(),
        checked.path("inspection").toString(),
        id);
  }

  public ObjectNode multipart(Auth.User u, String project, JsonNode body) {
    p.access(u, project, true);
    String kind = Json.text(body, "kind", 1, 10), filename = Json.text(body, "filename", 1, 240);
    long bytes = Json.integer(body, "byteSize", 1, FileInspection.limit(kind, filename));
    String id = Json.id(), key = u.tenant() + "/" + project + "/" + id;
    String mime = FileInspection.MIMES.get(FileInspection.extension(filename));
    return p.tx.execute(
        st -> {
          p.lock(u, project);
          p.access(u, project, true);
          Integer pending =
              p.db.queryForObject(
                  "SELECT count(*) FROM resources WHERE tenant_id=? AND state IN"
                      + " ('uploading','processing')",
                  Integer.class,
                  u.tenant());
          Json.require(pending < 20, "At most 20 pending uploads per tenant.");
          String upload =
              store
                  .client
                  .createMultipartUpload(b -> b.bucket(store.bucket).key(key).contentType(mime))
                  .uploadId();
          p.db.update(
              "INSERT INTO"
                  + " resources(id,tenant_id,project_id,kind,object_key,filename,content_type,byte_size,state,upload_id,expires_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,'uploading',?,now()+interval '1 hour')",
              id,
              u.tenant(),
              project,
              kind,
              key,
              filename,
              mime,
              bytes,
              upload);
          return Json.obj(
              "resourceId",
              id,
              "state",
              "uploading",
              "partSize",
              5 * 1024 * 1024,
              "partCount",
              (bytes + 5 * 1024 * 1024 - 1) / (5 * 1024 * 1024),
              "expiresInSeconds",
              3600);
        });
  }

  public ObjectNode part(Auth.User u, String project, String id, int number) {
    p.access(u, project, true);
    var row = row(u, project, id);
    Json.require(
        row.get("state").equals("uploading")
            && ((java.sql.Timestamp) row.get("expires_at"))
                .toInstant()
                .isAfter(java.time.Instant.now()),
        "Upload is not active.");
    long bytes = ((Number) row.get("byte_size")).longValue();
    Json.require(
        number >= 1 && number <= (bytes + 5 * 1024 * 1024 - 1) / (5 * 1024 * 1024),
        "Invalid part number.");
    var request =
        UploadPartRequest.builder()
            .bucket(store.bucket)
            .key((String) row.get("object_key"))
            .uploadId((String) row.get("upload_id"))
            .partNumber(number)
            .contentLength(Math.min(5 * 1024 * 1024, bytes - (number - 1L) * 5 * 1024 * 1024))
            .build();
    String url =
        store
            .signer
            .presignUploadPart(
                b -> b.signatureDuration(Duration.ofMinutes(10)).uploadPartRequest(request))
            .url()
            .toString();
    return Json.obj("url", url, "partNumber", number, "expiresInSeconds", 600);
  }

  public ObjectNode complete(Auth.User u, String project, String id) {
    return p.tx.execute(
        st -> {
          p.lock(u, project);
          p.access(u, project, true);
          var row = row(u, project, id);
          if (row.get("state").equals("processing") || row.get("state").equals("ready"))
            return Json.obj("resource", present(u, row));
          Json.require(
              row.get("state").equals("uploading")
                  && ((java.sql.Timestamp) row.get("expires_at"))
                      .toInstant()
                      .isAfter(java.time.Instant.now()),
              "Upload is not active.");
          String key = (String) row.get("object_key"), upload = (String) row.get("upload_id");
          long bytes = ((Number) row.get("byte_size")).longValue();
          try {
            var parts =
                store
                    .client
                    .listParts(b -> b.bucket(store.bucket).key(key).uploadId(upload))
                    .parts();
            long total =
                parts.stream().mapToLong(software.amazon.awssdk.services.s3.model.Part::size).sum();
            Json.require(
                total == bytes && parts.size() == (bytes + 5 * 1024 * 1024 - 1) / (5 * 1024 * 1024),
                "Uploaded parts do not match the reserved size.");
            for (int i = 0; i < parts.size(); i++)
              Json.require(
                  parts.get(i).partNumber() == i + 1
                      && parts.get(i).size()
                          == Math.min(5 * 1024 * 1024, bytes - i * 5L * 1024 * 1024),
                  "Multipart boundaries are invalid.");
            store.client.completeMultipartUpload(
                b ->
                    b.bucket(store.bucket)
                        .key(key)
                        .uploadId(upload)
                        .multipartUpload(
                            m ->
                                m.parts(
                                    parts.stream()
                                        .map(
                                            part ->
                                                CompletedPart.builder()
                                                    .partNumber(part.partNumber())
                                                    .eTag(part.eTag())
                                                    .build())
                                        .toList())));
          } catch (S3Exception e) {
            if (!"NoSuchUpload".equals(e.awsErrorDetails().errorCode())) throw e;
            org.slf4j.LoggerFactory.getLogger(Resources.class)
                .warn(
                    "Recovering completed multipart after interrupted DB commit resourceId={}", id);
          }
          long actual =
              store.client.headObject(b -> b.bucket(store.bucket).key(key)).contentLength();
          Json.require(actual == bytes, "Stored object length does not match upload reservation.");
          p.db.update("UPDATE resources SET state='processing' WHERE id=?", id);
          String job = Json.id();
          p.db.update(
              "INSERT INTO jobs(id,tenant_id,project_id,resource_id,kind)"
                  + " VALUES(?,?,?,?,'inspect_resource') ON CONFLICT(resource_id,kind) DO NOTHING",
              job,
              u.tenant(),
              project,
              id);
          p.auth.audit(u, project, "resource.complete", Json.obj("resourceId", id, "jobId", job));
          return Json.obj("resourceId", id, "jobId", job, "state", "processing");
        });
  }

  void deleteObject(Map<String, Object> row) {
    if (row.get("upload_id") != null) {
      try {
        store.client.abortMultipartUpload(
            b ->
                b.bucket(store.bucket)
                    .key((String) row.get("object_key"))
                    .uploadId((String) row.get("upload_id")));
      } catch (S3Exception e) {
        if (!"NoSuchUpload".equals(e.awsErrorDetails().errorCode())) throw e;
        org.slf4j.LoggerFactory.getLogger(Resources.class)
            .info("multipart_already_closed resource={}", row.get("id"));
      }
    }
    store.client.deleteObject(b -> b.bucket(store.bucket).key((String) row.get("object_key")));
  }

  public ObjectNode delete(Auth.User u, String project, String id, boolean confirmed) {
    return p.tx.execute(
        st -> {
          p.lock(u, project);
          p.access(u, project, true);
          if (id.startsWith("builtin:"))
            throw new ApiException(
                403, "system_resource_read_only", "Bundled resources cannot be deleted.");
          var row = row(u, project, id);
          ObjectNode usage = usage(u, project, id);
          if (usage.path("count").asInt() > 0 && !confirmed)
            throw new ApiException(
                409,
                "resource_in_use",
                "Resource is referenced. Explicit confirmation is required.");
          String state = (String) row.get("state");
          if (state.equals("processing"))
            throw new ApiException(
                409, "resource_processing", "Wait for inspection before deletion.");
          deleteObject(row);
          p.db.update(
              "DELETE FROM resources WHERE tenant_id=? AND project_id=? AND id=?",
              u.tenant(),
              project,
              id);
          String kind = (String) row.get("kind");
          p.auth.audit(u, project, "resource.delete", Json.obj("resourceId", id));
          return Json.obj(
              "deleted" + Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + "AssetId",
              id,
              "usage",
              usage,
              "warning",
              usage.path("count").asInt() > 0
                  ? "Existing references will report missing resources."
                  : null);
        });
  }
}

@RestController
@RequestMapping("/api/v1/projects/{project}")
class ResourceController {
  final Resources s;

  ResourceController(Resources s) {
    this.s = s;
  }

  @GetMapping("/{kind:model|image|media}-assets")
  Object list(@PathVariable String project, @PathVariable String kind, HttpServletRequest r) {
    return Json.obj(kind + "Assets", s.list(s.p.auth.require(r), project, kind));
  }

  @PostMapping("/{kind:model|image|media}-assets")
  @ResponseStatus(HttpStatus.CREATED)
  Object upload(
      @PathVariable String project,
      @PathVariable String kind,
      @RequestParam String filename,
      HttpServletRequest r)
      throws IOException {
    return Json.obj(
        kind + "Asset", s.upload(s.p.auth.require(r), project, kind, filename, r.getInputStream()));
  }

  @GetMapping("/{kind:model|image|media}-assets/{id}/content")
  ResponseEntity<?> download(
      @PathVariable String project,
      @PathVariable String kind,
      @PathVariable String id,
      HttpServletRequest r) {
    var u = s.p.auth.require(r);
    s.p.access(u, project, false);
    var builtin = s.contracts.builtinResource(kind, id);
    if (builtin != null)
      return ResponseEntity.status(302)
          .header("Location", builtin.path("contentPath").asText())
          .build();
    var row = s.row(u, project, id);
    if (!row.get("state").equals("ready") || !row.get("kind").equals(kind))
      throw new ApiException(409, "resource_not_ready", "Resource is not ready for display.");
    return ResponseEntity.status(302)
        .header("Location", s.store.download((String) row.get("object_key")))
        .build();
  }

  @DeleteMapping("/{kind:model|image|media}-assets/{id}")
  Object delete(
      @PathVariable String project,
      @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean confirmReferenced,
      HttpServletRequest r) {
    return s.delete(s.p.auth.require(r), project, id, confirmReferenced);
  }

  @PostMapping("/uploads")
  @ResponseStatus(HttpStatus.CREATED)
  Object multipart(@PathVariable String project, @RequestBody JsonNode b, HttpServletRequest r) {
    return s.multipart(s.p.auth.require(r), project, b);
  }

  @PostMapping("/uploads/{id}/parts/{number}")
  Object part(
      @PathVariable String project,
      @PathVariable String id,
      @PathVariable int number,
      HttpServletRequest r) {
    return s.part(s.p.auth.require(r), project, id, number);
  }

  @PostMapping("/uploads/{id}/complete")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Object complete(@PathVariable String project, @PathVariable String id, HttpServletRequest r) {
    return s.complete(s.p.auth.require(r), project, id);
  }

  @GetMapping("/uploads/{id}")
  Object status(@PathVariable String project, @PathVariable String id, HttpServletRequest r) {
    var u = s.p.auth.require(r);
    return Json.obj("resource", s.present(u, s.row(u, project, id)));
  }

  @GetMapping("/jobs/{id}")
  Object job(@PathVariable String project, @PathVariable String id, HttpServletRequest r) {
    var u = s.p.auth.require(r);
    s.p.access(u, project, false);
    var rows =
        s.p.db.queryForList(
            "SELECT id,kind,state,attempts,error,created_at AS \"createdAt\",updated_at AS"
                + " \"updatedAt\" FROM jobs WHERE tenant_id=? AND project_id=? AND id=?",
            u.tenant(),
            project,
            id);
    if (rows.isEmpty()) throw new ApiException(404, "job_not_found", "Job not found.");
    return Json.obj("job", rows.getFirst());
  }
}
