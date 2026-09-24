package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;

class TwinDriveRuntimeTest {
  final Auth.User user = new Auth.User("editor", "tenant", "editor@example.invalid", "editor", "Editor", "delivery_manager", true, true);
  final TransactionTemplate tx = mock(TransactionTemplate.class);
  final Auth auth = mock(Auth.class);
  final Contracts contracts = new Contracts();
  final Projects projects = spy(new Projects(mock(JdbcTemplate.class), auth, tx, mock(ProjectCovers.class), contracts));
  final TwinDriveDocuments documents = spy(new TwinDriveDocuments(projects, contracts));
  final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  final ValueOperations<String, String> values = mock(ValueOperations.class);
  final Map<String, String> store = new HashMap<>();
  final TwinDriveRuntime runtime = new TwinDriveRuntime(TwinDriveControllerTest.proxied(documents), projects, contracts, redis);
  ObjectNode document;

  @BeforeEach void setup() {
    document = Json.obj("projectId", "scene", "revision", 1, "config", TwinDriveEngineTest.config(), "editable", true);
    doNothing().when(projects).lock(user, "scene");
    doReturn(Json.obj("projectType", "3d", "projectRole", "editor")).when(projects).access(eq(user), eq("scene"), anyBoolean());
    doAnswer(call -> document.deepCopy()).when(documents).read(user, "scene");
    doNothing().when(documents).validateReferences(eq(user), eq("scene"), any());
    when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenAnswer(call -> store.get(call.getArgument(0)));
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call -> store.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
    doAnswer(call -> {
      RedisScript<Long> script = call.getArgument(0); List<String> keys = call.getArgument(1);
      String token = call.getArgument(2);
      if (!Objects.equals(store.get(keys.getFirst()), token)) return 0L;
      if (script == TwinDriveRuntime.RELEASE) store.remove(keys.getFirst());
      else store.put(keys.get(1), call.getArgument(3));
      return 1L;
    }).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
  }

  @Test void commandIdDeduplicatesActualMutationsAndRejectsDifferentContent() {
    var command = TwinDriveEngineTest.command("reset");
    var ack = runtime.command(user, "scene", command);
    assertEquals(ack.toString(), runtime.command(user, "scene", command).toString());
    verify(auth, times(1)).audit(eq(user), eq("scene"), eq("twin_drive.command"), any());
    assertEquals("twin_command_id_conflict", assertThrows(ApiException.class, () -> runtime.command(user, "scene", command.deepCopy().put("operation", "pause"))).code);
    assertFalse(store.containsKey(TwinDriveRuntime.key("tenant", "scene") + ":lock"));
  }

  @Test void configurationRevisionInvalidatesSamplesAndRequiresExplicitReset() {
    runtime.command(user, "scene", TwinDriveEngineTest.command("reset"));
    assertFalse(runtime.snapshot(user, "scene").path("points").isEmpty());
    document.put("revision", 2);
    var snapshot = runtime.snapshot(user, "scene");
    assertEquals(2, snapshot.path("revision").asInt()); assertEquals("idle", snapshot.path("status").asText()); assertTrue(snapshot.path("points").isEmpty());
    assertEquals("twin_revision_conflict", assertThrows(ApiException.class, () -> runtime.command(user, "scene", TwinDriveEngineTest.command("reset"))).code);
  }

  @Test void contentionAndWritePermissionsAreNotFakeSuccesses() {
    String lock = TwinDriveRuntime.key("tenant", "scene") + ":lock"; store.put(lock, "other-process");
    assertEquals("twin_runtime_busy", assertThrows(ApiException.class, () -> runtime.command(user, "scene", TwinDriveEngineTest.command("reset"))).code);
    assertEquals("other-process", store.get(lock)); store.remove(lock);
    doThrow(new ApiException(403, "project_write_forbidden", "Viewer cannot command")).when(projects).access(user, "scene", true);
    assertEquals("project_write_forbidden", assertThrows(ApiException.class, () -> runtime.command(user, "scene", TwinDriveEngineTest.command("reset"))).code);
    assertFalse(store.containsKey(lock)); verifyNoInteractions(auth);
  }

  @Test void fencedWriteRejectsExpiredOwnershipAndCommandRateIsBounded() {
    for (int i = 0; i < 20; i++) runtime.command(user, "scene", TwinDriveEngineTest.command("reset"));
    assertEquals("twin_command_rate_limited", assertThrows(ApiException.class, () -> runtime.command(user, "scene", TwinDriveEngineTest.command("reset"))).code);
    var state = TwinDriveEngine.initial("scene", 1, 1000);
    assertEquals("twin_lease_expired", assertThrows(ApiException.class, () -> runtime.persist(new TwinDriveRuntime.Context(document, state, "missing-lease", "expired"))).code);
  }

  @Test void automaticSchedulerRunsWithoutObserversAndReadsNeverIntegrateOrIssueCommands() {
    document.set("config", TwinDriveEngineTest.automaticConfig(true));
    doAnswer(call -> document.deepCopy()).when(documents).stored("tenant", "scene");
    when(projects.db.queryForList("SELECT id FROM projects WHERE tenant_id=? AND id=? FOR UPDATE", "tenant", "scene"))
        .thenReturn(List.of(Map.of("id", "scene")));
    long start = System.currentTimeMillis();
    for (long now = start; now <= start + 1400; now += 100) assertTrue(runtime.automaticTick("tenant", "scene", now));
    String key = TwinDriveRuntime.key("tenant", "scene") + ":state";
    String before = store.get(key);
    var first = runtime.snapshot(user, "scene"); var second = runtime.snapshot(user, "scene");
    assertEquals(first, second); assertEquals(before, store.get(key));
    assertTrue(first.path("points").path("lift").path("value").asDouble() > 1);
    assertEquals("plant/lift/height", first.path("points").path("lift").path("topic").asText());
    assertEquals("twin_automatic_mode", assertThrows(ApiException.class, () -> runtime.command(user, "scene", TwinDriveEngineTest.command("reset"))).code);
    assertEquals(before, store.get(key)); verifyNoInteractions(auth);
    // A second API process at the same clock instant cannot integrate twice.
    var replica = new TwinDriveRuntime(TwinDriveControllerTest.proxied(documents), projects, contracts, redis);
    assertFalse(replica.automaticTick("tenant", "scene", start + 1400)); assertEquals(before, store.get(key));
    document.put("revision", 2); ((ObjectNode) document.path("config").path("simulation")).put("enabled", false);
    assertFalse(runtime.automaticTick("tenant", "scene", start + 1500));
    var stopped = runtime.snapshot(user, "scene"); assertEquals("idle", stopped.path("status").asText()); assertTrue(stopped.path("points").isEmpty());
  }

  @Test void subscriptionsValidateProjectRevisionExactTopicsAndReadPermissionsWithoutStartingSimulation() {
    document.set("config", TwinDriveEngineTest.automaticConfig(true));
    var request = Json.obj("type", "subscribe", "expectedRevision", 1, "topics", List.of("plant/lift/height"));
    var ack = runtime.subscribe(user, "scene", request);
    assertEquals("subscribed", ack.path("type").asText()); assertEquals(request.path("topics"), ack.path("topics"));
    assertTrue(store.isEmpty());
    for (var topics : List.of(List.of(), List.of("other/project/topic"), List.of("plant/#"), List.of("plant/lift/height", "plant/lift/height"))) {
      var invalid = request.deepCopy(); invalid.set("topics", Json.M.valueToTree(topics));
      assertEquals("twin_topic_subscription_invalid", assertThrows(ApiException.class, () -> runtime.subscribe(user, "scene", invalid)).code);
    }
    assertEquals("twin_revision_conflict", assertThrows(ApiException.class, () -> runtime.subscribe(user, "scene", request.deepCopy().put("expectedRevision", 0))).code);
    doThrow(new ApiException(404, "project_not_found", "Project is inaccessible")).when(projects).access(user, "scene", false);
    assertEquals("project_not_found", assertThrows(ApiException.class, () -> runtime.subscribe(user, "scene", request)).code);
  }

  @Test void backgroundDiscoveryStartsPersistedTenantScopeWithoutAnyUserOrObserver() {
    document.set("config", TwinDriveEngineTest.automaticConfig(true));
    doReturn(List.of(new TwinDriveDocuments.AutomaticProject("tenant", "scene"))).when(documents).automaticProjects();
    doAnswer(call -> document.deepCopy()).when(documents).stored("tenant", "scene");
    when(projects.db.queryForList("SELECT id FROM projects WHERE tenant_id=? AND id=? FOR UPDATE", "tenant", "scene"))
        .thenReturn(List.of(Map.of("id", "scene")));
    runtime.simulateAutomatically();
    String stored = store.get(TwinDriveRuntime.key("tenant", "scene") + ":state");
    assertNotNull(stored); assertEquals("running", Json.parse(stored).path("snapshot").path("status").asText());
    verify(documents, never()).read(any(), anyString());
    verify(projects, never()).access(any(), anyString(), anyBoolean());
    verifyNoInteractions(auth);
    runtime.lastDiscoveryMs = 0;
    doReturn(List.of()).when(documents).automaticProjects();
    runtime.simulateAutomatically();
    assertEquals(stored, store.get(TwinDriveRuntime.key("tenant", "scene") + ":state"));
    assertTrue(runtime.automaticProjects.isEmpty());
  }

  @Test void parallelSubscriptionsAndAutomaticReadsNeverContendForSchedulersMutationLease() throws Exception {
    document.set("config", TwinDriveEngineTest.automaticConfig(true));
    String key = TwinDriveRuntime.key("tenant", "scene");
    var state = TwinDriveEngine.initial("scene", 1, 1000);
    TwinDriveEngine.automaticTick(state, document.path("config"), 1000);
    store.put(key + ":state", state.toString());
    store.put(key + ":lock", "active-scheduler-owner");
    var request = Json.obj("type", "subscribe", "expectedRevision", 1, "topics", List.of("plant/lift/height"));
    try (var observers = Executors.newFixedThreadPool(2)) {
      List<Future<?>> tasks = new ArrayList<>();
      for (int observer = 0; observer < 2; observer++) tasks.add(observers.submit(() -> {
        for (int attempt = 0; attempt < 20; attempt++) {
          assertEquals("subscribed", runtime.subscribe(user, "scene", request).path("type").asText());
          assertEquals(TwinDriveEngine.snapshot(state).toString(), runtime.snapshot(user, "scene").toString());
        }
      }));
      for (var task : tasks) task.get(3, TimeUnit.SECONDS);
    }
    assertEquals("twin_automatic_mode", assertThrows(ApiException.class, () -> runtime.command(user, "scene", TwinDriveEngineTest.command("resume"))).code);
    assertEquals(state.toString(), store.get(key + ":state"));
    assertEquals("active-scheduler-owner", store.get(key + ":lock"));
    verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    document.put("revision", 2);
    assertEquals("twin_revision_conflict", assertThrows(ApiException.class, () -> runtime.subscribe(user, "scene", request)).code);
    assertEquals(2, runtime.snapshot(user, "scene").path("revision").asLong());
    assertEquals(state.toString(), store.get(key + ":state"), "Reading the new revision must not initialize the producer");
  }
}
