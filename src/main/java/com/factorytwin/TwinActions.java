package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Validates declarative actions against the final document inside the save transaction. */
final class TwinActions {
  private TwinActions() {}

  static void validate(Projects p, Contracts contracts, Auth.User user, String projectId,
      String kind, String linkedProjectId, List<JsonNode> items) {
    Map<String, JsonNode> own = index(items);
    Map<String, Map<String, JsonNode>> documents = new HashMap<>();
    documents.put(projectId, own);
    Map<String, String> projectTypes = new HashMap<>();
    projectTypes.put(projectId, kind.equals("canvas") ? "2d" : "3d");
    Map<String, String> links = new HashMap<>();
    if (kind.equals("scene")) links.put(projectId, linkedProjectId);
    Set<String> assets = new HashSet<>();
    for (JsonNode item : items) {
      boolean canvas = kind.equals("canvas");
      JsonNode actions;
      if (canvas) {
        if (!item.has("interaction")) continue;
        contracts.validate("CanvasNodeInteraction", item.path("interaction"));
        if (is3d(item)) fail(projectId, item.path("id").asText(), -1, "3D components cannot define 2D click interactions.");
        actions = item.path("interaction").path("clickActions");
      } else {
        if (!item.has("clickActions")) continue;
        // Validate as part of an instance so the array bound also applies to stored data.
        contracts.validate("StandaloneSceneInstance", item);
        actions = item.path("clickActions");
      }
      int index = -1;
      for (JsonNode action : actions) {
        index++;
        String sourceId = item.path("id").asText(), type = action.path("type").asText();
        if (type.equals("message")) continue;
        if (type.equals("focus-model")) {
          String target = action.path("projectId").asText();
          if (!canvas && !target.equals(projectId)) fail(projectId, sourceId, index, "A 3D model can only focus another model in its own scene.");
          requireProject(p, user, projectTypes, target, "3d", projectId, sourceId, index);
          Map<String, JsonNode> targetItems = loadItems(p, user, documents, target);
          if (!links.containsKey(target)) {
            var row = p.db.queryForMap("SELECT linked_project_id FROM documents WHERE tenant_id=? AND project_id=?", user.tenant(), target);
            links.put(target, (String) row.get("linked_project_id"));
          }
          boolean referenced = items.stream().anyMatch(node -> node.path("type").asText().equals("scene-3d") && node.path("props").path("sceneProjectId").asText().equals(target));
          if (canvas && !projectId.equals(links.get(target)) && !referenced) fail(projectId, sourceId, index, "Scene " + target + " is not linked to or referenced by this canvas.");
          String instanceId = action.path("instanceId").asText();
          if (!targetItems.containsKey(instanceId)) fail(projectId, sourceId, index, "Model instance " + instanceId + " does not exist in scene " + target + ".");
          continue;
        }
        String target = canvas ? projectId : linkedProjectId;
        if (target == null) fail(projectId, sourceId, index, "This action requires a linked 2D project.");
        requireProject(p, user, projectTypes, target, "2d", projectId, sourceId, index);
        if (type.equals("select-asset")) {
          String assetId = action.path("assetId").asText(), key = target + "/" + assetId;
          if (!assets.contains(key)) {
            Integer count = p.db.queryForObject("SELECT count(*) FROM assets WHERE tenant_id=? AND project_id=? AND asset_key=?", Integer.class, user.tenant(), target, assetId);
            if (count == null || count != 1) fail(projectId, sourceId, index, "Business asset " + assetId + " does not exist in 2D project " + target + ".");
            assets.add(key);
          }
          continue;
        }
        String nodeId = action.path("nodeId").asText();
        JsonNode node = loadItems(p, user, documents, target).get(nodeId);
        if (node == null) fail(projectId, sourceId, index, "Component " + nodeId + " does not exist in 2D project " + target + ".");
        if (type.equals("set-text") && !node.path("type").asText().equals("plain-text")) fail(projectId, sourceId, index, "Component " + nodeId + " is not a plain-text component.");
        if (type.equals("panel") && is3d(node)) fail(projectId, sourceId, index, "Component " + nodeId + " must be a pure 2D component.");
      }
    }
  }

  private static boolean is3d(JsonNode node) {
    return Set.of("scene-3d", "model-3d").contains(node.path("type").asText());
  }

  private static Map<String, JsonNode> index(List<JsonNode> items) {
    Map<String, JsonNode> result = new HashMap<>();
    for (JsonNode item : items) result.put(item.path("id").asText(), item);
    return result;
  }

  private static Map<String, JsonNode> loadItems(Projects p, Auth.User user,
      Map<String, Map<String, JsonNode>> cache, String target) {
    return cache.computeIfAbsent(target, id -> index(p.db.query(
        "SELECT body::text FROM document_items WHERE tenant_id=? AND project_id=?",
        (row, index) -> Json.parse(row.getString(1)), user.tenant(), id)));
  }

  private static void requireProject(Projects p, Auth.User user, Map<String, String> cache,
      String target, String type, String sourceProject, String sourceId, int index) {
    String actual = cache.computeIfAbsent(target, id -> p.access(user, id, false).path("projectType").asText());
    if (!actual.equals(type)) fail(sourceProject, sourceId, index, "Target project " + target + " must be " + type + ".");
  }

  private static void fail(String projectId, String itemId, int index, String message) {
    throw new ApiException(400, "invalid_twin_action_reference", "Project " + projectId + ", item " + itemId + ", clickActions[" + index + "]: " + message);
  }
}
