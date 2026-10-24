package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Authored state rules and pipe references; runtime values never enter stored configuration. */
final class ProcessControls {
  private ProcessControls() {}

  static void stateBinding(Contracts contracts, JsonNode binding, String owner) {
    contracts.validate("StateBinding", binding);
    JsonNode limits = contracts.processControlLimits;
    // JSON Schema counts Unicode code points, whereas the authoring contract counts UTF-16 units.
    Json.require(binding.path("pointKey").asText().length() <= limits.path("maximumPointKeyLength").asInt(),
        owner + " pointKey exceeds the UTF-16 length limit.");
    String valueType = binding.path("valueType").asText();
    for (JsonNode state : binding.path("states")) {
      Json.require(state.path("label").asText().length() <= limits.path("maximumLabelLength").asInt(),
          owner + " state label exceeds the UTF-16 length limit.");
      JsonNode value = state.path("value");
      boolean matches = switch (valueType) {
        case "number" -> value.isNumber() && Double.isFinite(value.asDouble());
        case "boolean" -> value.isBoolean();
        case "string" -> value.isTextual() && value.asText().length() >= 1
            && value.asText().length() <= limits.path("maximumStringValueLength").asInt();
        default -> false;
      };
      Json.require(matches, owner + " state comparison value must match point valueType.");
      Json.require(valueType.equals("number") || state.path("operator").asText().equals("eq"),
          owner + " non-numeric state rules only support equality.");
      Json.require(Double.isFinite(state.path("speed").asDouble()), owner + " state speed must be finite.");
    }
  }

  static void node(Contracts contracts, JsonNode node) {
    String owner = "Node " + node.path("id").asText();
    if (node.has("stateBinding")) stateBinding(contracts, node.get("stateBinding"), owner);
    if (!node.path("type").asText().equals("process-pipe")) return;
    JsonNode props = node.path("props");
    contracts.validate("PipeProps", props);
    Json.require(props.path("label").asText().length() <= contracts.processControlLimits.path("maximumLabelLength").asInt(),
        owner + " pipe label exceeds the UTF-16 length limit.");
    if (node.has("interaction")) {
      contracts.validate("CanvasNodeInteraction", node.get("interaction"));
      Json.require(node.path("interaction").path("clickActions").isEmpty(), owner + " pipes cannot define click actions.");
    }
    for (String field : List.of("width", "speed"))
      Json.require(Double.isFinite(props.path(field).asDouble()), owner + " pipe " + field + " must be finite.");
    for (String field : List.of("source", "target")) point(props.path(field), owner + " pipe " + field);
    for (JsonNode waypoint : props.path("waypoints")) point(waypoint, owner + " pipe waypoint");
  }

  private static void point(JsonNode point, String owner) {
    for (String coordinate : List.of("x", "y"))
      Json.require(Double.isFinite(point.path(coordinate).asDouble()), owner + " " + coordinate + " must be finite.");
  }

  static void bindings(Contracts contracts, Iterable<JsonNode> items) {
    Map<String, String> types = new HashMap<>();
    for (JsonNode item : items) {
      String owner = item.path("id").asText();
      if (item.has("stateBinding")) stateBinding(contracts, item.get("stateBinding"), owner);
      // Include the existing optional display binding when present, without depending on its persistence extension.
      for (String field : List.of("stateBinding", "iotBinding")) {
        if (!item.has(field)) continue;
        JsonNode binding = item.get(field);
        String key = binding.path("pointKey").asText(), valueType = binding.path("valueType").asText();
        String prior = types.putIfAbsent(key, valueType);
        Json.require(prior == null || prior.equals(valueType),
            "Point " + key + " has conflicting valueTypes in the final document at " + owner + ".");
        Json.require(types.size() <= contracts.processControlLimits.path("maximumPoints").asInt(),
            "The final document exceeds its point binding budget.");
      }
    }
  }

  static void canvas(Contracts contracts, List<JsonNode> nodes) {
    Map<String, JsonNode> byId = new HashMap<>();
    for (JsonNode node : nodes) byId.put(node.path("id").asText(), node);
    bindings(contracts, nodes);
    for (JsonNode node : nodes) {
      if (!node.path("type").asText().equals("process-pipe")) continue;
      node(contracts, node);
      for (String endpoint : List.of("source", "target")) {
        JsonNode targetId = node.path("props").path(endpoint).path("nodeId");
        if (targetId.isNull()) continue;
        JsonNode target = byId.get(targetId.asText());
        Json.require(target != null && !target.path("type").asText().equals("process-pipe"),
            "Pipe " + node.path("id").asText() + " " + endpoint + " references missing or pipe component " + targetId.asText() + ".");
      }
    }
  }
}
