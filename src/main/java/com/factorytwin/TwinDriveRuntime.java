package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Service;

/** Authenticated gateway over actual API observations. Legacy simulators never run. */
@Service
public class TwinDriveRuntime {
  final TwinDriveDocuments documents;
  final Projects projects;
  final ApiMotionSources sources;
  public TwinDriveRuntime(TwinDriveDocuments documents, Projects projects, ApiMotionSources sources) {
    this.documents = documents; this.projects = projects; this.sources = sources;
  }
  static String key(String tenant, String project) { return RuntimeState.key(tenant, project) + ":drive"; }
  record StreamFrame(ObjectNode snapshot, List<String> topics) {}
  static List<String> topics(JsonNode config) { return List.of(); }
  static void requireApi(JsonNode config) {
    if (!config.path("source").asText().equals("api")) throw new ApiException(410, "legacy_simulation_removed", "Platform simulation was removed; connect a business API.");
  }
  public ObjectNode subscribe(Auth.User user, String project, ObjectNode request) {
    Json.fields(request, "type", "expectedRevision", "topics");
    long revision = Json.integer(request, "expectedRevision", 0, 9007199254740990L);
    ObjectNode document = documents.read(user, project); requireApi(document.path("config"));
    if (document.path("revision").asLong() != revision) throw new ApiException(409, "twin_revision_conflict", "Saved API configuration changed; reload before subscribing.");
    Json.require(!request.has("topics") || (request.path("topics").isArray() && request.path("topics").isEmpty()), "API sources do not use topic subscriptions.");
    return Json.obj("type", "subscribed", "revision", revision, "topics", List.of());
  }
  public ObjectNode snapshot(Auth.User user, String project) { return frame(user, project).snapshot(); }
  public StreamFrame frame(Auth.User user, String project) {
    ObjectNode read = documents.read(user, project); requireApi(read.path("config"));
    ObjectNode stored = documents.stored(user.tenant(), project); requireApi(stored.path("config"));
    return new StreamFrame(sources.snapshot(user.tenant(), project, stored), List.of());
  }
  public ObjectNode command(Auth.User user, String project, ObjectNode command) {
    projects.access(user, project, true);
    throw new ApiException(410, "legacy_simulation_removed", "Platform simulation commands were removed; use business API observations.");
  }
}
