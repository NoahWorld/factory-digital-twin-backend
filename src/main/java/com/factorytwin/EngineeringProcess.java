package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A bounded local process. No shell, download, package installation or network service is used. */
@Component
public class EngineeringProcess {
  final String python, script;
  final Duration timeout;
  final String reason;
  final Logger log = LoggerFactory.getLogger(EngineeringProcess.class);

  EngineeringProcess(@Value("${twin.engineering.python:}") String python,
      @Value("${twin.engineering.worker:}") String script,
      @Value("${twin.engineering.timeout-seconds:900}") long timeoutSeconds) {
    this.python = python;
    this.script = script;
    if (timeoutSeconds < 30 || timeoutSeconds > 3600) throw new IllegalArgumentException("Engineering timeout must be 30..3600 seconds.");
    this.timeout = Duration.ofSeconds(timeoutSeconds);
    this.reason = probe();
  }

  boolean available() { return reason == null; }
  String unavailableReason() { return reason; }
  String unavailableMessage() {
    return reason == null ? null : "BIM/CAD 转换服务未就绪，请检查服务器离线转换组件与部署配置（" + reason + "）。";
  }

  String probe() {
    if (python.isBlank() || script.isBlank() || !Path.of(python).isAbsolute() || !Path.of(script).isAbsolute()
        || !Files.isExecutable(Path.of(python)) || !Files.isRegularFile(Path.of(script))) return "engineering_not_configured";
    Process process = null;
    Path diagnostics = null;
    try {
      diagnostics = Files.createTempFile("twin-engineering-probe-", ".log");
      process = new ProcessBuilder(python, "-c", "import ifcopenshell,ezdxf,trimesh,shapely,numpy,mapbox_earcut")
          .redirectError(diagnostics.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      if (!process.waitFor(20, TimeUnit.SECONDS)) {
        log.error("engineering_dependency_probe_timeout python={} timeoutSeconds=20", python);
        return "engineering_probe_failed";
      }
      if (process.exitValue() != 0) {
        try (InputStream in = Files.newInputStream(diagnostics)) {
          log.error("engineering_dependency_probe_rejected python={} exit={} diagnostic={}",
              python, process.exitValue(), new String(in.readNBytes(8192), StandardCharsets.UTF_8));
        }
        return "engineering_dependencies_missing";
      }
      return null;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.error("engineering_dependency_probe_failed", e);
      return "engineering_probe_failed";
    } finally {
      if (process != null && process.isAlive()) terminate(process);
      if (diagnostics != null) {
        try { Files.deleteIfExists(diagnostics); }
        catch (IOException e) { log.error("engineering_probe_cleanup_failed path={}", diagnostics, e); }
      }
    }
  }

  static void terminate(Process p) {
    p.descendants().forEach(ProcessHandle::destroyForcibly);
    p.destroyForcibly();
  }

  JsonNode execute(String id, String mode, Path input, Path output, Path options,
      Consumer<JsonNode> progress, Runnable heartbeat) throws Exception {
    if (!available()) throw new ApiException(503, reason, unavailableMessage());
    ProcessBuilder builder = new ProcessBuilder(python, script, "--mode", mode, "--input", input.toString(),
        "--output", output.toString(), "--options", options.toString());
    builder.environment().put("PYTHONUNBUFFERED", "1");
    Process process = builder.start();
    try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> stdout = readers.submit(() -> {
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
          String line;
          while ((line = line(reader)) != null) {
            if (line.isBlank()) continue;
            JsonNode event;
            try { event = Json.M.readTree(line); }
            catch (IOException e) { throw new IllegalStateException("Converter stdout must be JSONL.", e); }
            if (event == null || !event.isObject()) throw new IllegalStateException("Invalid converter progress event.");
            progress.accept(event);
          }
        } catch (IOException e) { throw new UncheckedIOException(e); }
      });
      Future<?> stderr = readers.submit(() -> {
        try (Reader reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
          String line;
          int count = 0;
          while ((line = line(reader)) != null) {
            if (++count <= 100) log.warn("engineering_process_stderr import={} detail={}", id, line);
            else if (count == 101) log.warn("engineering_process_stderr_truncated import={}", id);
          }
        } catch (IOException e) { throw new UncheckedIOException(e); }
      });
      long start = System.nanoTime(), nextHeartbeat = start;
      try {
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
          if (stdout.isDone()) stdout.get();
          if (stderr.isDone()) stderr.get();
          long now = System.nanoTime();
          if (now - start > timeout.toNanos()) throw new ApiException(422, "engineering_timeout", "转换超过时间限制，请拆分较大的工程文件后重试。");
          if (now >= nextHeartbeat) { heartbeat.run(); nextHeartbeat = now + Duration.ofSeconds(10).toNanos(); }
        }
        stdout.get(5, TimeUnit.SECONDS);
        stderr.get(5, TimeUnit.SECONDS);
        Path result = output.resolve("result.json");
        if (!Files.isRegularFile(result) || Files.size(result) > 4 * 1024 * 1024)
          throw new ApiException(422, "engineering_result_missing", "转换组件未生成有效结果。任务 ID：" + id);
        JsonNode document = Json.M.readTree(Files.readString(result));
        if (document == null || !document.isObject()) throw new IllegalStateException("Converter result must be a JSON object.");
        if (process.exitValue() != 0 || document.has("error")) {
          JsonNode error = document.path("error");
          String code = error.path("code").asText("engineering_conversion_failed");
          String message = error.path("message").asText("转换失败，请查看任务诊断。");
          throw new ConversionFailure(code, message, error.path("details"));
        }
        return document;
      } finally { if (process.isAlive()) terminate(process); }
    } finally { if (process.isAlive()) terminate(process); }
  }

  // Prevent an unexpected native-library line from allocating unbounded heap.
  static String line(Reader reader) throws IOException {
    StringBuilder value = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1 && c != '\n') {
      if (value.length() >= 16384) throw new IOException("Converter log line exceeds 16 KiB.");
      value.append((char) c);
    }
    return c == -1 && value.isEmpty() ? null : value.toString();
  }

  static class ConversionFailure extends Exception {
    final String code;
    final JsonNode details;
    ConversionFailure(String code, String message, JsonNode details) {
      super(message); this.code = code; this.details = details;
    }
  }
}
