package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

/** Deterministic numerical device simulator. No model, node name or animation clock lives here. */
final class TwinDriveEngine {
  private TwinDriveEngine() {}

  static ObjectNode initial(String project, long revision, long now) {
    return Json.obj("snapshot", Json.obj("type", "snapshot", "projectId", project, "revision", revision,
        "sequence", 0, "timestamp", Instant.ofEpochMilli(now).toString(), "source", "simulator", "status", "idle",
        "points", Json.obj(), "procedure", null), "lastTickMs", now, "stepStartedMs", 0,
        "pausedAtMs", 0, "commands", List.of(), "rateWindowMs", now, "rateCount", 0);
  }

  static Map<String, JsonNode> points(JsonNode config) {
    Map<String, JsonNode> points = new LinkedHashMap<>();
    config.path("points").forEach(point -> points.put(point.path("id").asText(), point));
    return points;
  }

  static ObjectNode snapshot(ObjectNode state) { return (ObjectNode) state.path("snapshot"); }

  static boolean automatic(JsonNode config) {
    return config.path("enabled").asBoolean() && config.path("simulation").path("enabled").asBoolean();
  }

  static void requireInitialized(JsonNode snapshot, JsonNode values) {
    for (JsonNode value : values)
      if (!snapshot.path("points").has(value.path("pointId").asText()))
        throw new ApiException(409, "twin_reset_required", "Reset the simulator explicitly before moving point " + value.path("pointId").asText());
  }

  static void validateCommand(JsonNode command) {
    Json.fields(command, "type", "commandId", "expectedRevision", "operation", "values", "procedureId");
    Json.require(command.path("type").asText().equals("command"), "Expected a command message.");
    Contracts.identifier(Json.text(command, "commandId", 1, 120));
    Json.integer(command, "expectedRevision", 0, 9007199254740990L);
    String operation = Json.text(command, "operation", 1, 32);
    Json.require(Set.of("set", "move", "pause", "resume", "reset", "run-procedure", "stop-procedure").contains(operation), "Unknown simulator operation.");
    Json.require(Set.of("set", "move").contains(operation) == command.has("values"), "Only set/move commands require values.");
    Json.require(operation.equals("run-procedure") == command.has("procedureId"), "Only run-procedure requires procedureId.");
  }

  static ObjectNode command(ObjectNode state, JsonNode config, JsonNode command, long now) {
    validateCommand(command);
    ObjectNode snapshot = snapshot(state);
    if (snapshot.path("revision").asLong() != command.path("expectedRevision").asLong())
      throw new ApiException(409, "twin_revision_conflict", "Saved data-drive configuration changed; reload it before sending commands.");
    Json.require(config.path("enabled").asBoolean(), "Data-driven motion is not enabled in the saved configuration.");
    String operation = command.path("operation").asText();
    String stamp = Instant.ofEpochMilli(now).toString();
    ObjectNode samples = (ObjectNode) snapshot.path("points");
    JsonNode procedureState = snapshot.path("procedure");
    boolean procedureRunning = procedureState.isObject() && procedureState.path("status").asText().equals("running");
    if (Set.of("set", "move", "run-procedure").contains(operation) && procedureRunning)
      throw new ApiException(409, "twin_procedure_running", "Stop the active procedure before overriding its point targets.");
    // Frequent setpoint messages must not continually reset the integration clock and starve motion.
    advance(state, config, now, 1);
    switch (operation) {
      case "reset" -> {
        samples.removeAll();
        for (JsonNode point : config.path("points")) {
          double value = point.path("initialValue").asDouble();
          samples.set(point.path("id").asText(), sample(value, value, stamp, point));
        }
        snapshot.putNull("procedure"); snapshot.put("status", "running");
        state.put("stepStartedMs", 0); state.put("pausedAtMs", 0);
      }
      case "set", "move" -> {
        JsonNode values = command.path("values");
        TwinDriveDocuments.validateValues(values, points(config));
        if (operation.equals("move")) requireInitialized(snapshot, values);
        for (JsonNode value : values) {
          String id = value.path("pointId").asText(); double target = value.path("value").asDouble();
          samples.set(id, sample(operation.equals("set") ? target : samples.path(id).path("value").asDouble(), target, stamp, points(config).get(id)));
        }
        snapshot.putNull("procedure"); snapshot.put("status", "running"); state.put("pausedAtMs", 0);
      }
      case "pause" -> {
        if (snapshot.path("status").asText().equals("idle"))
          throw new ApiException(409, "twin_reset_required", "Reset or inject a point before pausing the simulator.");
        if (!snapshot.path("status").asText().equals("paused")) state.put("pausedAtMs", now);
        snapshot.put("status", "paused");
      }
      case "resume" -> {
        if (samples.isEmpty()) throw new ApiException(409, "twin_reset_required", "Reset the simulator explicitly before resuming.");
        if (procedureState.isObject() && procedureState.path("status").asText().equals("error"))
          throw new ApiException(409, "twin_procedure_failed", "Stop or reset the failed procedure before resuming.");
        long pausedAt = state.path("pausedAtMs").asLong();
        if (pausedAt > 0 && procedureRunning) state.put("stepStartedMs", state.path("stepStartedMs").asLong() + now - pausedAt);
        state.put("pausedAtMs", 0); snapshot.put("status", "running");
      }
      case "run-procedure" -> {
        String id = Json.text(command, "procedureId", 1, 120);
        JsonNode procedure = procedure(config, id);
        Json.require(procedure != null, "Unknown simulator procedure: " + id);
        for (JsonNode step : procedure.path("steps")) requireInitialized(snapshot, step.path("targets"));
        snapshot.set("procedure", Json.obj("id", id, "stepIndex", 0, "status", "running", "message", ""));
        snapshot.put("status", "running"); state.put("pausedAtMs", 0);
        setStepTargets(state, procedure.path("steps").get(0), now);
      }
      case "stop-procedure" -> {
        samples.elements().forEachRemaining(sample -> ((ObjectNode) sample).set("target", sample.path("value")));
        snapshot.putNull("procedure"); snapshot.put("status", "paused"); state.put("pausedAtMs", now);
      }
      default -> throw new IllegalStateException("Unvalidated simulator command");
    }
    state.put("lastTickMs", now);
    touch(state, now);
    return Json.obj("type", "command_ack", "commandId", command.path("commandId"), "sequence", snapshot.path("sequence"));
  }

  static ObjectNode sample(double value, double target, String stamp, JsonNode point) {
    ObjectNode sample = Json.obj("value", value, "target", target, "timestamp", stamp, "quality", "good");
    if (point.has("topic")) sample.set("topic", point.path("topic"));
    return sample;
  }

  static JsonNode procedure(JsonNode config, String id) {
    for (JsonNode procedure : config.path("procedures")) if (procedure.path("id").asText().equals(id)) return procedure;
    return null;
  }

  static void setStepTargets(ObjectNode state, JsonNode step, long now) {
    ObjectNode samples = (ObjectNode) snapshot(state).path("points");
    for (JsonNode target : step.path("targets")) ((ObjectNode) samples.path(target.path("pointId").asText())).set("target", target.path("value"));
    state.put("stepStartedMs", now);
  }

  static boolean reached(JsonNode samples, JsonNode step) {
    double tolerance = step.path("tolerance").asDouble();
    for (JsonNode target : step.path("targets"))
      if (Math.abs(samples.path(target.path("pointId").asText()).path("value").asDouble() - target.path("value").asDouble()) > tolerance) return false;
    return true;
  }

  static boolean tick(ObjectNode state, JsonNode config, long now) {
    return advance(state, config, now, 100);
  }

  /** Only the server scheduler calls this. Initialization is explicitly authorized by saved simulation.enabled. */
  static boolean automaticTick(ObjectNode state, JsonNode config, long now) {
    if (!automatic(config)) return false;
    if (snapshot(state).path("status").asText().equals("idle")) {
      command(state, config, Json.obj("type", "command", "commandId", "automatic-initialize",
          "expectedRevision", snapshot(state).path("revision"), "operation", "reset"), now);
      command(state, config, Json.obj("type", "command", "commandId", "automatic-start",
          "expectedRevision", snapshot(state).path("revision"), "operation", "run-procedure",
          "procedureId", config.path("simulation").path("procedureId")), now);
      return true;
    }
    return tick(state, config, now);
  }

  static boolean advance(ObjectNode state, JsonNode config, long now, long minimumElapsed) {
    long elapsed = now - state.path("lastTickMs").asLong();
    if (elapsed < minimumElapsed) return false;
    ObjectNode snapshot = snapshot(state), samples = (ObjectNode) snapshot.path("points");
    if (snapshot.path("status").asText().equals("running")) {
      // Legacy manual mode pauses without observers; the automatic scheduler keeps ticking without them.
      // A stalled automatic executor fails visibly instead of teleporting or silently restarting a procedure.
      if (elapsed > 1000) {
        if (automatic(config)) {
          snapshot.put("status", "error");
          if (snapshot.path("procedure").isObject()) ((ObjectNode) snapshot.path("procedure")).put("status", "error")
              .put("message", "Automatic simulator scheduling stopped for over 1 second; save configuration to restart without offline fast-forward.");
        } else { snapshot.put("status", "paused"); state.put("pausedAtMs", state.path("lastTickMs").asLong()); }
      } else {
        var points = points(config);
        samples.fields().forEachRemaining(entry -> {
          ObjectNode sample = (ObjectNode) entry.getValue();
          double current = sample.path("value").asDouble(), target = sample.path("target").asDouble();
          double step = points.get(entry.getKey()).path("maxSpeed").asDouble() * elapsed / 1000.0;
          sample.put("value", approach(current, target, step));
        });
        JsonNode run = snapshot.path("procedure");
        if (run.isObject() && run.path("status").asText().equals("running")) {
          JsonNode procedure = procedure(config, run.path("id").asText());
          if (procedure == null) throw new IllegalStateException("Runtime references a missing saved procedure");
          int index = run.path("stepIndex").asInt(); JsonNode step = procedure.path("steps").get(index);
          if (reached(samples, step)) {
            if (index + 1 == procedure.path("steps").size()) {
              if (automatic(config) && config.path("simulation").path("repeat").asBoolean()) {
                ((ObjectNode) run).put("stepIndex", 0);
                setStepTargets(state, procedure.path("steps").get(0), now);
              } else ((ObjectNode) run).put("status", "completed");
            }
            else { ((ObjectNode) run).put("stepIndex", index + 1); setStepTargets(state, procedure.path("steps").get(index + 1), now); }
          } else if (now - state.path("stepStartedMs").asLong() > step.path("timeoutMs").asLong()) {
            ((ObjectNode) run).put("status", "error").put("message", "Point targets did not arrive before the configured step timeout: " + step.path("id").asText());
            snapshot.put("status", "error");
          }
        }
      }
    }
    state.put("lastTickMs", now); touch(state, now); return true;
  }

  static double approach(double current, double target, double maximumStep) {
    if (Math.abs(target - current) <= maximumStep) return target;
    return current + Math.copySign(maximumStep, target - current);
  }

  static void touch(ObjectNode state, long now) {
    ObjectNode snapshot = snapshot(state); String stamp = Instant.ofEpochMilli(now).toString();
    snapshot.put("sequence", snapshot.path("sequence").asLong() + 1).put("timestamp", stamp);
    snapshot.path("points").elements().forEachRemaining(point -> ((ObjectNode) point).put("timestamp", stamp));
  }
}
