package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.sync.RequestBody;

@Service
public class MeshoptVersions {
  private static final Logger log = LoggerFactory.getLogger(MeshoptVersions.class);
  final Resources resources;
  private final String node;
  private final String worker;
  private final Semaphore slot = new Semaphore(1);

  MeshoptVersions(Resources resources,
      @Value("${twin.meshopt.node:node}") String node,
      @Value("${twin.meshopt.worker:meshopt/worker.mjs}") String worker) {
    this.resources = resources;
    this.node = node;
    this.worker = worker;
  }

  ObjectNode generate(Auth.User user, String project, String sourceId) throws IOException {
    Projects p = resources.p;
    p.access(user, project, true);
    if (!slot.tryAcquire())
      throw new ApiException(429, "model_optimization_busy", "Another model is being compressed.");
    Path sourceFile = null, outputFile = null, errorFile = null;
    long start = System.nanoTime();
    try {
      Map<String, Object> source = resources.row(user, project, sourceId);
      if (!"model".equals(source.get("kind")) || !"ready".equals(source.get("state")))
        throw new ApiException(409, "model_source_not_ready", "Source model is not ready.");
      sourceFile = Files.createTempFile("twin-meshopt-source-", ".bin");
      outputFile = Files.createTempFile("twin-meshopt-output-", ".glb");
      errorFile = Files.createTempFile("twin-meshopt-error-", ".txt");
      Files.delete(sourceFile);
      Path downloadPath = sourceFile;
      resources.store.client.getObject(b -> b.bucket(resources.store.bucket)
          .key((String) source.get("object_key")), downloadPath);
      ObjectNode sourceInspection = FileInspection.inspect(sourceFile, "model", (String) source.get("filename"));
      if (sourceInspection.path("byteSize").asLong() != ((Number) source.get("byte_size")).longValue()
          || !sourceInspection.path("sha256").asText().equals(source.get("sha256")))
        throw new ApiException(409, "model_source_integrity_failed", "Source model bytes differ from their stored SHA-256.");

      Process process = new ProcessBuilder(node, "--max-old-space-size=256", worker)
          .redirectInput(sourceFile.toFile())
          .redirectOutput(outputFile.toFile())
          .redirectError(errorFile.toFile())
          .start();
      boolean completed;
      try {
        completed = process.waitFor(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new ApiException(503, "model_optimization_interrupted", "Model compression was interrupted.");
      }
      if (!completed) {
        process.destroyForcibly();
        throw new ApiException(504, "model_optimization_timeout", "Model compression exceeded 30 seconds.");
      }
      if (process.exitValue() != 0) {
        if (Files.size(errorFile) > 65536)
          throw new ApiException(422, "model_compression_worker_error_limit",
              "Model compression worker exceeded the error output limit.");
        String raw = Files.readString(errorFile);
        String code = "model_compression_failed";
        try {
          String candidate = Json.parse(raw).path("code").asText();
          if (candidate.matches("model_compression_[a-z_]+")) code = candidate;
        } catch (Exception e) {
          log.warn("meshopt_worker_invalid_error project={} source={} stderr={}", project, sourceId,
              raw.substring(0, Math.min(raw.length(), 500)));
        }
        log.warn("meshopt_worker_failed project={} source={} code={} stderr={}",
            project, sourceId, code, raw.substring(0, Math.min(raw.length(), 500)));
        throw new ApiException(422, code, "Model compression failed; inspect the model's extensions and buffer layout.");
      }
      ObjectNode checked = FileInspection.inspect(outputFile, "model", "model-meshopt.glb");
      JsonNode provenance = provenance(outputFile);
      if (!"meshopt-buffer-views-v1".equals(provenance.path("algorithm").asText())
          || !"1.1.1".equals(provenance.path("encoderVersion").asText())
          || !sourceInspection.path("sha256").asText().equals(provenance.path("sourceSha256").asText())
          || sourceInspection.path("byteSize").asLong() != provenance.path("sourceBytes").asLong()
          || provenance.path("compressedViews").asInt() < 1
          || !provenance.path("attributesExact").asBoolean()
          || !provenance.path("indicesExact").asBoolean())
        throw new ApiException(422, "model_compression_verification_failed", "Compressed model provenance is invalid.");

      String versionId = Json.id();
      String objectKey = user.tenant() + "/" + project + "/" + versionId;
      String sourceName = ((String) source.get("filename")).replaceFirst("(?i)\\.(gltf|glb)$", "");
      String filename = sourceName.substring(0, Math.min(200, sourceName.length())) + "-meshopt.glb";
      resources.store.client.putObject(
          b -> b.bucket(resources.store.bucket).key(objectKey).contentType("model/gltf-binary"),
          RequestBody.fromFile(outputFile));
      try {
        ObjectNode result = p.tx.execute(st -> {
          p.lock(user, project);
          p.access(user, project, true);
          Map<String, Object> current = resources.row(user, project, sourceId);
          if (!"ready".equals(current.get("state")) || !sourceInspection.path("sha256").asText().equals(current.get("sha256")))
            throw new ApiException(409, "model_source_changed", "Source model changed during compression.");
          p.db.update("INSERT INTO resources(id,tenant_id,project_id,kind,object_key,filename,content_type,byte_size,sha256,state,inspection,source_model_id,compression)"
                  + " VALUES(?,?,?,'model',?,?,?,?,?,'ready',?::jsonb,?,?::jsonb)",
              versionId, user.tenant(), project, objectKey, filename, "model/gltf-binary",
              checked.path("byteSize").asLong(), checked.path("sha256").asText(),
              checked.path("inspection").toString(), sourceId, provenance.toString());
          p.auth.audit(user, project, "model.meshopt.generate",
              Json.obj("sourceId", sourceId, "versionId", versionId,
                  "sourceSha256", sourceInspection.path("sha256").asText(),
                  "outputSha256", checked.path("sha256").asText()));
          return Json.obj("modelAsset", resources.present(user, resources.row(user, project, versionId)));
        });
        log.info("meshopt_succeeded project={} source={} version={} sourceBytes={} outputBytes={} durationMs={}",
            project, sourceId, versionId, sourceInspection.path("byteSize").asLong(),
            checked.path("byteSize").asLong(), Duration.ofNanos(System.nanoTime()-start).toMillis());
        return result;
      } catch (RuntimeException e) {
        try {
          resources.store.client.deleteObject(b -> b.bucket(resources.store.bucket).key(objectKey));
        } catch (RuntimeException cleanupError) {
          e.addSuppressed(cleanupError);
          log.error("meshopt_orphan_cleanup_failed project={} source={} objectKey={}",
              project, sourceId, objectKey, cleanupError);
        }
        throw e;
      }
    } catch (Exception e) {
      log.error("meshopt_failed project={} source={} durationMs={}", project, sourceId,
          Duration.ofNanos(System.nanoTime()-start).toMillis(), e);
      throw e;
    } finally {
      if (sourceFile != null) Files.deleteIfExists(sourceFile);
      if (outputFile != null) Files.deleteIfExists(outputFile);
      if (errorFile != null) Files.deleteIfExists(errorFile);
      slot.release();
    }
  }

  private static JsonNode provenance(Path file) throws IOException {
    try (var in = Files.newInputStream(file)) {
      byte[] header = in.readNBytes(20);
      if (header.length != 20) throw new ApiException(422, "model_compression_output_invalid", "GLB header is incomplete.");
      ByteBuffer view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
      if (view.getInt(0) != 0x46546c67 || view.getInt(4) != 2 || view.getInt(16) != 0x4e4f534a)
        throw new ApiException(422, "model_compression_output_invalid", "Expected a GLB 2.0 output.");
      int jsonLength = view.getInt(12);
      if (jsonLength < 1 || jsonLength > 8*1024*1024)
        throw new ApiException(422, "model_compression_output_invalid", "GLB JSON chunk exceeds budget.");
      JsonNode document = Json.M.readTree(in.readNBytes(jsonLength));
      return document.path("asset").path("extras").path("meshoptCompression");
    }
  }
}

@RestController
@RequestMapping("/api/v1/projects/{project}/model-assets")
class MeshoptVersionController {
  private final MeshoptVersions versions;

  MeshoptVersionController(MeshoptVersions versions) { this.versions = versions; }

  @PostMapping("/{sourceId}/meshopt-versions")
  @ResponseStatus(HttpStatus.CREATED)
  Object generate(@PathVariable String project, @PathVariable String sourceId, HttpServletRequest request)
      throws IOException {
    return versions.generate(versions.resources.p.auth.require(request), project, sourceId);
  }
}
