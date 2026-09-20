package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import java.io.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class Contracts {
  private static final Logger log = LoggerFactory.getLogger(Contracts.class);
  final JsonNode root, builtins, minimumSizes, limits;
  final Map<String, JsonSchema> validators = new HashMap<>();

  public Contracts() {
    root = resource("configuration.schema.json");
    builtins = resource("builtin-models.json");
    minimumSizes = resource("canvas-minimum-sizes.json");
    limits = resource("scene-limits.json");
  }

  private JsonNode resource(String name) {
    try (InputStream in = getClass().getResourceAsStream("/contracts/" + name)) {
      if (in == null) throw new IOException("Missing " + name);
      return Json.M.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load configuration contract", e);
    }
  }

  public synchronized void validate(String type, JsonNode body) {
    var schema =
        validators.computeIfAbsent(
            type,
            k -> {
              ObjectNode definition = root.deepCopy();
              definition.put("$ref", "#/definitions/" + k);
              return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                  .getSchema(definition);
            });
    var errors = schema.validate(body);
    if (!errors.isEmpty()) {
      String message =
          errors.stream()
              .map(ValidationMessage::getMessage)
              .distinct()
              .sorted()
              .limit(5)
              .reduce((a, b) -> a + "; " + b)
              .orElseThrow();
      log.warn("schema_validation_failed contract={} details={}", type, message);
      throw new ApiException(400, "schema_validation_failed", message);
    }
  }

  public void normalizeCanvasPatch(ObjectNode patch) {
    JsonNode nodes = patch.path("upsertNodes");
    if (!nodes.isArray()) return; // Structural errors are reported by validate immediately after this.
    for (JsonNode node : nodes) {
      if (!node.path("type").asText().equals("model-3d")
          || !(node.path("props") instanceof ObjectNode props)) continue;
      root.path("definitions").path("Model3DProps").path("properties").fields()
          .forEachRemaining(field -> {
            if (!props.has(field.getKey()) && field.getValue().has("default"))
              props.set(field.getKey(), field.getValue().get("default").deepCopy());
          });
    }
  }

  public JsonNode builtin(String id) {
    for (JsonNode n : builtins) if (n.path("id").asText().equals(id)) return n;
    return null;
  }

  public static void number(JsonNode value, double min, double max, String label) {
    Json.require(
        value.isNumber()
            && Double.isFinite(value.asDouble())
            && value.asDouble() >= min
            && value.asDouble() <= max,
        label + " must be between " + min + " and " + max);
  }

  public static void identifier(String s) {
    Json.require(s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"), "Invalid stable identifier.");
  }

  public static void appearance(JsonNode node) {
    if (node.isObject())
      node.fields()
          .forEachRemaining(
              e -> {
                String k = e.getKey();
                JsonNode v = e.getValue();
                if ((k.endsWith("Color") || k.equals("color")) && !v.isNull())
                  Json.require(
                      v.isTextual() && v.asText().matches("#[0-9a-fA-F]{6}"),
                      k + " requires a six-digit hex color.");
                if (k.equals("opacity") || k.equals("backgroundOpacity")) number(v, 0, 1, k);
                if (k.equals("cameraFov")) number(v, 15, 90, k);
                if (k.equals("modelScale")) number(v, .25, 4, k);
                if (k.equals("animationSpeed")) number(v, .1, 3, k);
                if (k.equals("rotationSpeed")) number(v, 0, 5, k);
                if (k.equals("environmentLightIntensity") || k.equals("keyLightIntensity"))
                  number(v, 0, 10, k);
                if (k.equals("href") && !v.asText().isEmpty())
                  Json.require(
                      v.asText().matches("^(https?://|mailto:|tel:|/(?!/)|#).*$"),
                      "Unsafe link protocol.");
                appearance(v);
              });
    else if (node.isArray()) node.forEach(Contracts::appearance);
  }

  public void canvasNode(JsonNode n) {
    identifier(n.path("id").asText());
    String type = n.path("type").asText();
    JsonNode size = minimumSizes.path(type);
    Json.require(size.isArray(), "Unsupported canvas node type.");
    number(n.path("x"), -7680, 7680, "x");
    number(n.path("y"), -4320, 4320, "y");
    number(n.path("width"), size.get(0).asDouble(), 3840, "width");
    number(n.path("height"), size.get(1).asDouble(), 2160, "height");
    Json.integer(n, "zIndex", 0, 100000);
    if (Set.of("circle", "icon-background", "vector-icon", "radar-sweep", "energy-core")
        .contains(type))
      Json.require(
          Math.abs(n.path("width").asDouble() - n.path("height").asDouble()) <= .001,
          "Square components require equal width and height.");
    Json.require(
        n.path("props").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            <= 16384,
        "Node props exceed 16 KiB.");
    appearance(n.path("props"));
    if (type.endsWith("-chart")) {
      JsonNode props = n.path("props");
      Json.require(
          props.path("categories").size() == props.path("values").size()
              && props.path("values").size() <= 32,
          "Chart categories and values must match and contain at most 32 points.");
    }
    if (Set.of(
            "scene-3d",
            "asset-detail",
            "card-title",
            "vector-icon",
            "radar-sweep",
            "data-stream",
            "circuit-pulse",
            "energy-core",
            "industrial-flow",
            "scan-grid")
        .contains(type))
      Json.require(
          n.path("resourceRefs").isEmpty() && n.path("dataBindingRefs").isEmpty(),
          "This component cannot reference external resources or data bindings.");
    Set<String> refs = new HashSet<>();
    for (JsonNode ref : n.path("resourceRefs")) {
      identifier(ref.asText());
      Json.require(refs.add(ref.asText()), "Duplicate resource reference.");
    }
    if (type.equals("image")) Json.require(refs.size() <= 1, "Image accepts one resource.");
    if (type.equals("carousel"))
      Json.require(refs.size() <= 12, "Carousel accepts at most 12 images.");
    if (type.equals("model-3d")) {
      JsonNode instances = n.path("props").path("modelInstances");
      Json.require(instances.size() <= 32, "At most 32 embedded model instances.");
      if (instances.isEmpty())
        Json.require(refs.size() <= 1, "Multiple resources require explicit modelInstances.");
      else {
        Set<String> actual = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode i : instances) {
          actual.add(i.path("assetId").asText());
          Json.require(ids.add(i.path("id").asText()), "Duplicate model instance id.");
          transform(i.path("transform"));
        }
        Json.require(
            actual.equals(refs)
                && instances.get(0).path("assetId").equals(n.path("resourceRefs").get(0)),
            "modelInstances must agree with resourceRefs.");
      }
    }
  }

  public void instance(ObjectNode n) {
    identifier(n.path("id").asText());
    identifier(n.path("modelAssetId").asText());
    Json.text(n, "label", 1, 80);
    Json.integer(n, "sortOrder", 0, 100000);
    if (!n.path("assetId").isNull())
      Json.require(
          n.path("assetId").asText().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}"),
          "Invalid business asset identifier.");
    if (!n.has("animation")) n.set("animation", Json.obj("enabled", true, "speed", 1));
    if (!n.has("appearance")) n.set("appearance", Json.obj("color", null, "opacity", 1));
    number(n.path("animation").path("speed"), .1, 3, "animation.speed");
    appearance(n.path("appearance"));
    transform(n.path("transform"));
  }

  public static void transform(JsonNode n) {
    for (String k : List.of("position", "rotation", "scale")) {
      JsonNode vector = n.path(k);
      Json.require(vector.isArray() && vector.size() == 3, "Transforms require three coordinates.");
      for (JsonNode x : vector)
        number(
            x,
            k.equals("scale") ? .001 : k.equals("position") ? -1_000_000 : -3600,
            k.equals("scale") ? 1000 : k.equals("position") ? 1_000_000 : 3600,
            k);
    }
  }
}
