package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class IotBindingContractTest {
  final Contracts contracts = new Contracts();

  static ObjectNode binding(String key, String type) {
    return Json.obj("pointKey", key, "valueType", type, "staleAfterSeconds", 60);
  }

  static ObjectNode node(String id, String type) {
    ObjectNode props = Json.obj("textColor", "#ffffff", "accentColor", "#0088ff",
        "fillColor", "#071525", "borderColor", "#224466");
    if (type.equals("plain-text")) {
      props.put("borderRadius", 4).put("text", "Original static value").put("align", "left")
          .put("fontSize", 24).put("fontWeight", 400).put("scrollMode", "none")
          .put("scrollDuration", 10);
    } else {
      props.put("title", "Temperature").put("sample", false).put("unit", "℃")
          .put("subtitle", "Static configuration");
      if (type.equals("metric-card")) props.put("value", "25").put("icon", "temperature");
      else props.put("value", 25).put("maximum", 100);
    }
    return Json.obj("id", id, "type", type, "x", 0, "y", 0, "width", 300,
        "height", 240, "zIndex", 0, "props", props, "resourceRefs", List.of(),
        "dataBindingRefs", List.of());
  }

  static ObjectNode patch(long revision, JsonNode... nodes) {
    return Json.obj("expectedRevision", revision, "upsertNodes", Arrays.asList(nodes),
        "deleteNodeIds", List.of());
  }

  private void validate(ObjectNode node) {
    var patch = patch(0, node);
    contracts.normalizeCanvasPatch(patch);
    contracts.validate("CanvasPatch", patch);
    contracts.canvasNode(node);
    contracts.canvasIotBindings(List.of(node));
  }

  @Test void optionalBindingsKeepLegacyNodesUnchangedAndAcceptSupportedTypes() {
    for (String component : List.of("plain-text", "metric-card", "radial-gauge")) {
      var node = node("node-1", component);
      var original = node.deepCopy();
      validate(node);
      assertEquals(original, node);
      assertFalse(node.has("iotBinding"));
      for (String type : List.of("number", "string", "boolean")) {
        node.set("iotBinding", binding("inletTemperature", type));
        if (component.equals("radial-gauge") && !type.equals("number")) {
          assertThrows(ApiException.class, () -> validate(node));
        } else {
          assertDoesNotThrow(() -> validate(node));
          assertEquals(original.path("props"), node.path("props"));
        }
      }
    }
  }

  @Test void bindingRejectsNullWrongTypesUnknownFieldsMissingFieldsAndOutOfRangeValues() {
    List<JsonNode> invalid = new ArrayList<>();
    for (String json : List.of("null", "[]", "\"point\"", "true", "1", "{}"))
      invalid.add(Json.parse(json));
    invalid.add(binding("p", "integer"));
    invalid.add(binding("p", "number").put("sourceUrl", "https://example.invalid"));
    for (String field : List.of("pointKey", "valueType", "staleAfterSeconds")) {
      ObjectNode missing = binding("p", "number");
      missing.remove(field);
      invalid.add(missing);
    }
    for (String bad : List.of("null", "\"60\"", "0", "-1", "1.5", "86401")) {
      ObjectNode value = binding("p", "number");
      value.set("staleAfterSeconds", Json.parse(bad));
      invalid.add(value);
    }
    for (JsonNode value : invalid) {
      var node = node("invalid", "plain-text");
      node.set("iotBinding", value);
      assertThrows(ApiException.class, () -> validate(node), value.toString());
    }
    for (int age : List.of(1, 86400)) {
      var node = node("boundary", "plain-text");
      node.set("iotBinding", binding("p", "number").put("staleAfterSeconds", age));
      assertDoesNotThrow(() -> validate(node));
    }
  }

  @Test void pointKeyUsesTheSameWhitespaceControlAndUtf16LimitsAsTheSharedParser() {
    var node = node("key-test", "plain-text");
    for (String key : List.of("", " p", "p ", "p\n", "p\u0000q", "p\u007fq", "p\u00a0",
        "\u3000p", "p\ufeff", "p".repeat(201), "😀".repeat(101))) {
      node.set("iotBinding", binding(key, "number"));
      assertThrows(ApiException.class, () -> validate(node), "Accepted key: " + key);
    }
    for (String key : List.of("p", "inlet temperature", "锅炉/入口温度", "p".repeat(200), "😀".repeat(100))) {
      node.set("iotBinding", binding(key, "number"));
      assertDoesNotThrow(() -> validate(node), key);
    }
  }

  @Test void bindingIsNotPermittedOnOtherComponentKinds() {
    var node = node("shape", "plain-text");
    node.put("type", "rectangle");
    node.set("props", Json.obj("fillColor", "#071525", "borderColor", "#224466", "borderWidth", 1,
        "borderRadius", 4, "opacity", 1));
    // Confirm the fixture itself is valid before testing the new optional field.
    validate(node);
    node.set("iotBinding", binding("p", "number"));
    assertThrows(ApiException.class, () -> validate(node));
  }

  @Test void wholeCanvasDeduplicatesPointsAndRejectsConflictingTypesOrMoreThan500Keys() {
    List<JsonNode> nodes = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      var node = node("node-" + i, "plain-text");
      node.set("iotBinding", binding("point-" + i, "number"));
      nodes.add(node);
    }
    var duplicate = node("duplicate", "metric-card");
    duplicate.set("iotBinding", binding("point-0", "number").put("staleAfterSeconds", 5));
    nodes.add(duplicate);
    assertDoesNotThrow(() -> contracts.canvasIotBindings(nodes));
    duplicate.set("iotBinding", binding("point-0", "string"));
    var conflict = assertThrows(ApiException.class, () -> contracts.canvasIotBindings(nodes));
    assertEquals("invalid_canvas_iot_binding", conflict.code);
    assertTrue(conflict.getMessage().contains("point-0"));
    duplicate.set("iotBinding", binding("point-500", "number"));
    assertThrows(ApiException.class, () -> contracts.canvasIotBindings(nodes));
  }
}
