package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProcessControlContractTest {
  final Contracts contracts = new Contracts();

  static ObjectNode binding(String key, String type) {
    Object value = switch (type) { case "number" -> 1; case "boolean" -> true; default -> "run"; };
    return Json.obj("pointKey", key, "valueType", type, "staleAfterSeconds", 30,
        "states", List.of(Json.obj("label", "Running", "operator", "eq", "value", value,
            "color", "#00ff88", "visible", true, "flowing", true, "direction", "forward", "speed", 1)));
  }

  static ObjectNode node(String id) {
    return Json.obj("id", id, "type", "rectangle", "x", 0, "y", 0, "width", 240, "height", 160, "zIndex", 0,
        "props", Json.obj("fillColor", "#001122", "borderColor", "#334455", "borderWidth", 1, "borderRadius", 8, "opacity", 1),
        "resourceRefs", List.of(), "dataBindingRefs", List.of());
  }

  static ObjectNode pipe(String id, String sourceNodeId) {
    ObjectNode node = node(id).put("type", "process-pipe").put("width", 1).put("height", 1);
    node.set("props", Json.obj("label", "Water", "source", Json.obj("x", 100, "y", 120, "nodeId", sourceNodeId, "port", "right"),
        "target", Json.obj("x", 480, "y", 200, "nodeId", null, "port", "left"), "waypoints", List.of(Json.obj("x", 300, "y", 160)),
        "routing", "horizontal", "color", "#33bbff", "width", 10, "flowing", false, "direction", "forward", "speed", 1));
    return node;
  }

  static ObjectNode patch(int revision, JsonNode... nodes) {
    return Json.obj("expectedRevision", revision, "upsertNodes", List.of(nodes), "deleteNodeIds", List.of());
  }

  void validateBinding(JsonNode binding) { ProcessControls.stateBinding(contracts, binding, "test"); }

  @Test void genericPointNamesAndStrictStateRulesMatchAuthoringContract() {
    for (String key : List.of("1", "plant.temperature", "生产线 温度", "🙂".repeat(100)))
      for (String type : List.of("number", "boolean", "string")) validateBinding(binding(key, type));
    for (String key : List.of("", " leading", "trailing ", "\ufeffkey", "key\u00a0", "bad\nkey", "bad\u007fkey", "🙂".repeat(101)))
      assertThrows(ApiException.class, () -> validateBinding(binding(key, "number")), key);
    var base = binding("temperature", "number");
    var eight = base.deepCopy();
    var states = eight.putArray("states");
    for (int i = 0; i < 8; i++) states.add(base.path("states").get(0).deepCopy());
    validateBinding(eight);
    states.add(base.path("states").get(0).deepCopy());
    assertThrows(ApiException.class, () -> validateBinding(eight));
    for (String operator : List.of("eq", "gt", "gte", "lt", "lte")) {
      var numeric = base.deepCopy();
      ((ObjectNode) numeric.path("states").get(0)).put("operator", operator);
      validateBinding(numeric);
    }
    for (ObjectNode invalid : List.of(base.deepCopy().put("staleAfterSeconds", 0), base.deepCopy().put("staleAfterSeconds", 1.5),
        base.deepCopy().put("staleAfterSeconds", 86401), base.deepCopy().put("extra", true)))
      assertThrows(ApiException.class, () -> validateBinding(invalid));
    for (String type : List.of("boolean", "string")) {
      var invalid = binding("temperature", type);
      ((ObjectNode) invalid.path("states").get(0)).put("operator", "gt");
      assertThrows(ApiException.class, () -> validateBinding(invalid));
    }
    for (JsonNode wrong : List.of(Json.M.valueToTree("1"), Json.M.valueToTree(true), Json.M.nullNode(), Json.M.valueToTree(Double.POSITIVE_INFINITY))) {
      var invalid = base.deepCopy();
      ((ObjectNode) invalid.path("states").get(0)).set("value", wrong);
      assertThrows(ApiException.class, () -> validateBinding(invalid));
    }
    for (String label : List.of(" ", "\ufeff", "line\nfeed", "🙂".repeat(41))) {
      var invalid = base.deepCopy();
      ((ObjectNode) invalid.path("states").get(0)).put("label", label);
      assertThrows(ApiException.class, () -> validateBinding(invalid));
    }
    var longString = binding("status", "string");
    ((ObjectNode) longString.path("states").get(0)).put("value", "🙂".repeat(501));
    assertThrows(ApiException.class, () -> validateBinding(longString));
    var unknownRule = base.deepCopy();
    ((ObjectNode) unknownRule.path("states").get(0)).put("script", "forbidden");
    assertThrows(ApiException.class, () -> validateBinding(unknownRule));
  }

  @Test void pipeGeometryAndFinalReferencesAreValidatedWithoutRewritingNodes() {
    var device = node("device");
    var pipe = pipe("water", "device");
    var before = pipe.deepCopy();
    contracts.validate("CanvasPatch", patch(0, device, pipe));
    contracts.canvasNode(pipe);
    ProcessControls.canvas(contracts, List.of(device, pipe));
    assertEquals(before, pipe);
    assertThrows(ApiException.class, () -> ProcessControls.canvas(contracts, List.of(pipe)));
    assertThrows(ApiException.class, () -> ProcessControls.canvas(contracts, List.of(pipe("device", null), pipe)));
    for (String field : List.of("width", "speed")) {
      var invalid = pipe.deepCopy();
      ((ObjectNode) invalid.path("props")).put(field, -1);
      assertThrows(ApiException.class, () -> contracts.canvasNode(invalid));
    }
    var badCoordinate = pipe.deepCopy();
    ((ObjectNode) badCoordinate.path("props").path("source")).put("x", 16385);
    assertThrows(ApiException.class, () -> contracts.canvasNode(badCoordinate));
    var unknownProp = pipe.deepCopy();
    ((ObjectNode) unknownProp.path("props")).put("callback", "forbidden");
    assertThrows(ApiException.class, () -> contracts.canvasNode(unknownProp));
    var resources = pipe.deepCopy();
    resources.putArray("resourceRefs").add("external");
    assertThrows(ApiException.class, () -> contracts.canvasNode(resources));
    var click = pipe.deepCopy();
    click.set("interaction", Json.obj("clickActions", List.of()));
    assertThrows(ApiException.class, () -> contracts.canvasNode(click));
    click.set("interaction", Json.obj("clickActions", List.of(), "hiddenInPreview", true, "visibilityGroup", "process"));
    contracts.canvasNode(click);
    ((ObjectNode) click.path("interaction")).set("clickActions", Json.M.valueToTree(List.of(Json.obj("type", "message", "title", "", "text", "Forbidden"))));
    assertThrows(ApiException.class, () -> contracts.canvasNode(click));
    var many = pipe.deepCopy();
    var waypoints = ((ObjectNode) many.path("props")).putArray("waypoints");
    for (int i = 0; i < 32; i++) waypoints.add(Json.obj("x", i, "y", 0));
    contracts.canvasNode(many);
    waypoints.add(Json.obj("x", 32, "y", 0));
    assertThrows(ApiException.class, () -> contracts.canvasNode(many));
  }

  @Test void combinedPointTypesAndBudgetUseTheFinalDocument() {
    var bound = node("first");
    bound.set("stateBinding", binding("temperature", "number"));
    var second = node("second");
    second.set("stateBinding", binding("temperature", "string"));
    assertThrows(ApiException.class, () -> ProcessControls.canvas(contracts, List.of(bound, second)));
    second.remove("stateBinding");
    second.set("iotBinding", Json.obj("pointKey", "temperature", "valueType", "string", "staleAfterSeconds", 30));
    assertThrows(ApiException.class, () -> ProcessControls.canvas(contracts, List.of(bound, second)));
    var nodes = new ArrayList<JsonNode>();
    for (int i = 0; i < 500; i++) {
      var entry = node("n" + i);
      entry.set("stateBinding", binding("point " + i, "number"));
      nodes.add(entry);
    }
    ProcessControls.canvas(contracts, nodes);
    nodes.add(bound);
    assertThrows(ApiException.class, () -> ProcessControls.canvas(contracts, nodes));
    nodes.set(500, nodes.get(0).deepCopy());
    ProcessControls.bindings(contracts, nodes); // Repeated keys count once, without a device-count limit.
  }

  @Test void fluidRulesSurviveSettingsOnlySaveAndPublicProjection() throws Exception {
    JsonNode fixture;
    try (var stream = getClass().getResourceAsStream("/fluid-validation-cases.json")) { fixture = Json.M.readTree(stream); }
    var fluid = (ObjectNode) fixture.path("base").deepCopy();
    fluid.set("stateBinding", binding("flow", "boolean"));
    var fluids = List.of(fluid);
    var saved = contracts.sceneStorageAfterPatch(Documents.settings(contracts), Json.obj("fluids", fluids));
    var next = contracts.sceneStorageAfterPatch(saved, Json.obj("settings", Documents.settings(contracts).put("backgroundColor", "#112233")));
    assertEquals(Json.M.valueToTree(fluids), contracts.sceneFluidsFromStorage(next));
    assertFalse(contracts.sceneSettingsFromStorage(next).has("fluids"));
    var invalid = fluid.deepCopy().putNull("stateBinding");
    assertThrows(ApiException.class, () -> contracts.fluids(Json.M.valueToTree(List.of(invalid))));
    var other = fluid.deepCopy().put("id", "second-fluid");
    other.set("stateBinding", binding("flow", "number"));
    assertThrows(ApiException.class, () -> contracts.fluids(Json.M.valueToTree(List.of(fluid, other))));
  }
}
