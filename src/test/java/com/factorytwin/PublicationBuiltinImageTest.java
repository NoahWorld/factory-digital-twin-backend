package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationBuiltinImageTest {
  @Test void fixedVersionDraftIncludesLinkedCanvasWithoutTreatingBuiltinImageAsUpload() {
    var contracts=new Contracts();
    var db=mock(JdbcTemplate.class);
    var user=new Auth.User("user","tenant","u@example.invalid","u","User","delivery_manager",true,true);
    var projects=spy(new Projects(db,mock(Auth.class),mock(TransactionTemplate.class),
        mock(ProjectCovers.class),contracts));
    doReturn(Json.obj("id","scene","projectType","3d","projectRole","owner","name","Scene","documentRevision",1))
        .when(projects).access(user,"scene",false);
    doReturn(Json.obj("id","scene","projectType","3d","projectRole","owner","name","Scene","documentRevision",1))
        .when(projects).access(user,"scene",true);
    doReturn(Json.obj("id","canvas","projectType","2d","projectRole","owner","name","Canvas","documentRevision",1))
        .when(projects).access(user,"canvas",false);
    when(db.queryForMap(startsWith("SELECT * FROM documents"),eq("tenant"),anyString()))
        .thenAnswer(call -> {
          String id=call.getArgument(2);
          var row=new HashMap<String,Object>();
          row.put("revision",1L); row.put("updated_at",Instant.parse("2026-09-27T00:00:00Z"));
          row.put("settings",id.equals("scene")?Documents.settings(contracts).toString():Documents.theme().toString());
          row.put("linked_project_id",id.equals("scene")?"canvas":null);
          return row;
        });
    when(db.query(startsWith("SELECT body::text FROM document_items"),any(RowMapper.class),
        eq("tenant"),anyString())).thenAnswer(call -> {
          String id=call.getArgument(3);
          if (id.equals("scene")) return List.of(Json.obj("id","model-instance",
              "modelAssetId","builtin:aqua-helix-hd-v1"));
          return List.of(Json.obj("id","image-node","type","image",
              "resourceRefs",List.of("builtin:industry-production-v1")));
        });
    when(db.query(startsWith("SELECT id,body::text,created_at,updated_at FROM"),any(RowMapper.class),
        eq("tenant"),anyString())).thenReturn(List.of());
    when(db.query(startsWith("SELECT id,asset_id,source_id,body::text FROM data_bindings"),
        any(RowMapper.class),eq("tenant"),anyString())).thenReturn(List.of());
    when(db.queryForList(contains("FROM publication_current"),eq("tenant"),eq("scene")))
        .thenReturn(List.of());
    var publications=new Publications(projects,mock(ObjectStorage.class),contracts,mock(SourceClient.class));
    JsonNode draft=publications.draft(user,"scene");
    assertEquals(2,draft.path("projectCount").asInt());
    assertEquals(0,draft.path("resourceCount").asInt());
    assertFalse(draft.path("draftHash").asText().isBlank());
    doNothing().when(projects).lock(user,"scene");
    JsonNode created=publications.create(user,"scene","发布版本",draft.path("draftHash").asText());
    assertFalse(created.path("versionId").asText().isBlank());
    verify(db).update(startsWith("INSERT INTO publication_versions"),any(Object[].class));
    verify(db,never()).update(startsWith("INSERT INTO publication_resources"),any(Object[].class));
    verify(db,never()).queryForList(contains("FROM resources"),anyString(),anyString());
  }
}
