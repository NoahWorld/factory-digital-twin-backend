package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded scene declarations stored alongside settings, never in model instance bodies. */
final class SceneExtensions {
  private SceneExtensions() {}
  static final List<String> FIELDS = List.of("decorations", "roomAlarms", "staticMap");
  static final int SETTINGS_BYTES = 1024 * 1024;
  record Budget(long instances, long meshes, long triangles, long animated) {
    Budget plus(Budget other) { return new Budget(instances + other.instances, meshes + other.meshes,
        triangles + other.triangles, animated + other.animated); }
  }
  static final Budget ZERO = new Budget(0, 0, 0, 0);

  static boolean changed(JsonNode patch) { return FIELDS.stream().anyMatch(patch::has); }

  static void shallowBudget(JsonNode patch) {
    record Entry(JsonNode node, int depth) {}
    Deque<Entry> pending = new ArrayDeque<>();
    pending.add(new Entry(patch, 0));
    int visited = 0;
    while (!pending.isEmpty()) {
      Entry entry = pending.removeLast();
      Json.require(++visited <= 50000 && entry.depth <= 20,
          "Scene patch exceeds nesting or element budget.");
      JsonNode node = entry.node;
      if (node.isArray()) {
        Json.require(node.size() <= 4096, "Scene patch array exceeds element budget.");
        for (JsonNode child : node) pending.add(new Entry(child, entry.depth + 1));
      } else if (node.isObject()) {
        Json.require(node.size() <= 200, "Scene patch object exceeds field budget.");
        node.elements().forEachRemaining(child -> pending.add(new Entry(child, entry.depth + 1)));
      } else if (node.isTextual())
        Json.require(node.asText().length() <= SETTINGS_BYTES, "Scene patch string exceeds byte budget.");
    }
    for (String field : FIELDS) if (patch.has(field)) {
      JsonNode value = patch.get(field);
      if (field.equals("decorations") || field.equals("roomAlarms"))
        Json.require(value.isArray() && value.size() <= 64, field + " must have at most 64 entries.");
      if (field.equals("staticMap") && value.isObject())
        Json.require(value.path("features").isArray() && value.path("features").size() <= 64,
            "staticMap must have 1..64 features.");
      Json.require(value.toString().getBytes(StandardCharsets.UTF_8).length <= SETTINGS_BYTES,
          field + " exceeds the scene JSON budget.");
    }
  }

  static JsonNode fromStorage(ObjectNode stored, String field) {
    if (!stored.has(field)) return field.equals("staticMap") ? Json.M.nullNode() : Json.M.createArrayNode();
    JsonNode value = stored.get(field);
    try {
      shallowBudget(Json.obj(field, value));
      validate(field, value);
    }
    catch (ApiException error) {
      throw new ApiException(500, "invalid_scene_storage", "Stored " + field + " is invalid: " + error.getMessage());
    }
    return value.deepCopy();
  }

  static void validate(String field, JsonNode value) {
    switch (field) {
      case "decorations" -> decorations(value);
      case "roomAlarms" -> roomAlarms(value);
      case "staticMap" -> StaticMapValidation.validate(value);
      default -> throw new IllegalArgumentException(field);
    }
  }

  private static void keys(JsonNode n, String... names) {
    Json.require(n != null && n.isObject() && n.size() == names.length, "Missing or unknown scene fields.");
    for (String name : names) Json.require(n.has(name), "Missing scene field: " + name);
  }
  private static void ident(JsonNode n, int maximum, String label) {
    Json.require(n != null && n.isTextual() && n.asText().length() <= maximum
        && n.asText().matches("[A-Za-z0-9][A-Za-z0-9._:-]*"), label + " is invalid.");
  }
  private static void printable(JsonNode n, int maximum, String label, boolean trimRequired) {
    Json.require(n != null && n.isTextual() && !n.asText().isBlank()
        && n.asText().length() <= maximum && (!trimRequired || n.asText().equals(n.asText().trim()))
        && n.asText().codePoints().noneMatch(c -> c < 32 || (c >= 127 && c <= 159)),
        label + " must be printable.");
  }
  private static void color(JsonNode n) {
    Json.require(n != null && n.isTextual() && n.asText().matches("#[0-9a-fA-F]{6}"), "Invalid color.");
  }
  private static void bool(JsonNode n) { Json.require(n != null && n.isBoolean(), "Expected boolean."); }
  private static void vector(JsonNode n, double min, double max) {
    Json.require(n != null && n.isArray() && n.size() == 3, "Expected three coordinates.");
    for (JsonNode part : n) Contracts.number(part, min, max, "coordinate");
  }
  private static void transform(JsonNode n) {
    keys(n, "position", "rotation", "scale");
    vector(n.path("position"), -10000, 10000);
    vector(n.path("rotation"), -3600, 3600);
    vector(n.path("scale"), .001, 100);
  }

  static Budget decorations(JsonNode value) {
    Json.require(value.isArray() && value.size() <= 64, "decorations must be an array of at most 64 entries.");
    Set<String> ids = new HashSet<>();
    Budget budget = ZERO;
    for (JsonNode d : value) {
      String kind = d.path("kind").asText("");
      Json.require(Set.of("tree", "shrub", "river", "military-truck", "military-tent",
          "military-radar", "military-armored").contains(kind), "Invalid decoration kind.");
      if (kind.equals("river")) keys(d, "id", "label", "kind", "visible", "transform", "color", "accentColor", "seed", "river");
      else keys(d, "id", "label", "kind", "visible", "transform", "color", "accentColor", "seed");
      ident(d.path("id"), 120, "Decoration id");
      Json.require(ids.add(d.path("id").asText()), "Duplicate decoration id.");
      printable(d.path("label"), 80, "Decoration label", false);
      bool(d.path("visible")); color(d.path("color")); color(d.path("accentColor"));
      Json.integer(d, "seed", 0, 0xffffffffL); transform(d.path("transform"));
      long meshes, triangles, animated = 0;
      switch (kind) {
        case "tree" -> { meshes = 4; triangles = 120; }
        case "shrub" -> { meshes = 3; triangles = 60; }
        case "military-tent" -> { meshes = 3; triangles = 80; }
        case "military-radar" -> { meshes = 5; triangles = 220; }
        case "river" -> {
          JsonNode r = d.path("river"); keys(r, "points", "width", "speed", "opacity", "playing");
          JsonNode points = r.path("points");
          Json.require(points.isArray() && points.size() >= 2 && points.size() <= 64, "River requires 2..64 points.");
          for (JsonNode point : points) vector(point, -10000, 10000);
          Contracts.number(r.path("width"), .1, 100, "river width");
          Contracts.number(r.path("speed"), 0, 10, "river speed");
          Contracts.number(r.path("opacity"), .05, 1, "river opacity");
          bool(r.path("playing"));
          riverGeometry(points, r.path("width").asDouble());
          meshes = 1; triangles = 2L * (points.size() - 1);
          if (r.path("playing").asBoolean()) animated = 1;
        }
        default -> { meshes = 9; triangles = 250; }
      }
      budget = budget.plus(new Budget(1, meshes, triangles, animated));
    }
    return budget;
  }

  private static double cross(double[] a, double[] b, double[] c) {
    return (b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0]);
  }
  private static boolean overlaps(double[] a, double[] b, double[] c, double[] d) {
    double e = cross(a,b,c), f = cross(a,b,d), g = cross(c,d,a), h = cross(c,d,b);
    if (Math.abs(e)<1e-9 && Math.abs(f)<1e-9 && Math.abs(g)<1e-9 && Math.abs(h)<1e-9)
      return Math.max(Math.min(a[0],b[0]),Math.min(c[0],d[0])) <= Math.min(Math.max(a[0],b[0]),Math.max(c[0],d[0]))
          && Math.max(Math.min(a[1],b[1]),Math.min(c[1],d[1])) <= Math.min(Math.max(a[1],b[1]),Math.max(c[1],d[1]));
    return e*f<=0 && g*h<=0;
  }
  private static void riverGeometry(JsonNode points, double width) {
    int count = points.size();
    double[][] p = new double[count][2], normals = new double[count-1][2];
    for (int i=0;i<count;i++) { p[i][0]=points.get(i).get(0).asDouble(); p[i][1]=points.get(i).get(2).asDouble(); }
    for (int i=1;i<count;i++) {
      double dx=p[i][0]-p[i-1][0], dz=p[i][1]-p[i-1][1], length=Math.hypot(dx,dz);
      Json.require(length>=1e-5, "River has a degenerate XZ segment.");
      normals[i-1]=new double[]{-dz/length,dx/length};
      for (int k=0;k<i-2;k++) Json.require(!overlaps(p[k],p[k+1],p[i-1],p[i]), "River crosses itself.");
    }
    double[][][] edges=new double[count][2][2];
    for (int i=0;i<count;i++) {
      double nx,nz,miter=1;
      if (i==0 || i==count-1) { double[] n=normals[i==0?0:count-2]; nx=n[0]; nz=n[1]; }
      else {
        double sx=normals[i-1][0]+normals[i][0], sz=normals[i-1][1]+normals[i][1], length=Math.hypot(sx,sz);
        Json.require(length>=1e-8, "River banks fold."); nx=sx/length; nz=sz/length;
        double projection=nx*normals[i][0]+nz*normals[i][1];
        Json.require(projection>=.25, "River miter exceeds limit."); miter=1/projection;
      }
      double x=nx*width*miter/2,z=nz*width*miter/2;
      edges[i][0]=new double[]{p[i][0]-x,p[i][1]-z}; edges[i][1]=new double[]{p[i][0]+x,p[i][1]+z};
    }
    for (int i=1;i<count;i++) {
      Json.require(cross(edges[i-1][0],edges[i-1][1],edges[i][0]) < -1e-8
          && cross(edges[i-1][1],edges[i][1],edges[i][0]) < -1e-8, "River banks fold at this width.");
    }
    double[][] perimeter=new double[count*2][];
    for (int i=0;i<count;i++) { perimeter[i]=edges[i][0]; perimeter[count+i]=edges[count-1-i][1]; }
    for (int i=0;i<perimeter.length;i++) for (int j=i+2;j<perimeter.length;j++) {
      if (i==0 && j==perimeter.length-1) continue;
      Json.require(!overlaps(perimeter[i],perimeter[(i+1)%perimeter.length],
          perimeter[j],perimeter[(j+1)%perimeter.length]), "River banks overlap.");
    }
  }

  static void roomAlarms(JsonNode value) {
    Json.require(value.isArray() && value.size() <= 64, "roomAlarms must have at most 64 rules.");
    Set<String> ids = new HashSet<>(), targets = new HashSet<>(), sources = new HashSet<>();
    for (JsonNode rule : value) {
      keys(rule, "id", "label", "enabled", "source", "target", "condition", "color");
      ident(rule.path("id"), 120, "Alarm id");
      Json.require(ids.add(rule.path("id").asText()), "Duplicate alarm id.");
      printable(rule.path("label"), 80, "Alarm label", true); bool(rule.path("enabled")); color(rule.path("color"));
      JsonNode source=rule.path("source"), target=rule.path("target"), condition=rule.path("condition");
      keys(source, "projectId", "assetId", "metricKey");
      for (String key : List.of("projectId", "assetId", "metricKey")) ident(source.path(key), 120, "Alarm source " + key);
      sources.add(source.path("projectId").asText() + "\0" + source.path("assetId").asText());
      keys(target, "instanceId", "modelAssetId", "nodeName");
      ident(target.path("instanceId"), 120, "Alarm instance"); ident(target.path("modelAssetId"), 120, "Alarm model");
      printable(target.path("nodeName"), 256, "Alarm node", true);
      Json.require(targets.add(target.path("instanceId").asText()+"\0"+target.path("modelAssetId").asText()+"\0"+target.path("nodeName").asText()), "Duplicate alarm target.");
      keys(condition, "operator", "value");
      String operator=condition.path("operator").asText("");
      Json.require(Set.of("eq", "gt", "gte", "lt", "lte").contains(operator), "Invalid alarm operator.");
      JsonNode comparison=condition.path("value");
      if (comparison.isNumber()) Contracts.number(comparison, -1e9, 1e9, "Alarm comparison");
      else if (comparison.isTextual()) Json.require(comparison.asText().length()<=120, "Alarm string exceeds 120 characters.");
      else Json.require(comparison.isBoolean(), "Invalid alarm comparison.");
      Json.require(operator.equals("eq") || comparison.isNumber(), "Ordered alarm comparison requires a number.");
    }
    Json.require(sources.size()<=50, "Room alarms exceed 50 distinct asset sources.");
  }

  static Budget budget(JsonNode stored) {
    return decorations(fromStorage((ObjectNode)stored, "decorations"))
        .plus(StaticMapValidation.budget(fromStorage((ObjectNode)stored, "staticMap")));
  }

  static void validateReferences(Projects p, Auth.User user, String sceneProject,
      String linked2dProject, JsonNode stored, List<JsonNode> instances) {
    JsonNode rules = fromStorage((ObjectNode) stored, "roomAlarms");
    Map<String, String> models = new HashMap<>();
    for (JsonNode instance : instances)
      models.put(instance.path("id").asText(), instance.path("modelAssetId").asText());
    Set<String> locked = new HashSet<>();
    for (JsonNode rule : rules) {
      JsonNode source = rule.path("source"), target = rule.path("target"), condition = rule.path("condition");
      String sourceProject = source.path("projectId").asText();
      Json.require(sourceProject.equals(sceneProject) || sourceProject.equals(linked2dProject),
          "Alarm source must be this scene or its linked 2D project.");
      if (locked.add(sourceProject) && !sourceProject.equals(sceneProject)) p.lock(user, sourceProject);
      p.access(user, sourceProject, false);
      Json.require(Objects.equals(models.get(target.path("instanceId").asText()),
          target.path("modelAssetId").asText()), "Alarm target must match a saved instance and model.");
      var rows = p.db.queryForList("SELECT b.body::text FROM assets a JOIN data_bindings b"
          + " ON b.tenant_id=a.tenant_id AND b.project_id=a.project_id AND b.asset_id=a.id"
          + " WHERE a.tenant_id=? AND a.project_id=? AND a.asset_key=? AND b.metric_key=?",
          user.tenant(), sourceProject, source.path("assetId").asText(), source.path("metricKey").asText());
      Json.require(rows.size() == 1, "Alarm source requires an existing business asset and metric binding.");
      String type = Json.parse(rows.getFirst().get("body").toString()).path("valueType").asText();
      JsonNode value = condition.path("value");
      Json.require((value.isNumber() && type.equals("number"))
          || (value.isTextual() && type.equals("string"))
          || (value.isBoolean() && type.equals("boolean")),
          "Alarm comparison value does not match the metric valueType.");
    }
  }

  static void guardSourceChange(Projects p, Auth.User user, String sourceProject,
      String assetId, String metricKey) {
    ObjectNode source = Json.obj("projectId", sourceProject, "assetId", assetId);
    if (metricKey != null) source.put("metricKey", metricKey);
    String needle = Json.obj("source", source).toString();
    Integer count = p.db.queryForObject("SELECT count(*) FROM documents d JOIN projects pr"
        + " ON pr.tenant_id=d.tenant_id AND pr.id=d.project_id"
        + " WHERE d.tenant_id=? AND pr.project_type='3d'"
        + " AND d.settings->'roomAlarms' @> jsonb_build_array(?::jsonb)", Integer.class,
        user.tenant(), needle);
    if (count != null && count > 0)
      throw new ApiException(409, "room_alarm_source_referenced",
          "Update or remove room alarm rules before changing their asset or metric source.");
  }
}
