package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class SceneExtensionsContractTest {
  final Contracts contracts = new Contracts();

  ObjectNode decoration() {
    return Json.obj("id","tree-1","label","乔木","kind","tree","visible",true,
        "transform",Json.obj("position",List.of(0,0,0),"rotation",List.of(0,0,0),"scale",List.of(1,1,1)),
        "color","#4c8b53","accentColor","#8fbd69","seed",0);
  }
  ObjectNode alarm() {
    return Json.obj("id","rule-1","label","高温","enabled",false,
        "source",Json.obj("projectId","scene","assetId","pump-1","metricKey","temperature"),
        "target",Json.obj("instanceId","instance-1","modelAssetId","model-1","nodeName","Room"),
        "condition",Json.obj("operator","gt","value",80),"color","#ff0000");
  }
  ObjectNode map() {
    return Json.obj("version",1,"id","map-1","label","厂区","visible",true,
        "coordinateSystem","local","width",50,"outlineColor","#ffffff",
        "transform",Json.obj("position",List.of(0,0,0),"rotation",List.of(0,0,0),"scale",List.of(1,1,1)),
        "features",List.of(Json.obj("id","zone-1","label","一号区","height",5,"color","#555555",
            "polygons",List.of(Json.obj("outer",List.of(List.of(0,0),List.of(10,0),List.of(10,10),List.of(0,10),List.of(0,0)),
                "holes",List.of())))));
  }

  @Test void storageOmitClearAndLegacyErrors() {
    ObjectNode legacy=Documents.settings(contracts);
    ObjectNode patch=Json.obj("expectedRevision",0,"decorations",List.of(decoration()),
        "roomAlarms",List.of(alarm()),"staticMap",map());
    contracts.normalizeScenePatch(patch);
    assertDoesNotThrow(()->contracts.validate("StandaloneScenePatch",patch));
    assertTrue(SceneExtensions.fromStorage(legacy,"decorations").isEmpty());
    assertTrue(SceneExtensions.fromStorage(legacy,"roomAlarms").isEmpty());
    assertTrue(SceneExtensions.fromStorage(legacy,"staticMap").isNull());
    ObjectNode stored=contracts.sceneStorageAfterPatch(legacy,Json.obj(
        "decorations",List.of(decoration()),"roomAlarms",List.of(alarm()),"staticMap",map()));
    ObjectNode changed=contracts.sceneStorageAfterPatch(stored,Json.obj("settings",legacy.deepCopy().put("showGrid",false)));
    for (String field:SceneExtensions.FIELDS) assertEquals(stored.path(field),changed.path(field));
    ObjectNode publicSettings=contracts.sceneSettingsFromStorage(changed);
    for (String field:SceneExtensions.FIELDS) assertFalse(publicSettings.has(field));
    ObjectNode cleared=contracts.sceneStorageAfterPatch(changed,Json.obj(
        "decorations",List.of(),"roomAlarms",List.of(),"staticMap",null));
    assertTrue(cleared.path("decorations").isEmpty());
    assertTrue(cleared.path("roomAlarms").isEmpty());
    assertTrue(cleared.path("staticMap").isNull());
    stored.putNull("roomAlarms");
    ApiException error=assertThrows(ApiException.class,()->SceneExtensions.fromStorage(stored,"roomAlarms"));
    assertEquals(500,error.status); assertEquals("invalid_scene_storage",error.code);
  }

  @Test void rejectsInvalidGeometryAndDistinctBudgets() {
    assertDoesNotThrow(()->StaticMapValidation.validate(map()));
    ObjectNode bad=(ObjectNode)map().deepCopy();
    bad.path("features").get(0).path("polygons").get(0).path("outer");
    ((com.fasterxml.jackson.databind.node.ArrayNode)bad.path("features").get(0).path("polygons").get(0).path("outer"))
        .set(4,Json.M.valueToTree(List.of(2,2)));
    assertThrows(ApiException.class,()->StaticMapValidation.validate(bad));
    ObjectNode river=decoration();
    river.put("kind","river");
    river.set("river",Json.obj("points",List.of(List.of(0,0,0),List.of(5,0,0)),
        "width",2,"speed",1,"opacity",.8,"playing",true));
    assertEquals(1,SceneExtensions.decorations(Json.M.valueToTree(List.of(river))).animated());
    ((ObjectNode)river.path("river")).put("width",100);
    assertDoesNotThrow(()->SceneExtensions.decorations(Json.M.valueToTree(List.of(river))));
    var b=StaticMapValidation.budget(map());
    assertEquals(1,b.instances()); assertTrue(b.meshes()>0 && b.triangles()>0);
  }

  @Test void mapHolesAndGeographicDateLineUseSavedGeometry() {
    ObjectNode withHole=map();
    var polygon=(ObjectNode)withHole.path("features").get(0).path("polygons").get(0);
    polygon.set("holes",Json.M.valueToTree(List.of(List.of(List.of(2,2),List.of(4,2),
        List.of(4,4),List.of(2,4),List.of(2,2)))));
    assertDoesNotThrow(()->StaticMapValidation.validate(withHole));
    polygon.set("holes",Json.M.valueToTree(List.of(List.of(List.of(9,9),List.of(12,9),
        List.of(12,12),List.of(9,12),List.of(9,9)))));
    assertThrows(ApiException.class,()->StaticMapValidation.validate(withHole));
    ObjectNode geographic=map(); geographic.put("coordinateSystem","wgs84");
    geographic.path("features").get(0).path("polygons").get(0);
    assertDoesNotThrow(()->StaticMapValidation.validate(geographic));
    var outer=(com.fasterxml.jackson.databind.node.ArrayNode)geographic.path("features").get(0).path("polygons").get(0).path("outer");
    outer.set(0,Json.M.valueToTree(List.of(-179,0)));
    outer.set(1,Json.M.valueToTree(List.of(179,0)));
    outer.set(2,Json.M.valueToTree(List.of(179,10)));
    outer.set(3,Json.M.valueToTree(List.of(-179,10)));
    outer.set(4,Json.M.valueToTree(List.of(-179,0)));
    assertThrows(ApiException.class,()->StaticMapValidation.validate(geographic));
  }

  @Test void mapMultipartAllowsEdgeContactAndPolygonInsideHole() {
    ObjectNode multipart=map();
    var feature=(ObjectNode)multipart.path("features").get(0);
    var polygons=(com.fasterxml.jackson.databind.node.ArrayNode)feature.path("polygons");
    polygons.add(Json.obj("outer",List.of(List.of(10,0),List.of(20,0),List.of(20,10),List.of(10,10),List.of(10,0)),"holes",List.of()));
    assertDoesNotThrow(()->StaticMapValidation.validate(multipart));
    polygons.set(1,Json.obj("outer",List.of(List.of(5,0),List.of(15,0),List.of(15,10),List.of(5,10),List.of(5,0)),"holes",List.of()));
    assertThrows(ApiException.class,()->StaticMapValidation.validate(multipart));
    ObjectNode nested=map();
    var first=(ObjectNode)nested.path("features").get(0).path("polygons").get(0);
    first.set("holes",Json.M.valueToTree(List.of(List.of(List.of(2,2),List.of(8,2),List.of(8,8),List.of(2,8),List.of(2,2)))));
    ((com.fasterxml.jackson.databind.node.ArrayNode)nested.path("features").get(0).path("polygons"))
        .add(Json.obj("outer",List.of(List.of(2,2),List.of(8,2),List.of(8,8),List.of(2,8),List.of(2,2)),"holes",List.of()));
    assertDoesNotThrow(()->StaticMapValidation.validate(nested));
  }

  @Test void mapRectangleOverlapOracleMatchesSharedGeometryBoundary() {
    for (int x=-2;x<=3;x++) for (int y=-2;y<=3;y++) for (boolean reverse:List.of(false,true)) {
      ObjectNode candidate=map();
      var polygons=(com.fasterxml.jackson.databind.node.ArrayNode)candidate.path("features").get(0).path("polygons");
      polygons.set(0,Json.obj("outer",List.of(List.of(0,0),List.of(2,0),List.of(2,2),List.of(0,2),List.of(0,0)),"holes",List.of()));
      List<List<Integer>> other=new ArrayList<>(List.of(List.of(x,y),List.of(x+2,y),List.of(x+2,y+2),List.of(x,y+2),List.of(x,y)));
      if (reverse) Collections.reverse(other);
      polygons.add(Json.obj("outer",other,"holes",List.of()));
      boolean overlap=Math.min(2,x+2)>Math.max(0,x) && Math.min(2,y+2)>Math.max(0,y);
      if (overlap) assertThrows(ApiException.class,()->StaticMapValidation.validate(candidate),x+","+y+","+reverse);
      else assertDoesNotThrow(()->StaticMapValidation.validate(candidate),x+","+y+","+reverse);
    }
  }

  @Test void disabledAlarmStillChecksFinalReferencesAndMetricType() {
    var db=mock(JdbcTemplate.class); var user=new Auth.User("u","tenant","u@example.invalid","u","U","delivery_manager",true,true);
    var projects=spy(new Projects(db,mock(Auth.class),mock(TransactionTemplate.class),mock(ProjectCovers.class),contracts));
    doReturn(Json.obj("projectType","3d")).when(projects).access(user,"scene",false);
    when(db.queryForList(contains("FROM assets a JOIN data_bindings b"),eq("tenant"),eq("scene"),eq("pump-1"),eq("temperature")))
        .thenReturn(List.of(Map.of("body",Json.obj("valueType","number").toString())));
    ObjectNode stored=Documents.settings(contracts); stored.set("roomAlarms",Json.M.valueToTree(List.of(alarm())));
    JsonNode instance=Json.obj("id","instance-1","modelAssetId","model-1");
    assertDoesNotThrow(()->SceneExtensions.validateReferences(projects,user,"scene",null,stored,List.of(instance)));
    assertThrows(ApiException.class,()->SceneExtensions.validateReferences(projects,user,"scene",null,stored,List.of()));
    ((ObjectNode)stored.path("roomAlarms").get(0).path("condition")).put("value","hot");
    assertThrows(ApiException.class,()->SceneExtensions.validateReferences(projects,user,"scene",null,stored,List.of(instance)));
  }

  @Test void sourceMutationGuardIncludesDisabledRulesAcrossTenantScenes() {
    var db=mock(JdbcTemplate.class);
    var user=new Auth.User("u","tenant","u@example.invalid","u","U","delivery_manager",true,true);
    var projects=new Projects(db,mock(Auth.class),mock(TransactionTemplate.class),mock(ProjectCovers.class),contracts);
    when(db.queryForObject(contains("settings->'roomAlarms'"),eq(Integer.class),eq("tenant"),anyString()))
        .thenReturn(1);
    ApiException error=assertThrows(ApiException.class,()->
        SceneExtensions.guardSourceChange(projects,user,"source-project","pump-1","temperature"));
    assertEquals(409,error.status);
    assertEquals("room_alarm_source_referenced",error.code);
    verify(db).queryForObject(contains("d.tenant_id=?"),eq(Integer.class),eq("tenant"),
        contains("\"metricKey\":\"temperature\""));
  }
}
