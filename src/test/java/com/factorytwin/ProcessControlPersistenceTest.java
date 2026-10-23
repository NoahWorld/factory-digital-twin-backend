package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

class ProcessControlPersistenceTest {
  final Contracts contracts = new Contracts();
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Auth.User user = new Auth.User("editor", "tenant", "editor@example.invalid", "editor",
      "Editor", "delivery_manager", true, true);
  final Projects projects = spy(new Projects(db, mock(Auth.class), mock(TransactionTemplate.class),
      mock(ProjectCovers.class), contracts));
  final Documents documents = new Documents(projects, contracts, projects.covers);
  final Map<String, JsonNode> stored = new LinkedHashMap<>();
  final Map<String, Object> documentRow = new HashMap<>();
  final Map<String, Object> versionRow = new HashMap<>();

  @SuppressWarnings("unchecked")
  ProcessControlPersistenceTest() {
    var project = Json.obj("id", "canvas", "name", "Canvas", "projectType", "2d",
        "projectRole", "owner", "documentRevision", 0);
    doReturn(project).when(projects).access(any(Auth.User.class), eq("canvas"), anyBoolean());
    doNothing().when(projects).lock(user, "canvas");
    documentRow.put("revision", 0L);
    documentRow.put("updated_at", Instant.parse("2026-10-15T00:00:00Z"));
    documentRow.put("settings", Documents.theme().toString());
    when(db.queryForMap(startsWith("SELECT * FROM documents"), eq("tenant"), eq("canvas")))
        .thenReturn(documentRow);
    when(db.query(startsWith("SELECT body::text FROM document_items"), any(RowMapper.class),
        eq("tenant"), eq("canvas"))).thenAnswer(call -> items());
    when(db.query(startsWith("SELECT body::text FROM document_items"), any(RowMapper.class),
        eq("tenant"), eq("canvas"), anyInt(), anyInt())).thenAnswer(call -> items());
    when(db.queryForObject(startsWith("SELECT count(*) FROM document_items"), eq(Integer.class),
        eq("tenant"), eq("canvas"))).thenAnswer(call -> stored.size());
    when(db.queryForObject(startsWith("SELECT revision FROM documents"), eq(Long.class),
        eq("tenant"), eq("canvas"))).thenAnswer(call -> documentRow.get("revision"));
    when(db.update(anyString(), any(Object[].class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.startsWith("INSERT INTO document_items")) {
        stored.put(call.getArgument(3), Json.parse(call.getArgument(5)));
      } else if (sql.startsWith("DELETE FROM document_items")) {
        stored.remove(call.getArgument(3));
      } else if (sql.startsWith("UPDATE documents SET revision")) {
        documentRow.put("revision", (long) documentRow.get("revision") + 1);
      } else if (sql.startsWith("INSERT INTO publication_versions")) {
        versionRow.put("id", call.getArgument(1));
        versionRow.put("document_revision", call.getArgument(4));
        versionRow.put("title", call.getArgument(5));
        versionRow.put("snapshot", call.getArgument(6));
        versionRow.put("created_by", call.getArgument(7));
        versionRow.put("created_at", Instant.parse("2026-10-15T00:00:00Z"));
      }
      return 1;
    });
    when(db.query(startsWith("SELECT id,body::text,created_at,updated_at FROM"), any(RowMapper.class),
        eq("tenant"), eq("canvas"))).thenReturn(List.of());
    when(db.query(startsWith("SELECT id,asset_id,source_id,body::text FROM data_bindings"),
        any(RowMapper.class), eq("tenant"), eq("canvas"))).thenReturn(List.of());
    when(db.queryForList(contains("FROM publication_current"), eq("tenant"), eq("canvas")))
        .thenReturn(List.of());
    when(db.queryForList(startsWith("SELECT * FROM publication_versions"), eq("tenant"),
        eq("canvas"), anyString())).thenAnswer(call -> List.of(versionRow));
  }

  private List<JsonNode> items() {
    return stored.values().stream().map(node -> (JsonNode) node.deepCopy()).toList();
  }

  private ObjectNode boundNode() {
    ObjectNode node = ProcessControlContractTest.pipe("bound", null);
    node.set("stateBinding", ProcessControlContractTest.binding("inletTemperature", "number"));
    return node;
  }

  @Test void savePreviewChunkAndPublicSharePreserveBindingAndUnboundNodesExactly() {
    ObjectNode legacy = ProcessControlContractTest.node("legacy");
    stored.put("legacy", legacy.deepCopy());
    ObjectNode bound = boundNode();
    JsonNode saved = documents.patch(user, "canvas", "canvas", ProcessControlContractTest.patch(0, bound));
    assertEquals(1, saved.path("canvas").path("revision").asInt());
    assertEquals(legacy, stored.get("legacy"));
    assertEquals(bound, stored.get("bound"));
    assertEquals(bound, saved.path("canvas").path("nodes").get(1));
    assertEquals(bound, documents.read(user, "canvas", "canvas").path("canvas").path("nodes").get(1));
    assertEquals(bound, documents.chunk(user, "canvas", 1, 0, 200).path("items").get(1));

    var shares = spy(new PublicShares(projects, documents, mock(Resources.class),
        mock(DataConfiguration.class), mock(RuntimeState.class), mock(TwinDriveDocuments.class)));
    var reader = Auth.User.publicationReader("tenant", "canvas");
    doReturn(reader).when(shares).reader("test-share", "canvas");
    JsonNode shared = (JsonNode) new PublicShareController(shares).document("canvas", "canvas", "test-share");
    assertEquals(bound, shared.path("canvas").path("nodes").get(1));
    assertFalse(shared.path("canvas").path("nodes").get(0).has("stateBinding"));
    verify(shares).reader("test-share", "canvas");
  }

  @Test void immutablePublicationRetainsTheCapturedBindingAfterLiveCanvasChanges() {
    ObjectNode bound = boundNode();
    documents.patch(user, "canvas", "canvas", ProcessControlContractTest.patch(0, bound));
    var publications = new Publications(projects, mock(ObjectStorage.class), contracts, mock(SourceClient.class));
    String draftHash = publications.draft(user, "canvas").path("draftHash").asText();
    String version = publications.create(user, "canvas", "Process version", draftHash).path("versionId").asText();
    JsonNode snapshot = Json.parse(versionRow.get("snapshot").toString());
    assertEquals(bound, snapshot.path("projects").path("canvas").path("document").path("nodes").get(0));

    ObjectNode unbound = bound.deepCopy();
    unbound.remove("stateBinding");
    documents.patch(user, "canvas", "canvas", ProcessControlContractTest.patch(1, unbound));
    assertEquals(unbound, stored.get("bound"));
    JsonNode frozen = publications.version(user, "canvas", version).path("version").path("snapshot");
    assertEquals(bound, frozen.path("projects").path("canvas").path("document").path("nodes").get(0));
    assertEquals(1, frozen.path("projects").path("canvas").path("document").path("revision").asInt());
  }

  @Test void deletingAReferencedDeviceRejectsTheFinalCanvasBeforeRevisionAndCoverChanges() {
    stored.put("device", ProcessControlContractTest.node("device"));
    stored.put("pipe", ProcessControlContractTest.pipe("pipe", "device"));
    var patch = ProcessControlContractTest.patch(0);
    patch.putArray("deleteNodeIds").add("device");
    var error = assertThrows(ApiException.class, () -> documents.patch(user, "canvas", "canvas", patch));
    assertTrue(error.getMessage().contains("pipe"));
    verify(db, never()).update(startsWith("UPDATE documents SET revision"), any(Object[].class));
    verify(projects.covers, never()).refresh(anyString(), anyString());
  }

  @Test void deletingThePipeAndItsDeviceTogetherIsAllowed() {
    stored.put("device", ProcessControlContractTest.node("device"));
    stored.put("pipe", ProcessControlContractTest.pipe("pipe", "device"));
    var patch = ProcessControlContractTest.patch(0);
    patch.putArray("deleteNodeIds").add("pipe").add("device");
    JsonNode result = documents.patch(user, "canvas", "canvas", patch);
    assertTrue(result.path("canvas").path("nodes").isEmpty());
    assertEquals(1, result.path("canvas").path("revision").asInt());
  }

  @Test void malformedBindingIsRejectedBeforeAnyStorageMutation() {
    var invalid = boundNode();
    invalid.putNull("stateBinding");
    assertEquals("schema_validation_failed", assertThrows(ApiException.class,
        () -> documents.patch(user, "canvas", "canvas", ProcessControlContractTest.patch(0, invalid))).code);
    verify(db, never()).update(anyString(), any(Object[].class));
  }

  @Test void incrementalPatchChecksPointTypesAgainstUnchangedStoredNodes() {
    stored.put("bound", boundNode());
    var conflicting = ProcessControlContractTest.node("second");
    conflicting.set("stateBinding", ProcessControlContractTest.binding("inletTemperature", "string"));
    ApiException error = assertThrows(ApiException.class,
        () -> documents.patch(user, "canvas", "canvas", ProcessControlContractTest.patch(0, conflicting)));
    assertEquals("invalid_input", error.code);
    assertTrue(error.getMessage().contains("inletTemperature"));
    verify(db, never()).update(startsWith("UPDATE documents SET revision"), any(Object[].class));
    verify(projects.covers, never()).refresh(anyString(), anyString());
  }
}
