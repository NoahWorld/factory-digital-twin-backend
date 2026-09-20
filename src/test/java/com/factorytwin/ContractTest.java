package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.nio.file.*;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

class ContractTest {
  final Contracts c = new Contracts();

  private static com.fasterxml.jackson.databind.node.ObjectNode modelPatch() {
    return Json.obj(
        "expectedRevision", 0, "deleteNodeIds", List.of(), "upsertNodes", List.of(
            Json.obj("id", "legacy-model", "type", "model-3d", "x", 0, "y", 0,
                "width", 600, "height", 400, "zIndex", 0, "resourceRefs", List.of(),
                "dataBindingRefs", List.of(), "props",
                Json.obj("backgroundColor", "#071525", "autoRotate", false,
                    "rotationSpeed", .35, "showGrid", true, "animationSpeed", 1))));
  }

  @Test
  void modelValidationReportsItsOwnMissingFields() {
    var patch = modelPatch();
    c.normalizeCanvasPatch(patch);
    ((com.fasterxml.jackson.databind.node.ObjectNode) patch.path("upsertNodes").get(0).path("props"))
        .remove("modelInstances");
    var error = assertThrows(ApiException.class, () -> c.validate("CanvasPatch", patch));
    assertTrue(error.getMessage().contains("modelInstances"), error.getMessage());
    assertFalse(error.getMessage().contains("property 'animationSpeed'"), error.getMessage());
    assertFalse(error.getMessage().contains("accentColor"), error.getMessage());
  }

  @Test
  void legacyModelDefaultsPreserveExistingSettingsAndDoNotShareMutableValues() {
    var patch = modelPatch();
    var props = (com.fasterxml.jackson.databind.node.ObjectNode) patch.path("upsertNodes").get(0).path("props");
    props.put("animationSpeed", 2);
    c.normalizeCanvasPatch(patch);
    c.validate("CanvasPatch", patch);
    c.canvasNode(patch.path("upsertNodes").get(0));
    assertEquals(2, props.path("animationSpeed").asInt());
    assertFalse(props.path("autoRotate").asBoolean());
    assertEquals(Json.parse("[]"), props.path("modelInstances"));
    assertEquals("original", props.path("presentation").path("shellMode").asText());
    ((com.fasterxml.jackson.databind.node.ObjectNode) props.path("presentation")).put("shellMode", "hidden");
    var second = modelPatch();
    c.normalizeCanvasPatch(second);
    assertEquals("original", second.path("upsertNodes").get(0).path("props").path("presentation").path("shellMode").asText());
  }

  @Test
  void legacyNormalizationNeverRepairsInvalidFieldsOrAcceptsUnknownProps() {
    for (String bad : List.of("\"oops\"", "null", "{}")) {
      var patch = modelPatch();
      ((com.fasterxml.jackson.databind.node.ObjectNode) patch.path("upsertNodes").get(0).path("props"))
          .set("modelInstances", Json.parse(bad));
      c.normalizeCanvasPatch(patch);
      var error = assertThrows(ApiException.class, () -> c.validate("CanvasPatch", patch));
      assertTrue(error.getMessage().contains("modelInstances"), error.getMessage());
    }
    var patch = modelPatch();
    var props = (com.fasterxml.jackson.databind.node.ObjectNode) patch.path("upsertNodes").get(0).path("props");
    props.put("unknownProperty", true);
    c.normalizeCanvasPatch(patch);
    var error = assertThrows(ApiException.class, () -> c.validate("CanvasPatch", patch));
    assertTrue(error.getMessage().contains("unknownProperty"), error.getMessage());
    assertFalse(error.getMessage().contains("animationSpeed"), error.getMessage());
    props.remove("unknownProperty");
    props.remove("backgroundColor");
    c.normalizeCanvasPatch(patch);
    assertThrows(ApiException.class, () -> c.validate("CanvasPatch", patch));
  }

  @Test
  void databaseTimestampsHaveAnExplicitUtcOffset() {
    var instant = java.time.Instant.parse("2026-09-17T08:12:00Z");
    assertEquals(instant.toString(), Json.timestamp(java.sql.Timestamp.from(instant)));
    assertEquals(
        instant.toString(),
        Json.timestamp(java.time.OffsetDateTime.parse("2026-09-17T16:12:00+08:00")));
    assertThrows(IllegalArgumentException.class, () -> Json.timestamp("2026-09-17 08:12:00"));
  }

  @Test
  void propsMustMatchComponentKind() {
    var b =
        Json.obj(
            "expectedRevision",
            0,
            "deleteNodeIds",
            java.util.List.of(),
            "upsertNodes",
            java.util.List.of(
                Json.obj(
                    "id",
                    "node-1",
                    "type",
                    "rectangle",
                    "x",
                    0,
                    "y",
                    0,
                    "width",
                    240,
                    "height",
                    160,
                    "zIndex",
                    0,
                    "props",
                    Json.obj(
                        "title",
                        "Wrong properties",
                        "categories",
                        java.util.List.of("A"),
                        "values",
                        java.util.List.of(1),
                        "unit",
                        "",
                        "color",
                        "#ffffff"),
                    "resourceRefs",
                    java.util.List.of(),
                    "dataBindingRefs",
                    java.util.List.of())));
    assertThrows(ApiException.class, () -> c.validate("CanvasPatch", b));
  }

  @Test
  void sourcePathsAreBounded() {
    assertEquals(
        12,
        SourceClient.value(Json.parse("{\"devices\":[{\"value\":12}]}"), "$.devices[0].value")
            .asInt());
    assertThrows(ApiException.class, () -> SourceClient.pathSyntax("$.devices[?(@.value>0)]"));
    assertThrows(ApiException.class, () -> SourceClient.pathSyntax("$..value"));
  }

  @Test
  void javascriptLinksRejected() {
    assertThrows(
        ApiException.class, () -> Contracts.appearance(Json.obj("href", "javascript:alert(1)")));
    Contracts.appearance(Json.obj("href", "https://example.com"));
  }

  @Test
  void externalModelFilesRejected() throws Exception {
    Path p = Files.createTempFile("external-", ".gltf");
    try {
      Files.writeString(
          p,
          "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"https://example.com/file.bin\"}]}");
      assertThrows(ApiException.class, () -> FileInspection.inspect(p, "model", "test.gltf"));
    } finally {
      Files.delete(p);
    }
  }

  @Test
  void mismatchedFileSignatureRejected() throws Exception {
    Path p = Files.createTempFile("fake-", ".png");
    try {
      Files.writeString(p, "<svg onload='alert(1)'/>");
      assertThrows(ApiException.class, () -> FileInspection.inspect(p, "image", "fake.png"));
    } finally {
      Files.delete(p);
    }
  }

  @Test
  void cursorOrderingUsesBothParts() {
    assertTrue(Realtime.compare("1000-10", "1000-9") > 0);
    assertTrue(Realtime.compare("999-0", "1000-0") < 0);
  }

  @Test
  void canvasCoverExistsBeforeTheFirstSaveAndEscapesProjectNames() throws Exception {
    String svg =
        ProjectCovers.render(
            "空白 <项目> & \"一号\"", "2d", Documents.theme(), List.of());
    assertTrue(svg.contains("2D CANVAS"));
    assertTrue(svg.contains("空白 &lt;项目&gt; &amp; &quot;一号&quot;"));
    assertFalse(svg.contains("<项目>"));
    assertWellFormedXml(svg);
  }

  @Test
  void sceneCoverReflectsSavedInstances() throws Exception {
    var pump =
        Json.obj(
            "id",
            "pump-1",
            "label",
            "循环水泵",
            "modelAssetId",
            "builtin:water-pump",
            "visible",
            true,
            "renderMode",
            "interactive",
            "transform",
            Json.obj(
                "position",
                List.of(12, 0, -8),
                "rotation",
                List.of(0, 0, 0),
                "scale",
                List.of(2, 1, 3)),
            "appearance",
            Json.obj("color", "#ffbd59", "opacity", .8));
    var tank =
        Json.obj(
            "id",
            "tank-1",
            "label",
            "沉淀池",
            "modelAssetId",
            "builtin:clarifier-basin",
            "visible",
            true,
            "renderMode",
            "interactive",
            "transform",
            Json.obj(
                "position",
                List.of(-5, 0, 4),
                "rotation",
                List.of(0, 0, 0),
                "scale",
                List.of(3, 1, 3)));
    String empty = ProjectCovers.render("车间", "3d", Documents.settings(), List.of());
    String saved = ProjectCovers.render("车间", "3d", Documents.settings(), List.of(pump, tank));
    assertNotEquals(empty, saved);
    assertTrue(saved.contains("3D SCENE"));
    assertTrue(saved.contains("3D 场景概览"));
    assertTrue(saved.contains("循环水泵"));
    assertTrue(saved.contains("沉淀池"));
    assertTrue(saved.contains("data-scene-kind=\"pump\""));
    assertTrue(saved.contains("data-scene-kind=\"tank\""));
    assertTrue(saved.contains("#ffbd59"));
    assertEquals(2, ProjectCovers.RENDERER_VERSION);
    assertWellFormedXml(saved);
  }

  private static void assertWellFormedXml(String value) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setExpandEntityReferences(false);
    factory.newDocumentBuilder().parse(new InputSource(new StringReader(value)));
  }
}
