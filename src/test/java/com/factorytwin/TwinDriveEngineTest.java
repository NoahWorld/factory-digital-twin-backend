package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TwinDriveEngineTest {
  static ObjectNode config() {
    ObjectNode config = TwinDriveDocuments.emptyConfig().put("enabled", true);
    config.set("points", Json.M.valueToTree(List.of(Json.obj("id", "lift", "label", "Lift", "assetId", "machine", "metricKey", "height", "unit", "m", "min", 0, "max", 100, "initialValue", 0, "maxSpeed", 2, "staleAfterMs", 1500))));
    return config;
  }
  static ObjectNode command(String operation) { return Json.obj("type", "command", "commandId", Json.id(), "expectedRevision", 1, "operation", operation); }
  static ObjectNode move(double value) { return command("move").set("values", Json.M.valueToTree(List.of(Json.obj("pointId", "lift", "value", value)))); }
  static JsonNode sample(ObjectNode state) { return TwinDriveEngine.snapshot(state).path("points").path("lift"); }
  static void addProcedure(ObjectNode config, long timeout) {
    config.set("procedures", Json.M.valueToTree(List.of(Json.obj("id", "cycle", "label", "Cycle", "steps", List.of(
        Json.obj("id", "raise", "label", "Raise", "targets", List.of(Json.obj("pointId", "lift", "value", 2)), "tolerance", 0.001, "timeoutMs", timeout),
        Json.obj("id", "lower", "label", "Lower", "targets", List.of(Json.obj("pointId", "lift", "value", 0)), "tolerance", 0.001, "timeoutMs", timeout))))));
  }

  static ObjectNode automaticConfig(boolean repeat) {
    var config = new TwinDriveDocumentsTest().config();
    ((ObjectNode) config.path("points").get(0)).put("topic", "plant/lift/height");
    addProcedure(config, 5000);
    config.set("simulation", Json.obj("enabled", true, "procedureId", "cycle", "repeat", repeat));
    return config;
  }

  @Test void autonomousServerInitializesAndRepeatsOnlyAfterActualArrivalWithoutResettingSamples() {
    var config = automaticConfig(true); var state = TwinDriveEngine.initial("scene", 1, 1000);
    TwinDriveEngine.automaticTick(state, config, 1000);
    assertEquals("plant/lift/height", sample(state).path("topic").asText());
    assertEquals(0, sample(state).path("value").asDouble());
    for (long now = 1100; now <= 3900; now += 100) {
      double before = sample(state).path("value").asDouble();
      TwinDriveEngine.automaticTick(state, config, now);
      assertTrue(Math.abs(sample(state).path("value").asDouble() - before) <= .20000001);
      assertEquals("running", TwinDriveEngine.snapshot(state).path("procedure").path("status").asText());
      if (now == 1500) assertEquals(0, TwinDriveEngine.snapshot(state).path("procedure").path("stepIndex").asInt());
      if (now == 2000) assertEquals(1, TwinDriveEngine.snapshot(state).path("procedure").path("stepIndex").asInt());
      if (now == 3000) assertEquals(0, TwinDriveEngine.snapshot(state).path("procedure").path("stepIndex").asInt());
    }
    assertTrue(sample(state).path("value").asDouble() > 1.7);
  }

  @Test void autonomousDisabledDoesNothingOneShotStopsAndSchedulerStallFailsVisibly() {
    var config = automaticConfig(false); var state = TwinDriveEngine.initial("scene", 1, 1000);
    ((ObjectNode) config.path("simulation")).put("enabled", false);
    assertFalse(TwinDriveEngine.automaticTick(state, config, 1000));
    assertTrue(TwinDriveEngine.snapshot(state).path("points").isEmpty());
    ((ObjectNode) config.path("simulation")).put("enabled", true);
    for (long now = 1000; now <= 3500; now += 100) TwinDriveEngine.automaticTick(state, config, now);
    assertEquals("completed", TwinDriveEngine.snapshot(state).path("procedure").path("status").asText());
    assertEquals(0, sample(state).path("value").asDouble(), 1e-9);
    var stopped = TwinDriveEngine.initial("scene", 1, 1000);
    TwinDriveEngine.automaticTick(stopped, config, 1000); TwinDriveEngine.automaticTick(stopped, config, 5000);
    assertEquals("error", TwinDriveEngine.snapshot(stopped).path("status").asText());
    assertTrue(TwinDriveEngine.snapshot(stopped).path("procedure").path("message").asText().contains("save configuration"));
    assertEquals(0, sample(stopped).path("value").asDouble());
  }

  @Test void initialHasNoFabricatedSamplesAndMoveRequiresExplicitInitialization() {
    var state = TwinDriveEngine.initial("scene", 1, 1000);
    assertTrue(TwinDriveEngine.snapshot(state).path("points").isEmpty());
    assertEquals("idle", TwinDriveEngine.snapshot(state).path("status").asText());
    assertEquals("twin_reset_required", assertThrows(ApiException.class, () -> TwinDriveEngine.command(state, config(), move(1), 1100)).code);
    assertEquals("twin_reset_required", assertThrows(ApiException.class, () -> TwinDriveEngine.command(state, config(), command("resume"), 1100)).code);
  }

  @Test void actualMovesBySpeedTowardTargetWithoutOvershootOrTimeline() {
    var state = TwinDriveEngine.initial("scene", 1, 1000); var config = config();
    TwinDriveEngine.command(state, config, command("reset"), 1000);
    TwinDriveEngine.command(state, config, move(0.5), 1000);
    assertEquals(0, sample(state).path("value").asDouble());
    TwinDriveEngine.tick(state, config, 1100);
    assertEquals(0.2, sample(state).path("value").asDouble(), 1e-9);
    TwinDriveEngine.tick(state, config, 1300);
    assertEquals(0.5, sample(state).path("value").asDouble(), 1e-9);
    TwinDriveEngine.tick(state, config, 2000);
    assertEquals(0.5, sample(state).path("value").asDouble(), 1e-9);
    TwinDriveEngine.command(state, config, move(0), 2000);
    TwinDriveEngine.tick(state, config, 2100);
    assertEquals(0.3, sample(state).path("value").asDouble(), 1e-9);
  }

  @Test void setIsAnActualSampleAndPauseKeepsTruthfulStableValues() {
    var config = config(); var state = TwinDriveEngine.initial("scene", 1, 1000);
    var inject = move(7).put("operation", "set");
    var ack = TwinDriveEngine.command(state, config, inject, 1000);
    assertEquals(inject.path("commandId"), ack.path("commandId"));
    assertEquals(7, sample(state).path("value").asDouble());
    TwinDriveEngine.command(state, config, move(10), 1100);
    TwinDriveEngine.command(state, config, command("pause"), 1100);
    TwinDriveEngine.tick(state, config, 1700);
    assertEquals(7, sample(state).path("value").asDouble());
    assertEquals("paused", TwinDriveEngine.snapshot(state).path("status").asText());
    assertEquals("1970-01-01T00:00:01.700Z", sample(state).path("timestamp").asText());
    TwinDriveEngine.command(state, config, command("resume"), 1700);
    TwinDriveEngine.tick(state, config, 1800);
    assertEquals(7.2, sample(state).path("value").asDouble(), 1e-9);
  }

  @Test void procedureAdvancesOnlyAfterActualArrivalAndCannotBeOverridden() {
    var config = config(); addProcedure(config, 5000);
    var state = TwinDriveEngine.initial("scene", 1, 1000);
    TwinDriveEngine.command(state, config, command("reset"), 1000);
    TwinDriveEngine.command(state, config, command("run-procedure").put("procedureId", "cycle"), 1000);
    assertEquals("twin_procedure_running", assertThrows(ApiException.class, () -> TwinDriveEngine.command(state, config, move(7), 1100)).code);
    TwinDriveEngine.tick(state, config, 1500);
    assertEquals(0, TwinDriveEngine.snapshot(state).path("procedure").path("stepIndex").asInt());
    TwinDriveEngine.tick(state, config, 2000);
    assertEquals(1, TwinDriveEngine.snapshot(state).path("procedure").path("stepIndex").asInt());
    assertEquals(2, sample(state).path("value").asDouble());
    assertEquals(0, sample(state).path("target").asDouble());
    TwinDriveEngine.tick(state, config, 3000);
    assertEquals("completed", TwinDriveEngine.snapshot(state).path("procedure").path("status").asText());
    assertEquals(0, sample(state).path("value").asDouble());
  }

  @Test void procedureTimeoutFailsRatherThanJumpingToNextStepAndPauseExcludesHeldDuration() {
    var config = config(); addProcedure(config, 1000);
    ((ObjectNode) config.path("points").get(0)).put("maxSpeed", 0.1);
    var state = TwinDriveEngine.initial("scene", 1, 1000);
    TwinDriveEngine.command(state, config, command("reset"), 1000);
    TwinDriveEngine.command(state, config, command("run-procedure").put("procedureId", "cycle"), 1000);
    TwinDriveEngine.tick(state, config, 1500);
    TwinDriveEngine.command(state, config, command("pause"), 1500);
    TwinDriveEngine.tick(state, config, 5000);
    TwinDriveEngine.command(state, config, command("resume"), 5000);
    TwinDriveEngine.tick(state, config, 5400);
    assertEquals("running", TwinDriveEngine.snapshot(state).path("status").asText());
    TwinDriveEngine.tick(state, config, 5600);
    assertEquals("error", TwinDriveEngine.snapshot(state).path("status").asText());
    assertEquals(0, TwinDriveEngine.snapshot(state).path("procedure").path("stepIndex").asInt());
    assertEquals("twin_procedure_failed", assertThrows(ApiException.class, () -> TwinDriveEngine.command(state, config, command("resume"), 5700)).code);
    TwinDriveEngine.command(state, config, command("stop-procedure"), 5700);
    assertEquals(sample(state).path("value"), sample(state).path("target"));
  }

  @Test void longDisconnectPausesWithoutFastForwardAndConfigRevisionIsEnforced() {
    var config = config(); var state = TwinDriveEngine.initial("scene", 1, 1000);
    TwinDriveEngine.command(state, config, command("reset"), 1000);
    TwinDriveEngine.command(state, config, move(100), 1000);
    TwinDriveEngine.tick(state, config, 20000);
    assertEquals(0, sample(state).path("value").asDouble());
    assertEquals("paused", TwinDriveEngine.snapshot(state).path("status").asText());
    assertEquals("twin_revision_conflict", assertThrows(ApiException.class, () -> TwinDriveEngine.command(state, config, command("reset").put("expectedRevision", 0), 21000)).code);
  }

  @Test void rapidSetpointsCannotStarveActualMotion() {
    var config = config(); var state = TwinDriveEngine.initial("scene", 1, 1000);
    TwinDriveEngine.command(state, config, command("reset"), 1000);
    TwinDriveEngine.command(state, config, move(20), 1000);
    for (long now = 1050; now <= 2000; now += 50) TwinDriveEngine.command(state, config, move(20), now);
    assertEquals(2, sample(state).path("value").asDouble(), 1e-9);
  }

  @Test void reconnectHoldDoesNotConsumeProcedureTimeout() {
    var config = config(); addProcedure(config, 2000);
    var state = TwinDriveEngine.initial("scene", 1, 1000);
    TwinDriveEngine.command(state, config, command("reset"), 1000);
    TwinDriveEngine.command(state, config, command("run-procedure").put("procedureId", "cycle"), 1000);
    TwinDriveEngine.tick(state, config, 20000);
    TwinDriveEngine.command(state, config, command("resume"), 21000);
    TwinDriveEngine.tick(state, config, 21500);
    assertEquals("running", TwinDriveEngine.snapshot(state).path("status").asText());
    assertEquals(1, sample(state).path("value").asDouble(), 1e-9);
  }

  @Test void rejectsMalformedCommandsUnknownFieldsAndOutOfRangeValues() {
    var config = config(); var state = TwinDriveEngine.initial("scene", 1, 1000);
    TwinDriveEngine.command(state, config, command("reset"), 1000);
    for (ObjectNode command : List.of(command("script"), command("reset").put("script", "no"), command("move"), move(101), move(-1), move(Double.NaN), command("reset").put("procedureId", "cycle")))
      assertThrows(ApiException.class, () -> TwinDriveEngine.command(state, config, command, 1000), command.toString());
    var duplicate = move(1); ((ArrayNode) duplicate.path("values")).add(Json.obj("pointId", "lift", "value", 2));
    assertThrows(ApiException.class, () -> TwinDriveEngine.command(state, config, duplicate, 1000));
  }
}
