package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import org.slf4j.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** A bounded, recoverable simulator state. Redis fencing prevents two API processes integrating twice. */
@Service
public class TwinDriveRuntime {
  final TwinDriveDocuments documents;
  final Projects projects;
  final Contracts contracts;
  final StringRedisTemplate redis;
  final Logger log = LoggerFactory.getLogger(TwinDriveRuntime.class);
  volatile List<TwinDriveDocuments.AutomaticProject> automaticProjects = List.of();
  long lastDiscoveryMs;
  static final Duration LEASE = Duration.ofSeconds(5);
  static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>(
      "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end redis.call('SET',KEYS[2],ARGV[2],'EX',86400) return 1", Long.class);
  static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
      "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0", Long.class);

  public TwinDriveRuntime(TwinDriveDocuments documents, Projects projects, Contracts contracts, StringRedisTemplate redis) {
    this.documents = documents; this.projects = projects; this.contracts = contracts; this.redis = redis;
  }

  static String key(String tenant, String project) { return RuntimeState.key(tenant, project) + ":drive"; }

  record Context(ObjectNode document, ObjectNode state, String key, String lease) {}
  record StreamFrame(ObjectNode snapshot, List<String> topics) {}

  /** Serialize configuration reads with saves/ticks, without taking the simulator's mutation lease. */
  <T> T readLocked(Auth.User user, String project, boolean write, Function<ObjectNode, T> operation) {
    return projects.tx.execute(status -> {
      projects.lock(user, project);
      projects.access(user, project, write);
      return operation.apply(documents.read(user, project));
    });
  }

  <T> T locked(Auth.User user, String project, boolean write, Function<Context, T> operation) {
    String key = key(user.tenant(), project), token = Json.id();
    if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key + ":lock", token, LEASE)))
      throw new ApiException(409, "twin_runtime_busy", "The simulator is processing another operation; retry with the same command ID.");
    try {
      return projects.tx.execute(status -> {
        // Config saves and scene edits take the same project lock. Validate before advancing state.
        projects.lock(user, project);
        projects.access(user, project, write);
        ObjectNode document = documents.read(user, project);
        Context context = context(document, project, key, token);
        return operation.apply(context);
      });
    } finally {
      Long released = redis.execute(RELEASE, List.of(key + ":lock"), token);
      if (!Long.valueOf(1).equals(released)) log.warn("twin_lease_expired tenant={} project={}", user.tenant(), project);
    }
  }

  Context context(ObjectNode document, String project, String key, String token) {
    return new Context(document, state(document, project, key), key, token);
  }

  ObjectNode state(ObjectNode document, String project, String key) {
    String stored = redis.opsForValue().get(key + ":state");
    ObjectNode state = stored == null ? null : (ObjectNode) Json.parse(stored);
    if (state == null || TwinDriveEngine.snapshot(state).path("revision").asLong() != document.path("revision").asLong())
      state = TwinDriveEngine.initial(project, document.path("revision").asLong(), System.currentTimeMillis());
    return state;
  }

  /** Scheduler-only entry: persisted tenant scope and a project row lock, not request/user impersonation. */
  boolean automaticTick(String tenant, String project, long now) {
    String key = key(tenant, project), token = Json.id();
    if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key + ":lock", token, LEASE))) return false;
    try {
      return Boolean.TRUE.equals(projects.tx.execute(status -> {
        var rows = projects.db.queryForList("SELECT id FROM projects WHERE tenant_id=? AND id=? FOR UPDATE", tenant, project);
        if (rows.isEmpty()) return false; // Removed project: no state or points are invented.
        ObjectNode document = documents.stored(tenant, project);
        if (!TwinDriveEngine.automatic(document.path("config"))) return false;
        Context context = context(document, project, key, token);
        String before = TwinDriveEngine.snapshot(context.state()).path("status").asText();
        boolean changed = TwinDriveEngine.automaticTick(context.state(), document.path("config"), now);
        if (changed) persist(context);
        String after = TwinDriveEngine.snapshot(context.state()).path("status").asText();
        if (!before.equals(after)) log.info("twin_automatic_status tenant={} project={} revision={} previous={} status={} procedure={}",
            tenant, project, document.path("revision"), before, after, TwinDriveEngine.snapshot(context.state()).path("procedure"));
        return changed;
      }));
    } finally {
      Long released = redis.execute(RELEASE, List.of(key + ":lock"), token);
      if (!Long.valueOf(1).equals(released)) log.warn("twin_automatic_lease_expired tenant={} project={}", tenant, project);
    }
  }

  public void simulateAutomatically() {
    long now = System.currentTimeMillis();
    if (now - lastDiscoveryMs >= 1000) {
      try { automaticProjects = documents.automaticProjects(); lastDiscoveryMs = now; }
      catch (Exception error) {
        // The previous known set is still revalidated under DB locks below. No configured state is guessed.
        log.error("twin_automatic_discovery_failed knownProjects={}", automaticProjects.size(), error);
      }
    }
    for (var project : automaticProjects) {
      try { automaticTick(project.tenant(), project.project(), System.currentTimeMillis()); }
      catch (Exception error) { log.error("twin_automatic_tick_failed tenant={} project={}", project.tenant(), project.project(), error); }
    }
  }

  void persist(Context context) {
    Long written = redis.execute(WRITE, List.of(context.key() + ":lock", context.key() + ":state"), context.lease(), context.state().toString());
    if (!Long.valueOf(1).equals(written))
      throw new ApiException(409, "twin_lease_expired", "Simulator ownership expired; state was not written. Retry the same command ID.");
  }

  public ObjectNode snapshot(Auth.User user, String project) {
    return frame(user, project).snapshot();
  }

  static List<String> topics(JsonNode config) {
    List<String> topics = new ArrayList<>();
    config.path("points").forEach(point -> { if (point.has("topic")) topics.add(point.path("topic").asText()); });
    return List.copyOf(topics);
  }

  public ObjectNode subscribe(Auth.User user, String project, ObjectNode request) {
    Json.fields(request, "type", "expectedRevision", "topics");
    long expected = Json.integer(request, "expectedRevision", 0, 9007199254740990L);
    if (!request.path("topics").isArray() || request.path("topics").size() > 128)
      throw new ApiException(400, "twin_topic_subscription_invalid", "Topics must be an array of at most 128 exact configured topics.");
    Set<String> supplied = new LinkedHashSet<>();
    for (JsonNode topic : request.path("topics"))
      if (!topic.isTextual() || !topic.asText().matches("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$") || !supplied.add(topic.asText()))
        throw new ApiException(400, "twin_topic_subscription_invalid", "Topics must be unique exact topics without wildcards.");
    return readLocked(user, project, false, document -> {
      if (document.path("revision").asLong() != expected)
        throw new ApiException(409, "twin_revision_conflict", "Saved topic configuration changed; reload before subscribing.");
      List<String> configured = topics(document.path("config"));
      if (!supplied.equals(new HashSet<>(configured)))
        throw new ApiException(400, "twin_topic_subscription_invalid", "Subscribe to the complete exact topic set configured for this project.");
      return Json.obj("type", "subscribed", "revision", expected, "topics", configured);
    });
  }

  public StreamFrame frame(Auth.User user, String project) {
    Optional<StreamFrame> automaticFrame = readLocked(user, project, false, document -> {
      if (!TwinDriveEngine.automatic(document.path("config"))) return Optional.empty();
      ObjectNode state = state(document, project, key(user.tenant(), project));
      return Optional.of(new StreamFrame(TwinDriveEngine.snapshot(state).deepCopy(), topics(document.path("config"))));
    });
    if (automaticFrame.isPresent()) return automaticFrame.get();
    // Legacy observation still integrates; acquire its mutation lease before the DB row lock.
    // Recheck the saved mode inside the second transaction in case a save enabled automatic mode.
    return locked(user, project, false, context -> {
      long before = TwinDriveEngine.snapshot(context.state()).path("sequence").asLong();
      String previousStatus = TwinDriveEngine.snapshot(context.state()).path("status").asText();
      boolean automatic = TwinDriveEngine.automatic(context.document().path("config"));
      if (!automatic) TwinDriveEngine.tick(context.state(), context.document().path("config"), System.currentTimeMillis());
      String status = TwinDriveEngine.snapshot(context.state()).path("status").asText();
      if (!status.equals(previousStatus)) log.info("twin_status_changed tenant={} project={} previous={} status={} sequence={}",
          user.tenant(), project, previousStatus, status, before);
      if (!automatic) persist(context);
      return new StreamFrame(TwinDriveEngine.snapshot(context.state()).deepCopy(), topics(context.document().path("config")));
    });
  }

  public ObjectNode command(Auth.User user, String project, ObjectNode command) {
    contracts.validate("TwinDriveCommand", command);
    TwinDriveEngine.validateCommand(command);
    // A rejected automatic-mode command is read-only and must not race the producer for its lease.
    readLocked(user, project, true, document -> { rejectAutomaticCommand(document); return true; });
    return locked(user, project, true, context -> {
      rejectAutomaticCommand(context.document());
      ObjectNode state = context.state();
      ArrayNode history = (ArrayNode) state.path("commands");
      for (JsonNode entry : history) if (entry.path("command").path("commandId").equals(command.path("commandId"))) {
        if (!entry.path("command").equals(command))
          throw new ApiException(409, "twin_command_id_conflict", "This command ID was already used for different content.");
        return (ObjectNode) entry.path("ack").deepCopy();
      }
      long now = System.currentTimeMillis();
      if (now - state.path("rateWindowMs").asLong() >= 1000) state.put("rateWindowMs", now).put("rateCount", 0);
      if (state.path("rateCount").asInt() >= 20)
        throw new ApiException(429, "twin_command_rate_limited", "At most 20 simulator commands per project per second.");
      documents.validateReferences(user, project, context.document().path("config"));
      ObjectNode ack = TwinDriveEngine.command(state, context.document().path("config"), command, now);
      state.put("rateCount", state.path("rateCount").asInt() + 1);
      history.add(Json.obj("command", command, "ack", ack));
      while (history.size() > 128) history.remove(0);
      persist(context);
      projects.auth.audit(user, project, "twin_drive.command", Json.obj("commandId", command.path("commandId"),
          "operation", command.path("operation"), "revision", command.path("expectedRevision"), "sequence", ack.path("sequence"),
          "values", command.has("values") ? command.path("values") : Json.M.nullNode(),
          "procedureId", command.has("procedureId") ? command.path("procedureId") : Json.M.nullNode()));
      log.info("twin_command_applied tenant={} project={} command={} operation={} sequence={}",
          user.tenant(), project, command.path("commandId").asText(), command.path("operation").asText(), ack.path("sequence").asLong());
      return ack;
    });
  }

  static void rejectAutomaticCommand(ObjectNode document) {
    if (TwinDriveEngine.automatic(document.path("config")))
      throw new ApiException(409, "twin_automatic_mode", "The server simulator runs independently. Disable automatic simulation in configuration before sending manual commands.");
  }
}
