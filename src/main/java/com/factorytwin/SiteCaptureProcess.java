package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** Fixed local executable, bounded JSONL protocol, no shell or downloads. */
@Component
public class SiteCaptureProcess {
  final String python, script;
  final Duration timeout;
  final Logger log = LoggerFactory.getLogger(SiteCaptureProcess.class);
  static final Map<String, String> ERRORS = Map.ofEntries(
      Map.entry("invalid_manifest", "重建任务输入清单无效，请检查任务日志。"),
      Map.entry("input_invalid", "素材不符合输入要求，请检查原始文件。"),
      Map.entry("image_decode_failed", "部分照片无法解码，请重新导出有效原图。"),
      Map.entry("image_quality_insufficient", "照片清晰度或尺寸不足，请按采集规范重新拍摄。"),
      Map.entry("video_invalid", "视频无法读取或超出采集限制，请使用符合要求的原始视频。"),
      Map.entry("coverage_insufficient", "有效视角重叠不足，请补拍连续、多位置的照片。"),
      Map.entry("reconstruction_failed", "相机位置或空间结构重建失败，请检查视角覆盖及场景纹理。"),
      Map.entry("training_failed", "写实模型训练失败，请检查 GPU 工作节点日志。"),
      Map.entry("resource_limit", "重建超出资源限制，请缩小采集区域或减少素材。"),
      Map.entry("worker_unavailable", "GPU 重建组件未就绪，请检查工作节点。"),
      Map.entry("timeout", "重建超过时间限制，请缩小采集区域后重试。"),
      Map.entry("output_invalid", "重建输出校验失败，结果未发布。"),
      Map.entry("internal_error", "重建组件发生异常，请使用任务 ID 检查日志。"));

  SiteCaptureProcess(@Value("${twin.capture.python:}") String python, @Value("${twin.capture.worker:}") String script,
      @Value("${twin.capture.timeout-seconds:7200}") long seconds) {
    this.python = python; this.script = script;
    if (seconds < 60 || seconds > 21600) throw new IllegalArgumentException("Capture timeout must be 60..21600 seconds.");
    this.timeout = Duration.ofSeconds(seconds);
  }
  boolean configured() {
    return !python.isBlank() && !script.isBlank() && Path.of(python).isAbsolute() && Path.of(script).isAbsolute()
        && Files.isExecutable(Path.of(python)) && Files.isRegularFile(Path.of(script));
  }
  ObjectNode probe() {
    if (!configured()) return Json.obj("available", false, "reason", "GPU 工作节点尚未配置离线重建组件。");
    Process process = null;
    try {
      process = new ProcessBuilder(python, script, "--probe").start();
      Process running = process;
      try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
        var stdout = io.submit(() -> boundedText(running.getInputStream(), 16384));
        var stderr = io.submit(() -> boundedText(running.getErrorStream(), 16384));
        try {
          if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("GPU capability probe timed out.");
          String errors = stderr.get(2, TimeUnit.SECONDS), output = stdout.get(2, TimeUnit.SECONDS);
          if (process.exitValue() != 0) throw new IOException("GPU probe failed: " + errors);
          JsonNode result = Json.M.readTree(output);
          if (result == null || !result.isObject() || !result.path("available").isBoolean()) throw new IOException("Invalid GPU probe response.");
          boolean available = result.path("available").asBoolean();
          if (available && !(result.path("cuda").asBoolean() && result.path("colmap").asBoolean() && result.path("ffmpeg").asBoolean()
              && result.path("precompiledExtension").asBoolean() && result.path("gsplatVersion").isTextual())) throw new IOException("GPU probe did not verify required components.");
          if (!available) log.warn("site_capture_probe_unavailable detail={}", output);
          return Json.obj("available", available, "reason", available ? null : "GPU 或离线重建组件未通过检查，请检查工作节点日志。");
        } finally { terminate(process); }
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.error("site_capture_probe_failed", e);
      return Json.obj("available", false, "reason", "GPU 工作节点检查失败，请检查工作节点日志。");
    } finally { if (process != null) terminate(process); }
  }
  static String boundedText(InputStream input, int limit) throws IOException {
    try (input) {
      byte[] bytes = input.readNBytes(limit + 1);
      if (bytes.length > limit) throw new IOException("Process output exceeds bounded budget.");
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }
  static void terminate(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    if (process.isAlive()) process.destroyForcibly();
  }

  JsonNode execute(String id, Path input, Path output, Consumer<JsonNode> progress, Runnable heartbeat) throws Exception {
    if (!configured()) throw new ApiException(503, "site_capture_unavailable", "GPU 工作节点尚未配置。");
    ProcessBuilder builder = new ProcessBuilder(python, script, "--input", input.toString(), "--output", output.toString());
    builder.environment().put("PYTHONUNBUFFERED", "1");
    Process process = builder.start();
    try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> stdout = readers.submit(() -> {
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
          String line;
          while ((line = EngineeringProcess.line(reader)) != null) {
            if (line.isBlank()) continue;
            JsonNode event = Json.M.readTree(line);
            if (event == null || !event.isObject()) throw new IOException("Capture progress must be a JSON object.");
            progress.accept(event);
          }
        } catch (IOException e) { throw new UncheckedIOException(e); }
      });
      Future<?> stderr = readers.submit(() -> {
        try (Reader reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
          String line; int count = 0;
          while ((line = EngineeringProcess.line(reader)) != null) {
            if (++count <= 100) log.warn("site_capture_process_stderr capture={} detail={}", id, line);
            else if (count == 101) log.warn("site_capture_process_stderr_truncated capture={}", id);
          }
        } catch (IOException e) { throw new UncheckedIOException(e); }
      });
      long begin = System.nanoTime(), nextHeartbeat = begin;
      try {
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
          if (stdout.isDone()) stdout.get();
          if (stderr.isDone()) stderr.get();
          long now = System.nanoTime();
          if (now - begin > timeout.toNanos()) throw new ApiException(422, "site_capture_timeout", "重建超过时间限制，请缩小采集区域后重试。");
          if (now >= nextHeartbeat) { heartbeat.run(); nextHeartbeat = now + Duration.ofSeconds(5).toNanos(); }
        }
        stdout.get(5, TimeUnit.SECONDS); stderr.get(5, TimeUnit.SECONDS);
        heartbeat.run();
        if (process.exitValue() != 0) {
          JsonNode failure = document(output.resolve("error.json"), 16384);
          String code = failure.path("code").asText();
          if (!ERRORS.containsKey(code)) throw new IOException("Unknown capture error code: " + code);
          log.error("site_capture_component_failed capture={} code={} diagnostic={}", id, code, failure);
          throw new ApiException(422, code, ERRORS.get(code));
        }
        return document(output.resolve("result.json"), 1024 * 1024);
      } finally { terminate(process); }
    } finally { terminate(process); }
  }
  static JsonNode document(Path path, long limit) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) throw new IOException("Missing or oversized capture result document.");
    JsonNode value = Json.M.readTree(Files.readString(path));
    if (value == null || !value.isObject()) throw new IOException("Invalid capture result document.");
    return value;
  }
}
