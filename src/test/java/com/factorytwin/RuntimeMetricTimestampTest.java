package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class RuntimeMetricTimestampTest {
  final Auth.User user = new Auth.User("user", "tenant", "u@example.invalid", "u", "User",
      "delivery_manager", true, true);
  final Contracts contracts = new Contracts();
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Projects projects = spy(new Projects(db, mock(Auth.class), mock(TransactionTemplate.class),
      mock(ProjectCovers.class), contracts));
  final Instant now = Instant.now();
  final String t0 = now.minusSeconds(35).toString();
  final String t1 = now.minusSeconds(20).toString();
  final String t2 = now.minusSeconds(10).toString();

  ObjectNode state(String timestamp, String collectedAt, String field, Object value) {
    return Json.obj("quality", "good", "sourceTimestamp", timestamp, "collectedAt", collectedAt,
        "payload", Json.obj(field, value), "durationMs", 1);
  }
  Map<String,Object> binding(String id, String source, String metric, String field, String valueType) {
    return Map.of("id", id, "source_id", source,
        "body", Json.obj("metricKey", metric, "sourcePath", "$."+field,
            "valueType", valueType, "unit", null, "staleAfterSeconds", 3600).toString(),
        "source_body", Json.obj("name", source, "config", Json.obj("intervalSeconds", 60)).toString());
  }
  JsonNode metric(JsonNode runtime, String key) {
    for (JsonNode metric : runtime.path("metrics")) if (key.equals(metric.path("metricKey").asText())) return metric;
    fail("Metric not found: " + key); return Json.M.nullNode();
  }

  @Test void currentRuntimeKeepsSlowSourceTimestampWhileAlarmSourceAdvances() {
    doReturn(Json.obj("projectType", "3d")).when(projects).access(user, "project", false);
    var data=spy(new DataConfiguration(projects,contracts,mock(SourceClient.class)));
    doReturn(Map.of()).when(data).row(user,"project","assets","asset-record");
    doReturn(Json.obj("id","asset-record","assetId","pump-1","name","Pump"))
        .when(data).present(anyMap());
    doReturn(List.of(binding("binding-a","A","slow","slow","number"),
        binding("binding-b","B","alarm","alarm","boolean")))
        .when(data).bindingRows(user,"project","asset-record");
    var redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") HashOperations<String,Object,Object> hash=mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hash);
    when(hash.get(anyString(),eq("A"))).thenReturn(state(null,t0,"slow",7).toString());
    when(hash.get(anyString(),eq("B"))).thenReturn(
        state(t1,now.minusSeconds(18).toString(),"alarm",false).toString(),
        state(t2,now.minusSeconds(8).toString(),"alarm",true).toString());
    var runtime=new RuntimeState(redis,data);
    JsonNode first=runtime.asset(user,"project","asset-record");
    JsonNode second=runtime.asset(user,"project","asset-record");
    assertEquals(t0,first.path("timestamp").asText());
    assertEquals(t0,second.path("timestamp").asText());
    assertEquals(t0,metric(first,"slow").path("timestamp").asText());
    assertEquals(t1,metric(first,"alarm").path("timestamp").asText());
    assertEquals(t2,metric(second,"alarm").path("timestamp").asText());
    assertFalse(metric(first,"alarm").path("value").asBoolean());
    assertTrue(metric(second,"alarm").path("value").asBoolean());
    assertEquals("B",metric(second,"alarm").path("sourceId").asText());
    assertEquals("good",metric(second,"alarm").path("quality").asText());
    assertEquals(now.minusSeconds(8).toString(),metric(second,"alarm").path("collectedAt").asText());
    assertEquals(Set.of("A","B"),Set.of(first.path("sources").get(0).path("id").asText(),
        first.path("sources").get(1).path("id").asText()));
  }

  @Test void fixedVersionRuntimeUsesEachMetricSourceTimestamp() {
    doReturn(Json.obj("id","root")).when(projects).access(user,"root",false);
    var snapshot=Json.obj("projects",Json.obj("project",Json.obj(
        "assets",List.of(Json.obj("id","asset-record","assetId","pump-1","assetType","pump","name","Pump")),
        "dataSources",List.of(
            Json.obj("id","A","name","A","config",Json.obj("source","A","intervalSeconds",60)),
            Json.obj("id","B","name","B","config",Json.obj("source","B","intervalSeconds",60))),
        "dataBindings",List.of(
            Json.obj("id","binding-a","assetRecordId","asset-record","dataSourceId","A",
                "metricKey","slow","sourcePath","$.slow","valueType","number","unit",null,"staleAfterSeconds",3600),
            Json.obj("id","binding-b","assetRecordId","asset-record","dataSourceId","B",
                "metricKey","alarm","sourcePath","$.alarm","valueType","boolean","unit",null,"staleAfterSeconds",3600)))));
    when(db.queryForList(contains("FROM publication_versions"),eq("tenant"),eq("root"),eq("version")))
        .thenReturn(List.of(Map.of("snapshot",snapshot.toString())));
    var source=mock(SourceClient.class);
    var alarmReads=new java.util.concurrent.atomic.AtomicInteger();
    when(source.collect(any(JsonNode.class))).thenAnswer(call -> {
      JsonNode config=call.getArgument(0);
      if (config.path("source").asText().equals("A")) return state(null,t0,"slow",7);
      return alarmReads.getAndIncrement()==0
          ? state(t1,now.minusSeconds(18).toString(),"alarm",false)
          : state(t2,now.minusSeconds(8).toString(),"alarm",true);
    });
    var versions=new Publications(projects,mock(ObjectStorage.class),contracts,source);
    JsonNode first=versions.runtimeAsset(user,"root","version","project","asset-record").path("runtimeState");
    JsonNode second=versions.runtimeAsset(user,"root","version","project","asset-record").path("runtimeState");
    assertEquals(t0,first.path("timestamp").asText());
    assertEquals(t0,second.path("timestamp").asText());
    assertEquals(t1,metric(first,"alarm").path("timestamp").asText());
    assertEquals(t2,metric(second,"alarm").path("timestamp").asText());
    assertFalse(metric(first,"alarm").path("value").asBoolean());
    assertTrue(metric(second,"alarm").path("value").asBoolean());
    assertEquals("B",metric(second,"alarm").path("sourceId").asText());
    assertEquals("good",metric(second,"alarm").path("quality").asText());
    assertEquals(now.minusSeconds(8).toString(),metric(second,"alarm").path("collectedAt").asText());
  }
}
