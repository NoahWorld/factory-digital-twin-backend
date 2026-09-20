package com.factorytwin;

import java.nio.file.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "twin.mode", havingValue = "worker")
public class JobWorker {
  final Resources resources;
  final Projects p;
  final String owner = Json.id();
  final Logger log = LoggerFactory.getLogger(JobWorker.class);

  public JobWorker(Resources resources) {
    this.resources = resources;
    this.p = resources.p;
  }

  @Scheduled(fixedDelay = 1000)
  public void run() {
    // Expired leases are retryable; exhausted attempts become visible terminal failures.
    p.tx.executeWithoutResult(
        st -> {
          var failed =
              p.db.queryForList(
                  "UPDATE jobs SET state='failed',error='Worker lease expired after three"
                      + " attempts',updated_at=now() WHERE state='running' AND lease_until<now()"
                      + " AND attempts>=3 RETURNING resource_id");
          for (var row : failed)
            p.db.update(
                "UPDATE resources SET state='failed',error='Worker lease expired after three"
                    + " attempts' WHERE id=?",
                row.get("resource_id"));
        });
    var job =
        p.tx.execute(
            st -> {
              var rows =
                  p.db.queryForList(
                      "SELECT j.*,r.object_key,r.kind AS resource_kind,r.filename,r.byte_size FROM"
                          + " jobs j JOIN resources r ON r.id=j.resource_id WHERE (j.state='queued'"
                          + " OR (j.state='running' AND j.lease_until<now())) AND j.attempts<3"
                          + " ORDER BY j.created_at FOR UPDATE OF j SKIP LOCKED LIMIT 1");
              if (rows.isEmpty()) return null;
              var row = rows.getFirst();
              p.db.update(
                  "UPDATE jobs SET"
                      + " state='running',attempts=attempts+1,lease_owner=?,lease_until=now()+interval"
                      + " '5 minutes',updated_at=now() WHERE id=?",
                  owner,
                  row.get("id"));
              return row;
            });
    if (job == null) return;
    String id = (String) job.get("id"), rid = (String) job.get("resource_id");
    Path file = null;
    try {
      file = Files.createTempFile("twin-inspect-", ".bin");
      Files.delete(file);
      resources.store.client.getObject(
          b -> b.bucket(resources.store.bucket).key((String) job.get("object_key")), file);
      Json.require(
          Files.size(file) == ((Number) job.get("byte_size")).longValue(),
          "Stored object byte count changed.");
      var result =
          FileInspection.inspect(
              file, (String) job.get("resource_kind"), (String) job.get("filename"));
      p.tx.executeWithoutResult(
          st -> {
            int n =
                p.db.update(
                    "UPDATE jobs SET"
                        + " state='succeeded',lease_owner=NULL,lease_until=NULL,updated_at=now()"
                        + " WHERE id=? AND lease_owner=? AND state='running' AND lease_until>now()",
                    id,
                    owner);
            if (n != 1) throw new IllegalStateException("Inspection lease lost for job " + id);
            resources.ready(rid, result);
          });
      log.info(
          "inspection_succeeded job={} resource={} bytes={}", id, rid, result.path("byteSize"));
    } catch (Exception e) {
      log.error("inspection_failed job={} resource={}", id, rid, e);
      p.tx.executeWithoutResult(
          st -> {
            int n =
                p.db.update(
                    "UPDATE jobs SET state='failed',error=?,updated_at=now() WHERE id=? AND"
                        + " lease_owner=? AND state='running'",
                    e.getClass().getSimpleName()
                        + ": "
                        + Objects.toString(e.getMessage(), "unknown"),
                    id,
                    owner);
            if (n == 1)
              p.db.update(
                  "UPDATE resources SET state='failed',error=? WHERE id=?",
                  e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "unknown"),
                  rid);
          });
    } finally {
      if (file != null)
        try {
          Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
          log.error("inspection_temp_cleanup_failed job={} path={}", id, file, e);
        }
    }
  }

  @Scheduled(fixedDelay = 60000)
  public void expireUploads() {
    int abandoned =
        p.db.update(
            "UPDATE resources r SET state='failed',error='Upload process interrupted before"
                + " completion; delete and retry.' WHERE state='processing' AND"
                + " created_at<now()-interval '5 minutes' AND NOT EXISTS(SELECT 1 FROM jobs j WHERE"
                + " j.resource_id=r.id)");
    if (abandoned > 0) log.error("interrupted_uploads_marked_failed count={}", abandoned);
    for (var row :
        p.db.queryForList(
            "SELECT id,object_key,upload_id FROM resources WHERE state='uploading' AND"
                + " expires_at<now() LIMIT 20")) {
      String id = (String) row.get("id");
      try {
        try {
          resources.store.client.abortMultipartUpload(
              b ->
                  b.bucket(resources.store.bucket)
                      .key((String) row.get("object_key"))
                      .uploadId((String) row.get("upload_id")));
        } catch (software.amazon.awssdk.services.s3.model.NoSuchUploadException expired) {
          log.info("upload_already_aborted resource={}", id);
        }
        p.db.update(
            "UPDATE resources SET state='failed',error='Upload session expired',upload_id=NULL"
                + " WHERE id=? AND state='uploading'",
            id);
      } catch (Exception e) {
        log.error("upload_expiration_cleanup_failed resource={}", id, e);
      }
    }
  }
}
