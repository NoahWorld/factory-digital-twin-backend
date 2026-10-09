package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.*;

class TwinDriveRuntimeTest {
  final Auth.User user = new Auth.User("editor", "tenant", "editor@example.invalid", "editor", "Editor", "delivery_manager", true, true);
  final Projects projects = mock(Projects.class);
  final TwinDriveDocuments documents = mock(TwinDriveDocuments.class);
  final ApiMotionSources sources = mock(ApiMotionSources.class);
  final TwinDriveRuntime runtime = new TwinDriveRuntime(TwinDriveControllerTest.proxied(documents), projects, sources);
  ObjectNode document;
  @BeforeEach void setup() {
    document = Json.obj("projectId", "scene", "revision", 1, "config", new TwinDriveDocumentsTest().config(), "editable", true);
    when(documents.read(user, "scene")).thenAnswer(call -> document.deepCopy());
    when(documents.stored("tenant", "scene")).thenAnswer(call -> document.deepCopy());
  }
  @Test void observationUsesPrivateStoredConfigWithoutCollectingOrCommanding() {
    var expected = Json.obj("type", "snapshot", "source", "api", "sequence", 3);
    when(sources.snapshot(eq("tenant"), eq("scene"), any())).thenReturn(expected);
    assertEquals(expected, runtime.snapshot(user, "scene"));
    verify(documents).read(user, "scene"); verify(documents).stored("tenant", "scene");
    verify(sources, never()).collect();
  }
  @Test void subscriptionsRequireCurrentRevisionAndNoLegacyTopics() {
    var request = Json.obj("type", "subscribe", "expectedRevision", 1, "topics", List.of());
    assertEquals("subscribed", runtime.subscribe(user, "scene", request).path("type").asText());
    assertThrows(ApiException.class, () -> runtime.subscribe(user, "scene", Json.obj("type", "subscribe", "expectedRevision", 1, "topics", List.of("old/topic"))));
    assertEquals("twin_revision_conflict", assertThrows(ApiException.class, () -> runtime.subscribe(user, "scene", request.deepCopy().put("expectedRevision", 0))).code);
    verifyNoInteractions(sources);
  }
  @Test void retiredDocumentsRemainReadableButRuntimeAndCommandsAreGone() {
    ((ObjectNode) document.path("config")).put("source", "simulator");
    assertEquals("legacy_simulation_removed", assertThrows(ApiException.class, () -> runtime.snapshot(user, "scene")).code);
    assertEquals(410, assertThrows(ApiException.class, () -> runtime.command(user, "scene", Json.obj("type", "command"))).status);
    verifyNoInteractions(sources);
  }
  @Test void revokedProjectPermissionsStopBeforeReadingPrivateSources() {
    when(documents.read(user, "scene")).thenThrow(new ApiException(404, "project_not_found", "Inaccessible"));
    assertEquals("project_not_found", assertThrows(ApiException.class, () -> runtime.snapshot(user, "scene")).code);
    verify(documents, never()).stored(anyString(), anyString()); verifyNoInteractions(sources);
  }
}
