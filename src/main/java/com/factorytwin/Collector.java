package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "twin.mode", havingValue = "collector")
public class Collector {
  final Projects p;
  final SourceClient client;
  final RuntimeState runtime;
  final String owner = Json.id();
  final Logger log = LoggerFactory.getLogger(Collector.class);

  public Collector(Projects p, SourceClient client, RuntimeState runtime) {
    this.p = p;
    this.client = client;
    this.runtime = runtime;
  }

  @Scheduled(fixedDelay = 100)
  public void collect() {
    var claim =
        p.tx.execute(
            st -> {
              var rows =
                  p.db.queryForList(
                      "SELECT * FROM data_sources WHERE next_poll_at<=now() AND (lease_until IS"
                          + " NULL OR lease_until<now()) ORDER BY next_poll_at FOR UPDATE SKIP"
                          + " LOCKED LIMIT 1");
              if (rows.isEmpty()) return null;
              var row = rows.getFirst();
              long generation = ((Number) row.get("generation")).longValue() + 1;
              p.db.update(
                  "UPDATE data_sources SET lease_owner=?,lease_until=now()+interval '30"
                      + " seconds',generation=? WHERE id=?",
                  owner,
                  generation,
                  row.get("id"));
              row.put("generation", generation);
              return row;
            });
    if (claim == null) return;
    String id = (String) claim.get("id"),
        tenant = (String) claim.get("tenant_id"),
        project = (String) claim.get("project_id");
    long generation = ((Number) claim.get("generation")).longValue();
    JsonNode source = Json.parse(claim.get("body").toString());
    JsonNode result;
    try {
      result = client.collect(source.path("config"));
    } catch (ApiException e) {
      log.warn(
          "collection_failed tenant={} project={} source={} generation={} code={} message={}",
          tenant,
          project,
          id,
          generation,
          e.code,
          e.getMessage());
      result =
          Json.obj(
              "quality",
              "offline",
              "collectedAt",
              java.time.Instant.now().toString(),
              "error",
              e.getMessage(),
              "errorCode",
              e.code);
    }
    JsonNode snapshot = result;
    p.tx.executeWithoutResult(
        st -> {
          var current =
              p.db.queryForList(
                  "SELECT id FROM data_sources WHERE id=? AND tenant_id=? AND lease_owner=? AND"
                      + " generation=? AND lease_until>now() FOR UPDATE",
                  id,
                  tenant,
                  owner,
                  generation);
          if (current.isEmpty()) {
            log.warn("Discarding stale collector result source={} generation={}", id, generation);
            return;
          }
          runtime.publish(tenant, project, id, snapshot);
          p.db.update(
              "UPDATE data_sources SET lease_until=NULL,lease_owner=NULL,next_poll_at=now()+(? *"
                  + " interval '1 second') WHERE id=? AND generation=?",
              source.path("config").path("intervalSeconds").asInt(),
              id,
              generation);
        });
  }
}
