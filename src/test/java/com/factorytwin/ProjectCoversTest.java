package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ProjectCoversTest {
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Auth auth = mock(Auth.class);
  final TransactionTemplate tx = mock(TransactionTemplate.class);
  final ProjectCovers covers = mock(ProjectCovers.class);
  final Projects projects = spy(new Projects(db, auth, tx, covers, new Contracts()));
  final ProjectCoverController controller = new ProjectCoverController(projects, covers);
  final Auth.User user = new Auth.User("user", "tenant", "user@example.invalid", "user", "User", "delivery_manager");

  MockHttpServletRequest authorizedRequest() {
    var request = new MockHttpServletRequest();
    when(auth.require(request)).thenReturn(user);
    doReturn(Json.obj("id", "project", "coverStatus", "ready")).when(projects).access(user, "project", false);
    doReturn(Json.obj("id", "project")).when(projects).access(user, "project", true);
    doNothing().when(projects).lock(user, "project");
    when(tx.execute(any())).thenAnswer(invocation ->
        ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    return request;
  }

  @Test void uploadRejectsViewersBeforeReadingOrValidatingBody() {
    var request = authorizedRequest();
    doThrow(new ApiException(403, "project_write_forbidden", "Editor required"))
        .when(projects).access(user, "project", true);
    var error = assertThrows(ApiException.class, () -> controller.upload("project", "0", "1", request));
    assertEquals(403, error.status);
    verifyNoInteractions(covers, tx);
  }

  @Test void uploadRejectsStaleDocumentBeforeStoring() throws Exception {
    var request = authorizedRequest(); request.setContent(ProjectCoverPngTest.validPng());
    when(db.queryForObject(contains("FROM documents"), eq(Long.class), eq("tenant"), eq("project"))).thenReturn(2L);
    var error = assertThrows(ApiException.class, () -> controller.upload("project", "1", "7", request));
    assertEquals(409, error.status); assertEquals("revision_conflict", error.code);
    verifyNoInteractions(covers);
  }

  @Test void uploadRejectsChangedReferencedSceneBeforeStoring() throws Exception {
    var request = authorizedRequest(); request.setContent(ProjectCoverPngTest.validPng());
    when(db.queryForObject(contains("FROM documents"), eq(Long.class), eq("tenant"), eq("project"))).thenReturn(2L);
    doThrow(new ApiException(409, "cover_revision_conflict", "Referenced scene changed"))
        .when(covers).lockRevision("tenant", "project", 7);
    var error = assertThrows(ApiException.class, () -> controller.upload("project", "2", "7", request));
    assertEquals("cover_revision_conflict", error.code);
    verify(covers, never()).store(anyString(), anyString(), anyLong(), any());
  }

  @Test void uploadLocksChecksStoresAndAuditsInOrder() throws Exception {
    var request = authorizedRequest(); byte[] png = ProjectCoverPngTest.validPng(); request.setContent(png);
    when(db.queryForObject(contains("FROM documents"), eq(Long.class), eq("tenant"), eq("project"))).thenReturn(2L);
    when(covers.store(eq("tenant"), eq("project"), eq(2L), any())).thenReturn(8L);
    var result = (com.fasterxml.jackson.databind.JsonNode) controller.upload("project", "2", "7", request);
    assertEquals("ready", result.path("project").path("coverStatus").asText());
    var ordered = inOrder(projects, db, covers, auth);
    ordered.verify(projects).lock(user, "project");
    ordered.verify(projects).access(user, "project", true);
    ordered.verify(db).queryForObject(contains("FROM documents"), eq(Long.class), eq("tenant"), eq("project"));
    ordered.verify(covers).lockRevision("tenant", "project", 7);
    ordered.verify(covers).store(eq("tenant"), eq("project"), eq(2L), any());
    ordered.verify(auth).audit(eq(user), eq("project"), eq("project.cover.upload"), any());
  }

  @Test void coverRevisionLockRejectsStaleCapture() {
    when(db.queryForObject(contains("FOR UPDATE"), eq(Long.class), eq("tenant"), eq("project"))).thenReturn(8L);
    var error = assertThrows(ApiException.class, () -> new ProjectCovers(db).lockRevision("tenant", "project", 7));
    assertEquals(409, error.status); assertEquals("cover_revision_conflict", error.code);
    assertDoesNotThrow(() -> new ProjectCovers(db).lockRevision("tenant", "project", 8));
  }

  @Test void getAuthorizesBeforeReturningConditionalCacheResponse() {
    var request = authorizedRequest(); request.addHeader(HttpHeaders.IF_NONE_MATCH, "*");
    doThrow(new ApiException(404, "project_not_found", "Not accessible"))
        .when(projects).access(user, "project", false);
    assertThrows(ApiException.class, () -> controller.cover("project", request));
    verifyNoInteractions(covers);
  }

  @Test void getMissingScreenshotFailsInsteadOfReturningPlaceholder() {
    var request = authorizedRequest();
    when(covers.read("tenant", "project")).thenThrow(new ApiException(404, "project_cover_pending", "Not captured"));
    var error = assertThrows(ApiException.class, () -> controller.cover("project", request));
    assertEquals(404, error.status); assertEquals("project_cover_pending", error.code);
  }

  @Test void getHasPrivateRevalidatedEtagAndNeverLeaksPngIn304() throws Exception {
    var request = authorizedRequest(); byte[] png = ProjectCoverPngTest.validPng();
    when(covers.read("tenant", "project")).thenReturn(new ProjectCovers.Cover(7, 2, png));
    var response = controller.cover("project", request);
    assertEquals(200, response.getStatusCode().value()); assertArrayEquals(png, response.getBody());
    assertEquals("private, no-cache", response.getHeaders().getCacheControl());
    assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    request.addHeader(HttpHeaders.IF_NONE_MATCH, "W/" + response.getHeaders().getETag());
    var cached = controller.cover("project", request);
    assertEquals(304, cached.getStatusCode().value()); assertNull(cached.getBody());
  }

  @Test void coverStateSeparatesPendingScreenshotFromDocumentRevision() {
    var row = new HashMap<String, Object>(Map.of("id", "project", "cover_revision", 7L,
        "document_revision", 2L, "cover_status", "pending", "cover_has_png", false,
        "created_at", Instant.EPOCH, "updated_at", Instant.EPOCH));
    var pending = projects.present(row);
    assertTrue(pending.path("coverUrl").isNull()); assertTrue(pending.path("coverSourceRevision").isNull());
    row.put("cover_has_png", true); row.put("cover_source_revision", 1L);
    var old = projects.present(row);
    assertEquals("pending", old.path("coverStatus").asText());
    assertEquals("/api/v1/projects/project/cover.png?revision=7", old.path("coverUrl").asText());
    row.put("cover_status", "ready");
    assertEquals("pending", projects.present(row).path("coverStatus").asText());
    row.put("cover_source_revision", 2L);
    assertEquals("ready", projects.present(row).path("coverStatus").asText());
  }

  @Test void revisionQueriesRejectMissingNegativeFractionalAndUnsafeIntegers() {
    assertEquals(0, ProjectCoverController.revision("0", "sourceRevision"));
    assertEquals(9007199254740991L, ProjectCoverController.revision("9007199254740991", "expectedCoverRevision"));
    for (String value : new String[] {null, "", "-1", "1.0", "01", "9007199254740992", "99999999999999999999"}) {
      assertThrows(ApiException.class, () -> ProjectCoverController.revision(value, "sourceRevision"));
    }
  }
}
