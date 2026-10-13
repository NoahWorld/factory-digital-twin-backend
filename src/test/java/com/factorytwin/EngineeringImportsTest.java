package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class EngineeringImportsTest {
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Auth auth = mock(Auth.class);
  final TransactionTemplate tx = mock(TransactionTemplate.class);
  final Projects projects = spy(new Projects(db, auth, tx, mock(ProjectCovers.class), new Contracts()));
  final ObjectStorage store = mock(ObjectStorage.class);
  final EngineeringProcess engine = mock(EngineeringProcess.class);
  final EngineeringImports service = new EngineeringImports(projects, store, engine);
  final Auth.User user = new Auth.User("user", "tenant", "user@example.invalid", "user", "User", "delivery_manager", true, true);

  ObjectNode inspection() {
    return Json.obj("schemaVersion", 1, "format", "dxf", "units", "mm", "objectCount", 2,
        "layers", List.of(Json.obj("name", "WALL", "entityCount", 1, "types", List.of("LWPOLYLINE")),
            Json.obj("name", "TEXT", "entityCount", 1, "types", List.of("TEXT"))), "warnings", List.of());
  }

  ObjectNode options() {
    return Json.obj("schemaVersion", 1, "name", "Workshop", "center", true, "units", "mm", "layers",
        List.of(Json.obj("name", "WALL", "role", "wall", "height", 6.0, "base", 0),
            Json.obj("name", "TEXT", "role", "ignore", "height", 0, "base", 0)));
  }

  @Test void rejectsUnknownOrMissingLayerMappingsInsteadOfDroppingGeometry() {
    var options = options();
    ((ObjectNode) options.path("layers").get(1)).put("name", "UNKNOWN");
    assertThrows(ApiException.class, () -> EngineeringImports.validateOptions(options, inspection(), "dxf"));
    options.set("layers", Json.M.createArrayNode().add(options.path("layers").get(0)));
    assertThrows(ApiException.class, () -> EngineeringImports.validateOptions(options, inspection(), "dxf"));
  }

  @Test void rejectsNonfiniteZeroAndNegativeModelHeight() {
    for (double height : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, -1, 1001}) {
      var options = options();
      ((ObjectNode) options.path("layers").get(0)).put("height", height);
      assertThrows(ApiException.class, () -> EngineeringImports.validateOptions(options, inspection(), "dxf"));
    }
    assertDoesNotThrow(() -> EngineeringImports.validateOptions(options(), inspection(), "dxf"));
    var thin = options();
    ((ObjectNode) thin.path("layers").get(0)).put("height", 0.0001);
    assertDoesNotThrow(() -> EngineeringImports.validateOptions(thin, inspection(), "dxf"));
  }

  @Test void rejectsUnknownUnitsAndAllIgnoredLayers() {
    var unknown = options().put("units", "auto");
    assertThrows(ApiException.class, () -> EngineeringImports.validateOptions(unknown, inspection(), "dxf"));
    var ignored = options();
    ((ObjectNode) ignored.path("layers").get(0)).put("role", "ignore");
    assertThrows(ApiException.class, () -> EngineeringImports.validateOptions(ignored, inspection(), "dxf"));
  }

  @Test void ifcUsesNativeGeometryAndDoesNotAcceptCadExtrusionMappings() {
    var input = options();
    assertThrows(ApiException.class, () -> EngineeringImports.validateOptions(input, inspection(), "ifc"));
    input.set("layers", Json.M.createArrayNode());
    input.remove("units");
    assertDoesNotThrow(() -> EngineeringImports.validateOptions(input, inspection(), "ifc"));
  }

  @Test void fullChineseLayerMappingFitsTheSharedProcessBudget() {
    var input = options();
    var report = inspection();
    var mappings = Json.M.createArrayNode();
    var layers = Json.M.createArrayNode();
    for (int i = 0; i < 2000; i++) {
      String name = "工程图层".repeat(62) + String.format("层%06d", i);
      assertEquals(255, name.length());
      mappings.add(Json.obj("name", name, "role", "wall", "height", 3, "base", 0));
      layers.add(Json.obj("name", name, "entityCount", 1, "types", List.of("LWPOLYLINE")));
    }
    input.set("layers", mappings);
    report.set("layers", layers);
    assertTrue(input.toString().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024);
    assertDoesNotThrow(() -> EngineeringImports.validateOptions(input, report, "dxf"));
    mappings.add(Json.obj("name", "EXTRA", "role", "wall", "height", 3, "base", 0));
    assertThrows(ApiException.class, () -> EngineeringImports.validateOptions(input, report, "dxf"));
  }

  @Test void oversizedOptionsAreRejectedBeforeAProcessCanBeQueued() {
    var input = options().put("name", "Workshop" + " ".repeat(EngineeringImports.MAX_OPTIONS_BYTES));
    var error = assertThrows(ApiException.class, () -> EngineeringImports.validateOptions(input, inspection(), "dxf"));
    assertTrue(error.getMessage().contains("2 MiB"));
  }

  @Test void plainSupportedFilenamesOnly() {
    assertEquals("ifc", EngineeringImports.format("building.IFC"));
    for (String name : List.of("../a.dxf", "C:\\a.ifc", "a.rvt", "a.dwg", ".ifc", "a\n.ifc"))
      assertThrows(ApiException.class, () -> EngineeringImports.format(name));
  }

  @Test void deniedUploadDoesNotReadSourceOrProbeEngine() {
    doThrow(new ApiException(403, "project_write_forbidden", "Editor required"))
        .when(projects).access(user, "project", true);
    var file = mock(org.springframework.web.multipart.MultipartFile.class);
    assertThrows(ApiException.class, () -> service.upload(user, "project", "test", file));
    verifyNoInteractions(file, store, engine, tx);
  }

  @Test void unavailableEngineRejectsBeforeCreatingAnyJob() {
    doReturn(Json.obj()).when(projects).access(user, "project", true);
    when(engine.available()).thenReturn(false);
    var file = new MockMultipartFile("file", "test.dxf", "application/octet-stream", new byte[] {1});
    assertEquals(503, assertThrows(ApiException.class, () -> service.upload(user, "project", "test", file)).status);
    verifyNoInteractions(tx, store);
  }

  @Test void statusRequiresProjectAccessBeforeReadingAnyJob() {
    doThrow(new ApiException(404, "project_not_found", "No access"))
        .when(projects).access(user, "project", false);
    assertThrows(ApiException.class, () -> service.row(user, "project", "job"));
    verifyNoInteractions(db);
  }

  @Test void concurrentSubmissionCannotRestartAnActiveJob() {
    doReturn(Json.obj()).when(projects).access(user, "project", true);
    doReturn(Json.obj()).when(projects).access(user, "project", false);
    doNothing().when(projects).lock(user, "project");
    when(engine.available()).thenReturn(true);
    when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
    when(db.queryForList(contains("SELECT * FROM engineering_imports"), eq("tenant"), eq("project"), eq("job")))
        .thenReturn(List.of(Map.of("status", "converting", "inspection", inspection().toString())));
    var error = assertThrows(ApiException.class, () -> service.convert(user, "project", "job", options()));
    assertEquals("engineering_state_conflict", error.code);
    verify(db, never()).update(anyString(), any(Object[].class));
  }

  @Test void deletingProjectWithActiveImportIsBlockedBeforeStorageDeletion() {
    when(db.queryForList(contains("SELECT id,status,source_key"), eq("tenant"), eq("project")))
        .thenReturn(List.of(Map.of("status", "inspecting", "id", "job", "source_key", "source")));
    assertEquals("engineering_import_active", assertThrows(ApiException.class, () -> service.deleteProjectSources("tenant", "project")).code);
    verifyNoInteractions(store);
  }

  @Test void processLogReaderHasMemoryBoundAndPreservesLastLine() throws Exception {
    assertEquals("last", EngineeringProcess.line(new StringReader("last")));
    assertNull(EngineeringProcess.line(new StringReader("")));
    assertThrows(java.io.IOException.class, () -> EngineeringProcess.line(new StringReader("x".repeat(16385))));
  }

  @Test void lostLeaseStopsWorkerRatherThanPublishingAStaleResult() {
    var worker = new EngineeringWorker(service);
    try {
      when(db.update(contains("lease_until>now()"), eq("job"), eq("lease"))).thenReturn(0);
      assertThrows(IllegalStateException.class, () -> worker.heartbeat("job", "lease"));
    } finally { worker.stop(); }
  }

  @Test void converterErrorsKeepActionableDiagnosticsAndUnexpectedErrorsHideServerInternals() {
    var known = new EngineeringProcess.ConversionFailure("cad_open_loop", "墙体轮廓未闭合", Json.obj("layer", "WALL", "handle", "A1"));
    assertEquals("A1", EngineeringWorker.error(known, "job").path("details").path("handle").asText());
    var unknown = EngineeringWorker.error(new IllegalStateException("secret filesystem path"), "job");
    assertFalse(unknown.toString().contains("secret"));
    assertTrue(unknown.path("message").asText().contains("job"));
  }

  @Test void queueCapacityIsCheckedAfterTenantLockAndAppliesToConversionRetries() {
    doReturn(Json.obj()).when(projects).access(user, "project", true);
    doReturn(Json.obj()).when(projects).access(user, "project", false);
    doNothing().when(projects).lock(user, "project");
    when(engine.available()).thenReturn(true);
    when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
    when(db.queryForList(contains("SELECT * FROM engineering_imports"), eq("tenant"), eq("project"), eq("job")))
        .thenReturn(List.of(Map.of("status", "failed", "inspection", inspection().toString(), "format", "dxf")));
    when(db.queryForObject(contains("SELECT count(*) FROM engineering_imports"), eq(Integer.class), eq("tenant"))).thenReturn(10);
    assertEquals("engineering_queue_full", assertThrows(ApiException.class, () -> service.convert(user, "project", "job", options())).code);
    var ordered = inOrder(db);
    ordered.verify(db).queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", "tenant");
    ordered.verify(db).queryForObject(contains("SELECT count(*) FROM engineering_imports"), eq(Integer.class), eq("tenant"));
    verify(db, never()).update(anyString(), any(Object[].class));
  }

  @Test void auditFailureAfterSavingSourceCannotMarkClaimedImportAsFailed() {
    doReturn(Json.obj()).when(projects).access(user, "project", true);
    doNothing().when(projects).lock(user, "project");
    when(engine.available()).thenReturn(true);
    doAnswer(i -> { ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) i.getArgument(0)).accept(null); return null; })
        .when(tx).executeWithoutResult(any());
    when(db.queryForObject(contains("SELECT count(*) FROM engineering_imports"), eq(Integer.class), eq("tenant"))).thenReturn(0);
    org.springframework.test.util.ReflectionTestUtils.setField(store, "client", mock(software.amazon.awssdk.services.s3.S3Client.class));
    doThrow(new IllegalStateException("audit database unavailable")).when(auth).audit(eq(user), eq("project"), eq("engineering.upload"), any());
    var file = new MockMultipartFile("file", "test.dxf", "application/octet-stream", new byte[] {1});
    assertThrows(IllegalStateException.class, () -> service.upload(user, "project", "test", file));
    verify(db, never()).update(contains("status='failed'"), any(Object[].class));
    verify(db).update(contains("stage='queued'"), anyString());
  }
}
