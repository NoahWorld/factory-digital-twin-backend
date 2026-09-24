package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationsTest {
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final TransactionTemplate tx = mock(TransactionTemplate.class);
  final Auth auth = new Auth(db, mock(PasswordEncoder.class), tx, "x".repeat(32), "tenant", false);
  final Projects projects = spy(new Projects(db, auth, tx, mock(ProjectCovers.class), mock(Contracts.class)));
  final ObjectStorage storage = mock(ObjectStorage.class);
  final Resources resources = spy(new Resources(projects, storage, mock(Contracts.class)));
  final Publications publications = spy(new Publications(projects, mock(Documents.class), resources,
      mock(DataConfiguration.class), mock(RuntimeState.class), mock(TwinDriveDocuments.class)));
  final Auth.User editor = new Auth.User("editor", "tenant", "editor@example.invalid", "editor",
      "Editor", "delivery_manager", true, true);

  @Test void signedLinkOnlyReadsScopedProjectsAndStopsAfterRevocation() {
    String id = Json.id();
    var publication = new Publications.Publication(id, "tenant", "root");
    String share = publications.token(publication);
    var stored = Map.<String, Object>of("id", id, "tenant_id", "tenant", "project_id", "root");
    when(db.queryForList(contains("FROM project_publications pub"), eq(id)))
        .thenReturn(List.of(stored));
    when(db.queryForObject(contains("FROM project_publication_scopes"), eq(Integer.class),
        eq("tenant"), eq(id), eq("root"))).thenReturn(1);
    when(db.queryForObject(contains("FROM project_publication_scopes"), eq(Integer.class),
        eq("tenant"), eq(id), eq("other"))).thenReturn(0);

    Auth.User reader = publications.reader(share, "root");
    assertEquals("root", reader.publicProjectId());
    assertEquals("viewer", reader.role());
    var row = new HashMap<String, Object>();
    row.put("id", "root"); row.put("name", "Public project"); row.put("status", "published");
    row.put("project_type", "2d"); row.put("member_role", null);
    row.put("cover_revision", 1L); row.put("document_revision", 1L);
    row.put("cover_source_revision", 1L); row.put("cover_status", "pending");
    row.put("cover_has_png", false);
    row.put("created_at", Instant.now()); row.put("updated_at", Instant.now());
    when(db.queryForList(contains("AND p.id=?"), eq("publication"), eq("tenant"), eq(true), eq("root")))
        .thenReturn(List.of(row));
    assertEquals("root", projects.access(reader, "root", false).path("id").asText());
    assertEquals("publication_read_only", assertThrows(ApiException.class,
        () -> projects.access(reader, "root", true)).code);
    assertEquals("project_not_found", assertThrows(ApiException.class,
        () -> projects.access(reader, "other", false)).code);
    assertEquals("publication_not_found", assertThrows(ApiException.class,
        () -> publications.reader(share, "other")).code);
    char replacement = share.charAt(share.length() - 1) == 'A' ? 'B' : 'A';
    assertEquals("publication_not_found", assertThrows(ApiException.class,
        () -> publications.active(share.substring(0, share.length() - 1) + replacement)).code);
    when(db.queryForList(contains("FROM project_publications pub"), eq(id)))
        .thenReturn(List.of());
    assertEquals("publication_not_found", assertThrows(ApiException.class,
        () -> publications.active(share)).code);
  }

  @Test void publishedResourceEndpointsExposeOnlyReferencedFiles() {
    Auth.User reader = Auth.User.publicationReader("tenant", "root");
    doReturn(reader).when(publications).reader("share", "root");
    doReturn(Set.of("used")).when(publications).publicResourceIds(reader, "root");
    doReturn(List.of(Json.obj("id", "used"), Json.obj("id", "unused"))).when(resources)
        .list(reader, "root", "image");
    var controller = new PublicationController(publications);
    var listed = (com.fasterxml.jackson.databind.JsonNode) controller.resources("root", "image", "share");
    assertEquals(1, listed.path("imageAssets").size());
    assertEquals("used", listed.path("imageAssets").get(0).path("id").asText());
    assertEquals("publication_resource_not_found", assertThrows(ApiException.class,
        () -> controller.content("root", "image", "unused", "share")).code);
    verify(resources, never()).row(reader, "root", "unused");
    doReturn(Map.<String, Object>of("state", "ready", "kind", "image", "object_key", "safe/key"))
        .when(resources).row(reader, "root", "used");
    when(storage.download("safe/key")).thenReturn("https://storage.example.invalid/signed");
    var response = controller.content("root", "image", "used", "share");
    assertEquals(302, response.getStatusCode().value());
    assertEquals("https://storage.example.invalid/signed", response.getHeaders().getLocation().toString());
  }

  @Test void publisherMustHaveEditRightsOnLinkedProjectsBeforeAnyMutation() {
    when(tx.execute(any())).thenAnswer(call ->
        ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
    doReturn(Json.obj("status", "draft", "documentRevision", 1L)).when(projects)
        .access(editor, "root", true);
    doReturn(Json.obj("documentRevision", 1L)).when(projects).access(editor, "root", false);
    doNothing().when(projects).lock(editor, "root");
    var document = new HashMap<String, Object>();
    document.put("linked_project_id", "linked");
    when(db.queryForMap(contains("SELECT linked_project_id FROM documents"), eq("tenant"), eq("root")))
        .thenReturn(document);
    doReturn(List.of()).when(db).query(contains("SELECT body::text FROM document_items"),
        any(RowMapper.class), eq("tenant"), eq("root"));
    doThrow(new ApiException(403, "project_write_forbidden", "Linked project edit permission required"))
        .when(projects).access(editor, "linked", true);
    assertEquals("project_write_forbidden", assertThrows(ApiException.class,
        () -> publications.publish(editor, "root")).code);
    verify(db, never()).update(anyString(), any(Object[].class));
  }
}
