package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

public final class Json {
  public static final ObjectMapper M = new ObjectMapper();

  public static ObjectNode obj(Object... pairs) {
    ObjectNode n = M.createObjectNode();
    for (int i = 0; i < pairs.length; i += 2) n.set((String) pairs[i], M.valueToTree(pairs[i + 1]));
    return n;
  }

  public static JsonNode parse(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored JSON", e);
    }
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String timestamp(Object value) {
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
    if (value instanceof java.time.OffsetDateTime timestamp)
      return timestamp.toInstant().toString();
    if (value instanceof java.time.Instant timestamp) return timestamp.toString();
    throw new IllegalArgumentException(
        "Expected a database timestamp, received "
            + (value == null ? "null" : value.getClass().getName()));
  }

  public static String text(JsonNode n, String key, int min, int max) {
    JsonNode v = n.get(key);
    if (v == null
        || !v.isTextual()
        || v.textValue().trim().length() < min
        || v.textValue().trim().length() > max
        || v.textValue().chars().anyMatch(c -> c < 32))
      throw new ApiException(
          400,
          "invalid_input",
          key + " must contain " + min + "–" + max + " printable characters.");
    return v.textValue().trim();
  }

  public static void require(boolean ok, String message) {
    if (!ok) throw new ApiException(400, "invalid_input", message);
  }

  public static void fields(JsonNode n, String... fields) {
    require(n != null && n.isObject(), "Expected a JSON object.");
    Set<String> allowed = Set.of(fields);
    n.fieldNames()
        .forEachRemaining(
            k -> {
              if (!allowed.contains(k))
                throw new ApiException(400, "unknown_field", "Unsupported field: " + k);
            });
  }

  public static long integer(JsonNode n, String key, long min, long max) {
    JsonNode v = n.path(key);
    require(
        v.isIntegralNumber()
            && v.canConvertToLong()
            && v.longValue() >= min
            && v.longValue() <= max,
        key + " must be an integer between " + min + " and " + max);
    return v.longValue();
  }
}
