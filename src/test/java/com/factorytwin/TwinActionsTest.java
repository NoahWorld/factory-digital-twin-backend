package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

class TwinActionsTest {
  final Contracts contracts = new Contracts();
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Projects projects = spy(new Projects(db, mock(Auth.class), mock(TransactionTemplate.class), mock(ProjectCovers.class), contracts));
  final Auth.User user = new Auth.User("user", "tenant", "user@example.invalid", "user", "User", "delivery_manager", true, true);

  ObjectNode message() { return Json.obj("type", "message", "title", "设备", "text", "设备已选中\n查看详情"); }
  ObjectNode node(String id, String type) { return Json.obj("id", id, "type", type, "props", Json.obj()); }
  ObjectNode trigger(JsonNode... actions) {
    ObjectNode node = node("trigger", "button");
    node.set("interaction", Json.obj("clickActions", List.of(actions), "hiddenInPreview", false));
    return node;
  }
  ObjectNode instance() {
    return Json.obj("id", "model", "assetId", null, "modelAssetId", "builtin:aqua-helix-hd-v1", "label", "Pump", "renderMode", "interactive", "sortOrder", 0, "visible", true,
        "transform", Json.obj("position", List.of(0,0,0), "rotation", List.of(0,0,0), "scale", List.of(1,1,1)));
  }
  void validateCanvas(JsonNode... nodes) { TwinActions.validate(projects, contracts, user, "canvas", "canvas", null, List.of(nodes)); }

  @Test void generatedSchemaEnforcesActionsAndKeepsMissingVersusEmpty() {
    var actions = Json.obj("clickActions", List.of(message()), "hiddenInPreview", false);
    contracts.validate("CanvasNodeInteraction", actions);
    var item = instance();
    contracts.validate("StandaloneSceneInstance", item);
    assertFalse(item.has("clickActions"));
    item.set("clickActions", Json.M.createArrayNode());
    contracts.validate("StandaloneSceneInstance", item);
    assertTrue(item.has("clickActions"));
    item.putNull("clickActions");
    assertThrows(ApiException.class, () -> contracts.validate("StandaloneSceneInstance", item));
    for (JsonNode bad : List.of(
        Json.obj("type", "script", "text", "alert(1)"),
        Json.obj("type", "message", "title", "", "text", ""),
        Json.obj("type", "message", "title", "x".repeat(81), "text", "text"),
        Json.obj("type", "message", "title", "", "text", "x".repeat(2001)),
        Json.obj("type", "message", "title", "", "text", "bad\u0000"),
        Json.obj("type", "focus-model", "projectId", "scene\n", "instanceId", "model"),
        Json.obj("type", "message", "title", "", "text", "text", "script", "alert(1)"))) {
      actions.set("clickActions", Json.M.valueToTree(List.of(bad)));
      assertThrows(ApiException.class, () -> contracts.validate("CanvasNodeInteraction", actions), bad.toString());
    }
    actions.set("clickActions", Json.M.valueToTree(Collections.nCopies(9, message())));
    assertThrows(ApiException.class, () -> contracts.validate("CanvasNodeInteraction", actions));
    actions.set("clickActions", Json.M.valueToTree(Collections.nCopies(8, message())));
    contracts.validate("CanvasNodeInteraction", actions);
  }

  @Test void mergedCanvasRejectsDeletedAndNon2dPanelTargets() {
    var show = trigger(Json.obj("type", "panel", "nodeId", "panel", "operation", "show"));
    validateCanvas(show, node("panel", "plain-text"));
    var deleted = assertThrows(ApiException.class, () -> validateCanvas(show));
    assertTrue(deleted.getMessage().contains("trigger"));
    assertTrue(deleted.getMessage().contains("clickActions[0]"));
    assertThrows(ApiException.class, () -> validateCanvas(show, node("panel", "scene-3d")));
    assertThrows(ApiException.class, () -> validateCanvas(show, node("panel", "model-3d")));
    var text = trigger(Json.obj("type", "set-text", "nodeId", "panel", "text", "新内容"));
    validateCanvas(text, node("panel", "plain-text"));
    assertThrows(ApiException.class, () -> validateCanvas(text, node("panel", "button")));
    var invalidSource = trigger(); invalidSource.put("type", "scene-3d");
    assertThrows(ApiException.class, () -> validateCanvas(invalidSource));
    validateCanvas(trigger());
  }

  @Test void sceneActionsRequireLinkedCanvasAndSameSceneFocus() {
    var item = instance();
    item.set("clickActions", Json.M.valueToTree(List.of(Json.obj("type", "focus-model", "projectId", "scene", "instanceId", "model"))));
    TwinActions.validate(projects, contracts, user, "scene", "scene", null, List.of(item));
    ((ObjectNode) item.path("clickActions").get(0)).put("instanceId", "deleted");
    assertThrows(ApiException.class, () -> TwinActions.validate(projects, contracts, user, "scene", "scene", null, List.of(item)));
    ((ObjectNode) item.path("clickActions").get(0)).put("projectId", "other");
    assertThrows(ApiException.class, () -> TwinActions.validate(projects, contracts, user, "scene", "scene", null, List.of(item)));
    item.set("clickActions", Json.M.valueToTree(List.of(Json.obj("type", "panel", "nodeId", "panel", "operation", "show"))));
    assertThrows(ApiException.class, () -> TwinActions.validate(projects, contracts, user, "scene", "scene", null, List.of(item)));
    doReturn(Json.obj("projectType", "2d")).when(projects).access(user, "canvas", false);
    doReturn(List.of(node("panel", "plain-text"))).when(db).query(contains("document_items"), any(RowMapper.class), eq("tenant"), eq("canvas"));
    TwinActions.validate(projects, contracts, user, "scene", "scene", "canvas", List.of(item));
  }

  @Test void focusRequiresReadPermissionAndAnActualProjectRelationship() {
    var focus = trigger(Json.obj("type", "focus-model", "projectId", "scene", "instanceId", "model"));
    doThrow(new ApiException(403, "forbidden", "No project permission.")).when(projects).access(user, "scene", false);
    assertThrows(ApiException.class, () -> validateCanvas(focus));
    doReturn(Json.obj("projectType", "3d")).when(projects).access(user, "scene", false);
    doReturn(List.of(instance())).when(db).query(contains("document_items"), any(RowMapper.class), eq("tenant"), eq("scene"));
    when(db.queryForMap(contains("FROM documents"), eq("tenant"), eq("scene"))).thenReturn(Map.of("linked_project_id", "unrelated"));
    assertThrows(ApiException.class, () -> validateCanvas(focus));
    var sceneNode = node("scene-node", "scene-3d");
    sceneNode.set("props", Json.obj("sceneProjectId", "scene"));
    validateCanvas(focus, sceneNode);
    when(db.queryForMap(contains("FROM documents"), eq("tenant"), eq("scene"))).thenReturn(Map.of("linked_project_id", "canvas"));
    validateCanvas(focus);
    ((ObjectNode) focus.path("interaction").path("clickActions").get(0)).put("instanceId", "missing");
    assertThrows(ApiException.class, () -> validateCanvas(focus));
  }

  @Test void businessAssetIdIsScopedToThe2dAssetLedger() {
    var select = trigger(Json.obj("type", "select-asset", "assetId", "pump-1"));
    when(db.queryForObject(contains("FROM assets"), eq(Integer.class), eq("tenant"), eq("canvas"), eq("pump-1"))).thenReturn(0);
    assertThrows(ApiException.class, () -> validateCanvas(select));
    when(db.queryForObject(contains("FROM assets"), eq(Integer.class), eq("tenant"), eq("canvas"), eq("pump-1"))).thenReturn(1);
    validateCanvas(select);
  }
}
