package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TwinDriveDocumentsTest {
  final Contracts contracts = new Contracts();
  ObjectNode binding(String id, String node) {
    return Json.obj("id", id, "label", "Lift", "pointId", "lift", "target", Json.obj("instanceId", "model", "modelAssetId", "builtin:machine", "nodeName", node), "parentBindingId", null,
        "useNodeRestPose", true, "kind", "translation", "axis", List.of(0,1,0), "pivot", List.of(0,0,0), "valueScale", 1, "valueOffset", 0, "poses", List.of());
  }
  ObjectNode config() {
    var config = TwinDriveDocuments.emptyConfig().put("enabled", true);
    config.set("connection", ApiMotionSourcesTest.connection("rest", ApiMotionSources.REST_PATH));
    config.set("points", Json.M.valueToTree(List.of(Json.obj("id", "lift", "label", "Lift", "assetId", "", "metricKey", "height", "unit", "m", "sourcePath", "agv.positionM", "min", 0, "max", 100, "initialValue", 0, "maxSpeed", 2, "staleAfterMs", 1500))));
    config.set("bindings", Json.M.valueToTree(List.of(binding("lift-binding", "LiftMesh")))); return config;
  }
  void validate(ObjectNode config) {
    contracts.validate("TwinDrivePatch", Json.obj("expectedRevision", 0, "config", config));
    TwinDriveDocuments.validate(config);
  }
  @Test void readonlyProjectionRetainsKnownSyntheticProvenanceAndRedactsExternalCredentials() {
    var builtIn = Json.obj("config", config()); TwinDriveDocuments.redact(builtIn);
    assertEquals(ApiMotionSources.REST_PATH, builtIn.path("config").path("connection").path("url").asText());
    var external = Json.obj("config", config()); ((ObjectNode) external.path("config").path("connection")).put("url", "https://private.example/state").put("subscribeMessage", "{\"private\":\"secret\"}"); TwinDriveDocuments.redact(external);
    assertEquals("", external.path("config").path("connection").path("url").asText()); assertTrue(external.path("config").path("connection").path("redacted").asBoolean()); assertFalse(external.path("config").path("connection").has("subscribeMessage"));
  }
  @Test void generatedSchemaAndSemanticChecksAcceptBoundedConfig() {
    var config = config(); validate(config);
    validate(TwinDriveDocuments.emptyConfig());
  }
  @Test void readableFeedbackLabelsAreOptionalButBoundedUniqueAndNonblank() {
    var labeled = config(); var point = (ObjectNode) labeled.path("points").get(0);
    point.set("valueLabels", Json.M.valueToTree(List.of(Json.obj("value", 0, "label", "送件到站"), Json.obj("value", 1, "label", "车辆停稳"))));
    validate(labeled);
    for (Object invalid : List.of(List.of(), List.of(Json.obj("value", 0, "label", " ")), List.of(Json.obj("value", 101, "label", "越界")),
        List.of(Json.obj("value", .5, "label", "非整数")), List.of(Json.obj("value", 0, "label", "重复"), Json.obj("value", 0, "label", "重复")),
        List.of(Json.obj("value", 0, "label", "控制\n字符")), List.of("not-an-object"))) {
      point.set("valueLabels", Json.M.valueToTree(invalid));
      assertThrows(ApiException.class, () -> validate(labeled));
      assertThrows(ApiException.class, () -> TwinDriveDocuments.validate(labeled));
    }
  }
  @Test void rejectsRetiredSimulationTopicsAndInvalidSourcePaths() {
    var legacy = config().put("source", "simulator");
    assertEquals("legacy_simulation_removed", assertThrows(ApiException.class, () -> TwinDriveDocuments.validate(legacy)).code);
    var simulation = config(); simulation.set("simulation", Json.obj("enabled", false, "procedureId", "", "repeat", false)); assertThrows(ApiException.class, () -> validate(simulation));
    var topic = config(); ((ObjectNode) topic.path("points").get(0)).put("topic", "plant/lift/height"); assertThrows(ApiException.class, () -> validate(topic));
    for (String path : List.of("", "agv.__proto__", "agv.*", "items[1000].value", "a.b.c.d.e.f.g.h.i")) {
      var invalid = config(); ((ObjectNode) invalid.path("points").get(0)).put("sourcePath", path); assertThrows(ApiException.class, () -> validate(invalid));
    }
    var noBinding = config(); ((ArrayNode) noBinding.path("bindings")).removeAll(); assertThrows(ApiException.class, () -> validate(noBinding));
    var invalidUrl = config(); ((ObjectNode) invalidUrl.path("connection")).put("url", "/api/v1/users"); assertThrows(ApiException.class, () -> validate(invalidUrl));
    var redacted = config(); ((ObjectNode) redacted.path("connection")).put("redacted", true); assertThrows(ApiException.class, () -> validate(redacted));
  }
  @Test void rejectsUnknownFieldsInvalidVectorsCyclesAndDuplicateTargets() {
    var config = config(); config.put("script", "not supported"); assertThrows(ApiException.class, () -> validate(config));
    var wrongAxis = config(); ((ObjectNode) wrongAxis.path("bindings").get(0)).set("axis", Json.M.valueToTree(List.of(0,2,0))); assertThrows(ApiException.class, () -> validate(wrongAxis));
    var cycle = config(); ((ObjectNode) cycle.path("bindings").get(0)).put("parentBindingId", "lift-binding"); assertThrows(ApiException.class, () -> validate(cycle));
    var duplicate = config(); ((ArrayNode) duplicate.path("bindings")).add(binding("second", "LiftMesh")); assertThrows(ApiException.class, () -> validate(duplicate));
    var unknownPoint = config(); ((ObjectNode) unknownPoint.path("bindings").get(0)).put("pointId", "unknown"); assertThrows(ApiException.class, () -> validate(unknownPoint));
    var nonpose = config(); ((ArrayNode) nonpose.path("bindings").get(0).path("poses")).add(Json.obj("value", 0, "position", List.of(0,0,0), "rotation", List.of(0,0,0), "scale", List.of(1,1,1))); assertThrows(ApiException.class, () -> validate(nonpose));
  }
  @Test void protectsInstanceResourceAndNativeAnimationExclusivity() {
    var config = config(); var model = Json.obj("id", "model", "modelAssetId", "builtin:machine", "animation", Json.obj("enabled", false));
    TwinDriveDocuments.validateSceneReferences(config, Json.obj("playAnimations", true), List.of(model));
    model.set("animation", Json.obj("enabled", true));
    assertEquals("twin_animation_conflict", assertThrows(ApiException.class, () -> TwinDriveDocuments.validateSceneReferences(config, Json.obj("playAnimations", true), List.of(model))).code);
    TwinDriveDocuments.validateSceneReferences(config, Json.obj("playAnimations", false), List.of(model));
    model.put("modelAssetId", "different");
    assertThrows(ApiException.class, () -> TwinDriveDocuments.validateSceneReferences(config, Json.obj("playAnimations", false), List.of(model)));
    assertThrows(ApiException.class, () -> TwinDriveDocuments.validateSceneReferences(config, Json.obj(), List.of()));
  }
  @Test void apiRangesDoNotDependOnUnusedSimulationInitialValuesOrSpeeds() {
    var config = config(); ((ObjectNode) config.path("points").get(0)).put("min", 10).put("max", 20).put("initialValue", 0).put("maxSpeed", 0);
    validate(config);
  }
  @Test void pointsMustUseFiniteEngineeringRangesAndBoundedCompatibilityValues() {
    var config = config(); ((ObjectNode) config.path("points").get(0)).put("maxSpeed", -1); assertThrows(ApiException.class, () -> validate(config));
    var range = config(); ((ObjectNode) range.path("points").get(0)).put("initialValue", 1_000_001); assertThrows(ApiException.class, () -> validate(range));
    var stale = config(); ((ObjectNode) stale.path("points").get(0)).put("staleAfterMs", 100); assertThrows(ApiException.class, () -> validate(stale));

  }

  @Test void finiteScaleAndOffsetCannotOverflowMappedEngineeringRange() {
    var positive = config(); ((ObjectNode) positive.path("bindings").get(0)).put("valueScale", Double.MAX_VALUE);
    assertThrows(ApiException.class, () -> validate(positive));
    var negative = config(); ((ObjectNode) negative.path("points").get(0)).put("min", -100);
    ((ObjectNode) negative.path("bindings").get(0)).put("valueScale", -Double.MAX_VALUE);
    assertThrows(ApiException.class, () -> validate(negative));
    var offset = config(); ((ObjectNode) offset.path("points").get(0)).put("max", Double.MAX_VALUE);
    ((ObjectNode) offset.path("bindings").get(0)).put("valueOffset", Double.MAX_VALUE);
    assertThrows(ApiException.class, () -> validate(offset));
    var bounded = config(); ((ObjectNode) bounded.path("bindings").get(0)).put("valueScale", -2).put("valueOffset", 1000);
    validate(bounded);
  }
}
