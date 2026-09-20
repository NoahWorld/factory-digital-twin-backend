package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
public class DataConfiguration {
  final Projects p;
  final Contracts c;
  final SourceClient source;

  DataConfiguration(Projects p, Contracts c, SourceClient source) {
    this.p = p;
    this.c = c;
    this.source = source;
  }

  static final Map<String, String> TYPES =
      Map.of("assets", "assets", "data-sources", "data_sources");

  ObjectNode present(Map<String, Object> row) {
    ObjectNode body = (ObjectNode) Json.parse(row.get("body").toString());
    body.put("id", (String) row.get("id"))
        .put("projectId", (String) row.get("project_id"))
        .put("createdAt", Json.timestamp(row.get("created_at")))
        .put("updatedAt", Json.timestamp(row.get("updated_at")));
    return body;
  }

  Map<String, Object> row(Auth.User u, String project, String type, String id) {
    String table = TYPES.get(type);
    Json.require(table != null, "Unknown data collection.");
    var rows =
        p.db.queryForList(
            "SELECT * FROM " + table + " WHERE tenant_id=? AND project_id=? AND id=?",
            u.tenant(),
            project,
            id);
    if (rows.isEmpty())
      throw new ApiException(404, "record_not_found", "Record not found in this project.");
    return rows.getFirst();
  }

  void validate(String type, ObjectNode body) {
    if (type.equals("assets")) {
      if (!body.has("metadata")) body.set("metadata", Json.obj());
      if (!body.has("modelNode")) body.putNull("modelNode");
      c.validate("AssetCreateInput", body);
      Json.require(
          Json.text(body, "assetId", 1, 80).matches("[A-Za-z0-9][A-Za-z0-9._:-]*"),
          "Invalid business asset identifier.");
      Json.text(body, "name", 1, 120);
      Json.require(
          Json.text(body, "assetType", 1, 64).matches("[A-Za-z0-9][A-Za-z0-9_-]*"),
          "Invalid assetType.");
      Json.require(
          body.path("metadata").toString().length() <= 16384, "Asset metadata exceeds budget.");
    } else {
      c.validate("DataSourceCreateInput", body);
      Json.text(body, "name", 1, 100);
      if (!body.path("sourceType").asText().equals("rest_polling"))
        throw new ApiException(
            501,
            "upstream_websocket_not_implemented",
            "This release collects HTTP sources. Browser WebSocket subscriptions are available;"
                + " upstream WebSocket adapters are not yet implemented.");
      JsonNode cfg = body.path("config");
      Json.integer(cfg, "intervalSeconds", 1, 3600);
      Json.integer(cfg, "timeoutMs", 100, 15000);
      source.validate(cfg);
    }
  }

  ObjectNode save(Auth.User u, String project, String type, String id, ObjectNode input) {
    p.access(u, project, true);
    return p.tx.execute(
        st -> {
          p.lock(u, project);
          p.access(u, project, true);
          boolean create = id == null;
          String record = create ? Json.id() : id;
          ObjectNode body =
              create
                  ? input.deepCopy()
                  : (ObjectNode) Json.parse(row(u, project, type, id).get("body").toString());
          input.fields().forEachRemaining(e -> body.set(e.getKey(), e.getValue()));
          validate(type, body);
          String table = TYPES.get(type);
          if (type.equals("assets")) {
            if (create)
              p.db.update(
                  "INSERT INTO assets(id,tenant_id,project_id,asset_key,body)"
                      + " VALUES(?,?,?,?,?::jsonb)",
                  record,
                  u.tenant(),
                  project,
                  body.path("assetId").asText(),
                  body.toString());
            else
              p.db.update(
                  "UPDATE assets SET asset_key=?,body=?::jsonb,updated_at=now() WHERE tenant_id=?"
                      + " AND project_id=? AND id=?",
                  body.path("assetId").asText(),
                  body.toString(),
                  u.tenant(),
                  project,
                  record);
          } else if (create)
            p.db.update(
                "INSERT INTO data_sources(id,tenant_id,project_id,body) VALUES(?,?,?,?::jsonb)",
                record,
                u.tenant(),
                project,
                body.toString());
          else
            p.db.update(
                "UPDATE data_sources SET"
                    + " body=?::jsonb,updated_at=now(),generation=generation+1,lease_owner=NULL,lease_until=NULL,next_poll_at=now()"
                    + " WHERE tenant_id=? AND project_id=? AND id=?",
                body.toString(),
                u.tenant(),
                project,
                record);
          p.auth.audit(u, project, type + ".save", Json.obj("id", record));
          return present(row(u, project, type, record));
        });
  }

  ObjectNode binding(Map<String, Object> row) {
    ObjectNode b = (ObjectNode) Json.parse(row.get("body").toString());
    b.put("id", (String) row.get("id"))
        .put("assetRecordId", (String) row.get("asset_id"))
        .put("createdAt", Json.timestamp(row.get("created_at")))
        .put("updatedAt", Json.timestamp(row.get("updated_at")));
    JsonNode src = Json.parse(row.get("source_body").toString());
    b.set("dataSourceName", src.path("name"));
    b.set("dataSourceType", src.path("sourceType"));
    return b;
  }

  List<Map<String, Object>> bindingRows(Auth.User u, String project, String asset) {
    return p.db.queryForList(
        "SELECT b.*,s.body AS source_body FROM data_bindings b JOIN data_sources s ON"
            + " b.source_id=s.id AND b.tenant_id=s.tenant_id WHERE b.tenant_id=? AND b.project_id=?"
            + " AND b.asset_id=? ORDER BY b.created_at",
        u.tenant(),
        project,
        asset);
  }

  ObjectNode saveBinding(Auth.User u, String project, String asset, String id, ObjectNode input) {
    p.access(u, project, true);
    return p.tx.execute(
        st -> {
          p.lock(u, project);
          p.access(u, project, true);
          row(u, project, "assets", asset);
          boolean create = id == null;
          String record = create ? Json.id() : id;
          ObjectNode body = input.deepCopy();
          if (!create) {
            var rows =
                bindingRows(u, project, asset).stream()
                    .filter(x -> x.get("id").equals(id))
                    .toList();
            if (rows.isEmpty())
              throw new ApiException(404, "binding_not_found", "Binding not found.");
            body = (ObjectNode) Json.parse(rows.getFirst().get("body").toString());
            body.setAll(input);
          }
          if (!body.has("unit")) body.putNull("unit");
          c.validate("AssetDataBindingCreateInput", body);
          String sourceId = Json.text(body, "dataSourceId", 1, 100);
          row(u, project, "data-sources", sourceId);
          Json.require(
              Json.text(body, "metricKey", 1, 80).matches("[A-Za-z][A-Za-z0-9._:-]*"),
              "Invalid metric key.");
          SourceClient.pathSyntax(Json.text(body, "sourcePath", 1, 240));
          Json.integer(body, "staleAfterSeconds", 1, 86400);
          p.db.update(
              "INSERT INTO"
                  + " data_bindings(id,tenant_id,project_id,asset_id,source_id,metric_key,body)"
                  + " VALUES(?,?,?,?,?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET"
                  + " source_id=excluded.source_id,metric_key=excluded.metric_key,body=excluded.body,updated_at=now()",
              record,
              u.tenant(),
              project,
              asset,
              sourceId,
              body.path("metricKey").asText(),
              body.toString());
          return binding(
              bindingRows(u, project, asset).stream()
                  .filter(x -> x.get("id").equals(record))
                  .findFirst()
                  .orElseThrow());
        });
  }
}

@RestController
@RequestMapping("/api/v1/projects/{project}")
class DataController {
  final DataConfiguration d;
  final RuntimeState runtime;

  DataController(DataConfiguration d, RuntimeState runtime) {
    this.d = d;
    this.runtime = runtime;
  }

  @GetMapping("/{type:assets|data-sources}")
  Object list(
      @PathVariable String project,
      @PathVariable String type,
      HttpServletRequest r,
      @RequestParam(required = false) Integer limit,
      @RequestParam(defaultValue = "0") int offset) {
    var u = d.p.auth.require(r);
    d.p.access(u, project, false);
    int size = limit == null ? 10000 : limit;
    Json.require(
        size >= 1 && size <= 10000 && offset >= 0, "Invalid pagination; limit must be 1..10000.");
    var rows =
        d.p.db.queryForList(
            "SELECT * FROM "
                + DataConfiguration.TYPES.get(type)
                + " WHERE tenant_id=? AND project_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
            u.tenant(),
            project,
            size + 1,
            offset);
    boolean more = rows.size() > size;
    if (more && limit == null)
      throw new ApiException(
          413,
          "pagination_required",
          "More than 10000 records; request explicit limit and offset.");
    return Json.obj(
        type.equals("assets") ? "assets" : "dataSources",
        rows.stream().limit(size).map(d::present).toList(),
        "nextOffset",
        more ? offset + size : null);
  }

  @PostMapping("/{type:assets|data-sources}")
  @ResponseStatus(HttpStatus.CREATED)
  Object create(
      @PathVariable String project,
      @PathVariable String type,
      @RequestBody ObjectNode b,
      HttpServletRequest r) {
    return Json.obj(
        type.equals("assets") ? "asset" : "dataSource",
        d.save(d.p.auth.require(r), project, type, null, b));
  }

  @PatchMapping("/{type:assets|data-sources}/{id}")
  Object patch(
      @PathVariable String project,
      @PathVariable String type,
      @PathVariable String id,
      @RequestBody ObjectNode b,
      HttpServletRequest r) {
    return Json.obj(
        type.equals("assets") ? "asset" : "dataSource",
        d.save(d.p.auth.require(r), project, type, id, b));
  }

  @PostMapping("/data-sources/{id}/probe")
  Object probe(@PathVariable String project, @PathVariable String id, HttpServletRequest r) {
    var u = d.p.auth.require(r);
    d.p.access(u, project, true);
    JsonNode src = Json.parse(d.row(u, project, "data-sources", id).get("body").toString());
    return Json.obj("probe", d.source.probe(id, src));
  }

  @GetMapping("/assets/{asset}/data-bindings")
  Object bindings(@PathVariable String project, @PathVariable String asset, HttpServletRequest r) {
    var u = d.p.auth.require(r);
    d.p.access(u, project, false);
    d.row(u, project, "assets", asset);
    return Json.obj(
        "dataBindings", d.bindingRows(u, project, asset).stream().map(d::binding).toList());
  }

  @PostMapping("/assets/{asset}/data-bindings")
  @ResponseStatus(HttpStatus.CREATED)
  Object createBinding(
      @PathVariable String project,
      @PathVariable String asset,
      @RequestBody ObjectNode b,
      HttpServletRequest r) {
    return Json.obj("dataBinding", d.saveBinding(d.p.auth.require(r), project, asset, null, b));
  }

  @PatchMapping("/assets/{asset}/data-bindings/{id}")
  Object updateBinding(
      @PathVariable String project,
      @PathVariable String asset,
      @PathVariable String id,
      @RequestBody ObjectNode b,
      HttpServletRequest r) {
    return Json.obj("dataBinding", d.saveBinding(d.p.auth.require(r), project, asset, id, b));
  }

  @DeleteMapping("/assets/{asset}/data-bindings/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteBinding(
      @PathVariable String project,
      @PathVariable String asset,
      @PathVariable String id,
      HttpServletRequest r) {
    var u = d.p.auth.require(r);
    d.p.access(u, project, true);
    int n =
        d.p.db.update(
            "DELETE FROM data_bindings WHERE tenant_id=? AND project_id=? AND asset_id=? AND id=?",
            u.tenant(),
            project,
            asset,
            id);
    if (n == 0) throw new ApiException(404, "binding_not_found", "Binding not found.");
  }

  @GetMapping("/assets/{asset}/runtime-state")
  Object state(@PathVariable String project, @PathVariable String asset, HttpServletRequest r) {
    return Json.obj("runtimeState", runtime.asset(d.p.auth.require(r), project, asset));
  }

  @GetMapping("/runtime/snapshot")
  Object snapshot(@PathVariable String project, HttpServletRequest r) {
    var u = d.p.auth.require(r);
    d.p.access(u, project, false);
    return runtime.snapshot(u.tenant(), project);
  }
}
