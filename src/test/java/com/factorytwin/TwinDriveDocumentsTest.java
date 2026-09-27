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
    var config = TwinDriveEngineTest.config(); config.set("bindings", Json.M.valueToTree(List.of(binding("lift-binding", "LiftMesh")))); return config;
  }
  void validate(ObjectNode config) {
    contracts.validate("TwinDrivePatch", Json.obj("expectedRevision", 0, "config", config));
    TwinDriveDocuments.validate(config);
  }
  @Test void generatedSchemaAndSemanticChecksAcceptBoundedConfig() {
    var config = config(); validate(config); TwinDriveEngineTest.addProcedure(config, 10000); validate(config);
    validate(TwinDriveDocuments.emptyConfig());
  }
  @Test void automaticSimulationRequiresExplicitUniqueTopicsBindingsAndSavedProcedure() {
    var config = TwinDriveEngineTest.automaticConfig(true); validate(config);
    var noTopic = config.deepCopy(); ((ObjectNode) noTopic.path("points").get(0)).remove("topic");
    assertThrows(ApiException.class, () -> validate(noTopic));
    var duplicate = config.deepCopy(); ((ArrayNode) duplicate.path("points")).add(duplicate.path("points").get(0).deepCopy());
    ((ObjectNode) duplicate.path("points").get(1)).put("id", "other").put("metricKey", "other");
    assertThrows(ApiException.class, () -> validate(duplicate));
    for (String topic : List.of("", "plant/#", "plant/+", "plant lift", "/leading")) {
      var invalid = config.deepCopy(); ((ObjectNode) invalid.path("points").get(0)).put("topic", topic);
      assertThrows(ApiException.class, () -> validate(invalid));
    }
    var missing = config.deepCopy(); ((ObjectNode) missing.path("simulation")).put("procedureId", "missing");
    assertThrows(ApiException.class, () -> validate(missing));
    var disabled = config.deepCopy().put("enabled", false); assertThrows(ApiException.class, () -> validate(disabled));
    var noBinding = config.deepCopy(); ((ArrayNode) noBinding.path("bindings")).removeAll(); assertThrows(ApiException.class, () -> validate(noBinding));
    var optional = config(); optional.set("simulation", Json.obj("enabled", false, "procedureId", "", "repeat", false)); validate(optional);
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
  @Test void pointsAndProcedureValuesMustUseFiniteEngineeringRanges() {
    var config = config(); ((ObjectNode) config.path("points").get(0)).put("maxSpeed", 0); assertThrows(ApiException.class, () -> validate(config));
    var range = config(); ((ObjectNode) range.path("points").get(0)).put("initialValue", 101); assertThrows(ApiException.class, () -> validate(range));
    var stale = config(); ((ObjectNode) stale.path("points").get(0)).put("staleAfterMs", 100); assertThrows(ApiException.class, () -> validate(stale));
    var procedure = config(); TwinDriveEngineTest.addProcedure(procedure, 1000);
    ((ObjectNode) procedure.path("procedures").get(0).path("steps").get(0).path("targets").get(0)).put("value", -1); assertThrows(ApiException.class, () -> validate(procedure));
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
