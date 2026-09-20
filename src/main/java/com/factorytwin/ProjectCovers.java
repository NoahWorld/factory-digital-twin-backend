package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
public class ProjectCovers {
  static final int WIDTH = 480;
  static final int HEIGHT = 270;
  static final int RENDERER_VERSION = 2;
  static final int MAX_RENDERED_ITEMS = 160;
  private static final Logger log = LoggerFactory.getLogger(ProjectCovers.class);
  private static final List<String> PALETTE =
      List.of("#55d8ff", "#7c9cff", "#62e6a5", "#ffbd59", "#a78bfa", "#ff7a90");

  final JdbcTemplate db;

  public ProjectCovers(JdbcTemplate db) {
    this.db = db;
  }

  record Cover(long revision, long sourceRevision, int rendererVersion, String svg) {}

  @Transactional
  public long refresh(String tenantId, String projectId) {
    var document =
        db.queryForMap(
            "SELECT p.name,p.project_type,d.revision,d.settings::text AS settings FROM projects p"
                + " JOIN documents d ON d.tenant_id=p.tenant_id AND d.project_id=p.id"
                + " WHERE p.tenant_id=? AND p.id=?",
            tenantId,
            projectId);
    var items =
        db.query(
            "SELECT body::text FROM document_items WHERE tenant_id=? AND project_id=? ORDER BY"
                + " sort_order,id LIMIT ?",
            (row, index) -> Json.parse(row.getString(1)),
            tenantId,
            projectId,
            MAX_RENDERED_ITEMS);
    String projectType = String.valueOf(document.get("project_type"));
    long sourceRevision = ((Number) document.get("revision")).longValue();
    String svg =
        render(
            String.valueOf(document.get("name")),
            projectType,
            Json.parse(String.valueOf(document.get("settings"))),
            items);
    Long revision =
        db.queryForObject(
            "INSERT INTO project_covers(tenant_id,project_id,revision,source_revision,"
                + " renderer_version,svg,updated_at) VALUES(?,?,1,?,?,?,now())"
                + " ON CONFLICT(project_id) DO UPDATE SET revision=project_covers.revision+1,"
                + " source_revision=excluded.source_revision,renderer_version=excluded.renderer_version,"
                + " svg=excluded.svg,updated_at=now() RETURNING revision",
            Long.class,
            tenantId,
            projectId,
            sourceRevision,
            RENDERER_VERSION,
            svg);
    if (revision == null) {
      throw new IllegalStateException("Project cover update returned no revision for " + projectId);
    }
    log.info(
        "Project cover refreshed projectId={} projectType={} sourceRevision={} coverRevision={} rendererVersion={}",
        projectId,
        projectType,
        sourceRevision,
        revision,
        RENDERER_VERSION);
    return revision;
  }

  @Transactional
  public Cover read(String tenantId, String projectId) {
    Cover cover = stored(tenantId, projectId);
    if (cover.rendererVersion() != RENDERER_VERSION) {
      log.info(
          "Rebuilding stale project cover projectId={} storedRendererVersion={} rendererVersion={}",
          projectId,
          cover.rendererVersion(),
          RENDERER_VERSION);
      refresh(tenantId, projectId);
      cover = stored(tenantId, projectId);
    }
    return cover;
  }

  private Cover stored(String tenantId, String projectId) {
    var rows =
        db.query(
            "SELECT revision,source_revision,renderer_version,svg FROM project_covers WHERE"
                + " tenant_id=? AND project_id=?",
            (row, index) ->
                new Cover(
                    row.getLong("revision"),
                    row.getLong("source_revision"),
                    row.getInt("renderer_version"),
                    row.getString("svg")),
            tenantId,
            projectId);
    if (rows.isEmpty()) {
      throw new IllegalStateException("Project cover row is missing for " + projectId);
    }
    return rows.getFirst();
  }

  static String render(
      String projectName, String projectType, JsonNode settings, List<JsonNode> items) {
    if (projectType.equals("2d")) return renderCanvas(projectName, settings, items);
    if (projectType.equals("3d")) return renderScene(projectName, settings, items);
    throw new IllegalArgumentException("Unsupported project type for cover: " + projectType);
  }

  static String renderCanvas(String projectName, JsonNode theme, List<JsonNode> items) {
    String background = color(theme.path("backgroundColor"), "#071525");
    String surface = color(theme.path("surfaceColor"), "#0b2637");
    String border = color(theme.path("borderColor"), "#286783");
    String accent = color(theme.path("accentColor"), "#55d8ff");
    String text = color(theme.path("textColor"), "#e9f8ff");
    StringBuilder body = new StringBuilder();
    body.append(svgStart("2D 项目封面"));
    body.append("<defs><pattern id=\"cover-grid\" width=\"20\" height=\"20\" patternUnits=\"userSpaceOnUse\"><path d=\"M20 0H0V20\" fill=\"none\" stroke=\"")
        .append(border)
        .append("\" stroke-width=\".6\" stroke-opacity=\".28\"/></pattern><linearGradient id=\"cover-shade\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"55%\" stop-color=\"#02080d\" stop-opacity=\"0\"/><stop offset=\"100%\" stop-color=\"#02080d\" stop-opacity=\".86\"/></linearGradient><clipPath id=\"cover-clip\"><rect width=\"480\" height=\"270\"/></clipPath></defs>");
    body.append("<rect width=\"480\" height=\"270\" fill=\"").append(background).append("\"/>");
    if (!theme.path("backgroundPattern").asText("grid").equals("none")) {
      body.append("<rect width=\"480\" height=\"270\" fill=\"url(#cover-grid)\"/>");
    }
    body.append("<g clip-path=\"url(#cover-clip)\">");
    int rendered = 0;
    for (JsonNode node : items) {
      if (rendered++ >= MAX_RENDERED_ITEMS) break;
      double x = number(node.path("x"), 0) * .25;
      double y = number(node.path("y"), 0) * .25;
      double width = Math.max(1, number(node.path("width"), 1) * .25);
      double height = Math.max(1, number(node.path("height"), 1) * .25);
      String type = node.path("type").asText("component");
      JsonNode props = node.path("props");
      String fill = color(props.path("fillColor"), surface);
      String stroke = color(props.path("borderColor"), border);
      String nodeAccent = color(props.path("accentColor"), accent);
      double opacity = clamp(number(props.path("opacity"), .94), 0, 1);
      if (type.equals("circle")) {
        body.append(String.format(Locale.ROOT,
            "<ellipse cx=\"%.2f\" cy=\"%.2f\" rx=\"%.2f\" ry=\"%.2f\" fill=\"%s\" fill-opacity=\"%.2f\" stroke=\"%s\" stroke-width=\".8\"/>",
            x + width / 2, y + height / 2, width / 2, height / 2, fill, opacity, stroke));
      } else {
        body.append(String.format(Locale.ROOT,
            "<rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" rx=\"%.2f\" fill=\"%s\" fill-opacity=\"%.2f\" stroke=\"%s\" stroke-width=\".8\"/>",
            x, y, width, height, Math.min(7, Math.max(1, number(props.path("borderRadius"), 6) * .25)), fill, opacity, stroke));
      }
      renderNodeMark(body, type, props, x, y, width, height, nodeAccent, text);
    }
    if (items.isEmpty()) renderEmptyCanvas(body, accent, surface, border);
    body.append("</g><rect width=\"480\" height=\"270\" fill=\"url(#cover-shade)\"/>");
    appendCaption(body, projectName, "2D CANVAS", text, accent);
    return body.append("</svg>").toString();
  }

  private static void renderNodeMark(
      StringBuilder body,
      String type,
      JsonNode props,
      double x,
      double y,
      double width,
      double height,
      String accent,
      String text) {
    if (Set.of("line-chart", "area-chart", "bar-chart").contains(type) && width > 25 && height > 18) {
      String points =
          String.format(
              Locale.ROOT,
              "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
              x + width * .08,
              y + height * .72,
              x + width * .28,
              y + height * .48,
              x + width * .48,
              y + height * .63,
              x + width * .7,
              y + height * .3,
              x + width * .92,
              y + height * .42);
      body.append("<polyline points=\"").append(points).append("\" fill=\"none\" stroke=\"")
          .append(accent).append("\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>");
    } else if ((type.equals("pie-chart") || type.equals("donut-chart") || type.equals("ring-progress")) && width > 24 && height > 24) {
      body.append(String.format(Locale.ROOT,
          "<circle cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-dasharray=\"70 28\"/>",
          x + width / 2, y + height * .56, Math.min(width, height) * .22, accent, Math.max(2, Math.min(width, height) * .08)));
    } else if (type.equals("model-3d") || type.equals("scene-3d")) {
      double centerX = x + width / 2;
      double centerY = y + height * .55;
      double size = Math.max(5, Math.min(width, height) * .18);
      body.append(String.format(Locale.ROOT,
          "<path d=\"M%.2f %.2fl%.2f %.2fl%.2f-%.2fl-%.2f-%.2fzM%.2f %.2fv%.2fl%.2f %.2fv-%.2f\" fill=\"%s\" fill-opacity=\".18\" stroke=\"%s\" stroke-width=\"1.2\"/>",
          centerX, centerY - size, size, size * .55, -size, size * .55, -size, -size * .55,
          centerX, centerY, size, size * .55, -size, -size, accent, accent));
    }
    String label = firstText(props, "title", "text", "label");
    if (!label.isBlank() && width > 34 && height > 13) {
      body.append(String.format(Locale.ROOT,
          "<text x=\"%.2f\" y=\"%.2f\" fill=\"%s\" font-family=\"system-ui,sans-serif\" font-size=\"%.1f\" font-weight=\"650\">%s</text>",
          x + 6, y + Math.min(14, height * .28), text, Math.max(5, Math.min(8, height * .12)), escape(truncate(label, 28))));
    }
  }

  private static void renderEmptyCanvas(
      StringBuilder body, String accent, String surface, String border) {
    double[][] cards = {{54, 54, 112, 62}, {184, 54, 242, 62}, {54, 132, 172, 60}, {244, 132, 182, 60}};
    for (double[] card : cards) {
      body.append(String.format(Locale.ROOT,
          "<rect x=\"%.0f\" y=\"%.0f\" width=\"%.0f\" height=\"%.0f\" rx=\"6\" fill=\"%s\" stroke=\"%s\"/><path d=\"M%.0f %.0fh%.0f\" stroke=\"%s\" stroke-width=\"2\"/>",
          card[0], card[1], card[2], card[3], surface, border, card[0] + 12, card[1] + 18, card[2] * .34, accent));
    }
  }

  static String renderScene(String projectName, JsonNode settings, List<JsonNode> items) {
    String background = color(settings.path("backgroundColor"), "#071525");
    String accent = "#70dcff";
    String text = "#e9f8ff";
    StringBuilder body = new StringBuilder();
    body.append(svgStart("3D 项目封面"));
    body.append("<defs><radialGradient id=\"scene-light\" cx=\"50%\" cy=\"34%\" r=\"70%\"><stop offset=\"0\" stop-color=\"#285b76\" stop-opacity=\".7\"/><stop offset=\"1\" stop-color=\"")
        .append(background)
        .append("\" stop-opacity=\"0\"/></radialGradient><linearGradient id=\"scene-floor\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#18384b\"/><stop offset=\"1\" stop-color=\"#0a1c2a\"/></linearGradient><linearGradient id=\"scene-shade\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"54%\" stop-color=\"#02080d\" stop-opacity=\"0\"/><stop offset=\"100%\" stop-color=\"#02080d\" stop-opacity=\".94\"/></linearGradient></defs>");
    body.append("<rect width=\"480\" height=\"270\" fill=\"").append(background).append("\"/><rect width=\"480\" height=\"270\" fill=\"url(#scene-light)\"/>");
    body.append("<text x=\"22\" y=\"25\" fill=\"#9adff3\" font-family=\"system-ui,sans-serif\" font-size=\"9\" font-weight=\"800\" letter-spacing=\"1.25\">3D 场景概览</text>");
    body.append("<path d=\"M240 43L450 112L240 184L30 112Z\" fill=\"url(#scene-floor)\" stroke=\"#62c8e7\" stroke-opacity=\".34\"/>");
    if (settings.path("showGrid").asBoolean(true)) {
      for (int index = 1; index < 10; index++) {
        double ratio = index / 10.0;
        body.append(String.format(Locale.ROOT,
            "<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#66c8e5\" stroke-opacity=\".13\" stroke-width=\".7\"/>",
            240 + (450 - 240) * ratio, 43 + (112 - 43) * ratio,
            240 + (240 - 450) * ratio, 184 + (112 - 184) * ratio));
      }
      for (int index = 1; index < 8; index++) {
        double ratio = index / 8.0;
        body.append(String.format(Locale.ROOT,
            "<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#66c8e5\" stroke-opacity=\".13\" stroke-width=\".7\"/>",
            240 + (30 - 240) * ratio, 43 + (112 - 43) * ratio,
            240 + (450 - 240) * ratio, 43 + (112 - 43) * ratio));
      }
    }

    List<SceneMark> marks = new ArrayList<>();
    for (JsonNode item : items) {
      if (!item.path("visible").asBoolean(true)) continue;
      JsonNode position = item.path("transform").path("position");
      JsonNode scale = item.path("transform").path("scale");
      double x = arrayNumber(position, 0, 0);
      double z = arrayNumber(position, 2, 0);
      double u = x - z;
      double v = (x + z) * .5;
      double footprint = Math.max(.2, (arrayNumber(scale, 0, 1) + arrayNumber(scale, 2, 1)) / 2);
      String label = item.path("label").asText("").trim();
      String modelId = item.path("modelAssetId").asText(item.path("id").asText("model"));
      boolean sceneBackground = item.path("renderMode").asText("interactive").equals("background");
      marks.add(new SceneMark(item, u, v, footprint, sceneKind(label, modelId, sceneBackground), label, sceneBackground));
    }
    if (marks.isEmpty()) {
      renderEmptyScene(body, accent);
    } else {
      boolean hasEquipment = marks.stream().anyMatch(mark -> !mark.background());
      List<SceneMark> layoutMarks = hasEquipment
          ? marks.stream().filter(mark -> !mark.background()).toList()
          : marks;
      double minU = layoutMarks.stream().mapToDouble(SceneMark::u).min().orElse(0);
      double maxU = layoutMarks.stream().mapToDouble(SceneMark::u).max().orElse(0);
      double minV = layoutMarks.stream().mapToDouble(SceneMark::v).min().orElse(0);
      double maxV = layoutMarks.stream().mapToDouble(SceneMark::v).max().orElse(0);
      double spanU = Math.max(maxU - minU, 5);
      double spanV = Math.max(maxV - minV, 5);
      marks.sort(
          Comparator.comparing(SceneMark::background)
              .reversed()
              .thenComparingDouble(SceneMark::v)
              .thenComparing(mark -> mark.item().path("id").asText("")));
      int rendered = 0;
      int renderedBackgrounds = 0;
      int labels = 0;
      Map<String, Integer> renderedByKind = new HashMap<>();
      for (SceneMark mark : marks) {
        if (rendered >= 32) break;
        if (mark.background()) {
          if (hasEquipment || renderedBackgrounds >= 2) continue;
          renderedBackgrounds++;
        } else {
          int kindCount = renderedByKind.getOrDefault(mark.kind(), 0);
          if (kindCount >= 6) continue;
          renderedByKind.put(mark.kind(), kindCount + 1);
        }
        rendered++;
        double x = 58 + (mark.u() - minU + (spanU - (maxU - minU)) / 2) / spanU * 364;
        double y = 67 + (mark.v() - minV + (spanV - (maxV - minV)) / 2) / spanV * 89;
        double size = clamp(13 + Math.sqrt(mark.footprint()) * 3.2, 13, mark.background() ? 34 : 26);
        String modelId = mark.item().path("modelAssetId").asText(mark.item().path("id").asText("model"));
        String base = color(mark.item().path("appearance").path("color"), PALETTE.get(Math.floorMod(modelId.hashCode(), PALETTE.size())));
        double opacity = clamp(number(mark.item().path("appearance").path("opacity"), 1), .22, 1);
        renderSceneGlyph(body, mark.kind(), x, y, size, base, mark.background() ? opacity * .4 : opacity);
        if (!mark.background() && !mark.label().isBlank() && labels++ < 7) {
          body.append(String.format(Locale.ROOT,
              "<text x=\"%.2f\" y=\"%.2f\" fill=\"#f0fbff\" stroke=\"%s\" stroke-width=\"2.6\" paint-order=\"stroke\" stroke-linejoin=\"round\" font-family=\"system-ui,sans-serif\" font-size=\"7.2\" font-weight=\"700\" text-anchor=\"middle\">%s</text>",
              x, y + size * .72 + 8, background, escape(truncate(mark.label(), 9))));
        }
      }
    }
    body.append("<rect width=\"480\" height=\"270\" fill=\"url(#scene-shade)\"/>");
    appendSceneSummary(body, marks, text, accent);
    appendCaption(body, projectName, "3D SCENE", text, accent);
    return body.append("</svg>").toString();
  }

  private record SceneMark(
      JsonNode item,
      double u,
      double v,
      double footprint,
      String kind,
      String label,
      boolean background) {}

  private static String sceneKind(
      String label, String modelId, boolean sceneBackground) {
    if (sceneBackground) return "site";
    String value = (label + " " + modelId).toLowerCase(Locale.ROOT);
    if (containsAny(value, "冷却塔", "烟囱", "塔", "tower", "silo", "column")) return "tower";
    if (containsAny(value, "水池", "沉淀池", "沉池", "反应池", "罐", "槽", "tank", "basin", "clarifier", "reactor")) return "tank";
    if (containsAny(value, "泵", "风机", "压缩机", "pump", "blower", "compressor", "fan")) return "pump";
    if (containsAny(value, "输送", "辊道", "连铸", "产线", "conveyor", "caster", "production-line")) return "conveyor";
    if (containsAny(value, "机械臂", "机器人", "robot")) return "robot";
    if (containsAny(value, "配电", "控制柜", "机柜", "电柜", "cabinet", "rack")) return "cabinet";
    if (containsAny(value, "agv", "叉车", "车辆", "小车", "vehicle")) return "vehicle";
    if (containsAny(value, "管廊", "管道", "管线", "pipe", "gantry")) return "pipe";
    return "machine";
  }

  private static boolean containsAny(String value, String... candidates) {
    return Arrays.stream(candidates).anyMatch(value::contains);
  }

  private static String sceneKindLabel(String kind) {
    return switch (kind) {
      case "tower" -> "塔器";
      case "tank" -> "池/罐";
      case "pump" -> "泵/风机";
      case "conveyor" -> "产线";
      case "robot" -> "机器人";
      case "cabinet" -> "机柜";
      case "vehicle" -> "车辆";
      case "pipe" -> "管线";
      default -> "设备";
    };
  }

  private static void renderSceneGlyph(
      StringBuilder body,
      String kind,
      double x,
      double y,
      double size,
      String color,
      double opacity) {
    String transform = String.format(Locale.ROOT, "translate(%.2f %.2f) scale(%.3f)", x, y, size / 24);
    body.append("<g data-scene-kind=\"").append(kind).append("\" transform=\"").append(transform)
        .append("\" fill=\"").append(color).append("\" fill-opacity=\"")
        .append(String.format(Locale.ROOT, "%.2f", opacity))
        .append("\" stroke=\"#dcf8ff\" stroke-opacity=\".78\" stroke-width=\"1.35\" stroke-linejoin=\"round\">");
    switch (kind) {
      case "tank" -> body.append("<path d=\"M-10-5a10 4 0 0 0 20 0v11a10 4 0 0 1-20 0z\"/><ellipse cx=\"0\" cy=\"-5\" rx=\"10\" ry=\"4\" fill-opacity=\".45\"/><path d=\"M-7 1c4 2 10 2 14 0\" fill=\"none\"/>");
      case "pump" -> body.append("<circle cx=\"-2\" cy=\"0\" r=\"8\"/><circle cx=\"-2\" cy=\"0\" r=\"2.5\" fill=\"none\"/><path d=\"M6-3h7v6H6M-10 8h19\" fill=\"none\"/>");
      case "tower" -> body.append("<path d=\"M-7 9h14L4-10h-8z\"/><ellipse cx=\"0\" cy=\"-10\" rx=\"4\" ry=\"2\" fill-opacity=\".42\"/><path d=\"M-5 1h10M-6 6h12\" fill=\"none\"/>");
      case "conveyor" -> body.append("<path d=\"M-12-5h24v9h-24z\"/><path d=\"M-10 4l-3 7M10 4l3 7\" fill=\"none\"/><circle cx=\"-7\" cy=\"0\" r=\"2\" fill=\"none\"/><circle cx=\"0\" cy=\"0\" r=\"2\" fill=\"none\"/><circle cx=\"7\" cy=\"0\" r=\"2\" fill=\"none\"/>");
      case "robot" -> body.append("<path d=\"M-9 9h18v3H-9z\"/><circle cx=\"-3\" cy=\"4\" r=\"3\"/><path d=\"M-3 1l4-8 6 3-4 7\" fill=\"none\" stroke-width=\"3\"/><circle cx=\"1\" cy=\"-7\" r=\"2.5\"/><path d=\"M7-4l5-4M7-4l5 1\" fill=\"none\"/>");
      case "cabinet" -> body.append("<rect x=\"-8\" y=\"-11\" width=\"16\" height=\"22\" rx=\"1\"/><rect x=\"-5\" y=\"-7\" width=\"10\" height=\"6\" fill=\"none\"/><path d=\"M-5 4h10M-5 7h10\" fill=\"none\"/><circle cx=\"4\" cy=\"2\" r=\"1\"/>");
      case "vehicle" -> body.append("<path d=\"M-12 5v-8h5l3-5h8l5 5h3v8z\"/><circle cx=\"-7\" cy=\"7\" r=\"3\"/><circle cx=\"7\" cy=\"7\" r=\"3\"/><path d=\"M-2-7v5h7\" fill=\"none\"/>");
      case "pipe" -> body.append("<path d=\"M-12 7v-11h15v-7M-7 7v-6H8v-12\" fill=\"none\" stroke-width=\"4\"/><path d=\"M-12 10v-3M-7 10V7M3-1h5\" fill=\"none\"/>");
      case "site" -> body.append("<path d=\"M-14 8V-7l14-7 14 7V8L0 14zM0-14V1m-14-8L0 1l14-8M0 1v13\" fill-opacity=\".2\"/>");
      default -> body.append("<path d=\"M0-12l11 6v12L0 12-11 6V-6z\"/><path d=\"M0 0v12M-11-6L0 0l11-6\" fill=\"none\"/>");
    }
    body.append("</g>");
  }

  private static void appendSceneSummary(
      StringBuilder body, List<SceneMark> marks, String text, String accent) {
    List<SceneMark> equipment = marks.stream().filter(mark -> !mark.background()).toList();
    Map<String, Long> counts = new LinkedHashMap<>();
    equipment.forEach(mark -> counts.merge(mark.kind(), 1L, Long::sum));
    String countSummary = counts.entrySet().stream().limit(4)
        .map(entry -> sceneKindLabel(entry.getKey()) + " " + entry.getValue())
        .reduce((left, right) -> left + " · " + right).orElse("尚无设备");
    body.append("<text x=\"458\" y=\"25\" fill=\"").append(text)
        .append("\" fill-opacity=\".78\" font-family=\"system-ui,sans-serif\" font-size=\"8\" font-weight=\"650\" text-anchor=\"end\">")
        .append(escape(countSummary)).append("</text>");
    Map<String, String> representativeLabels = new LinkedHashMap<>();
    equipment.stream().filter(mark -> !mark.label().isBlank())
        .forEach(mark -> representativeLabels.putIfAbsent(mark.kind(), truncate(mark.label(), 11)));
    String labels = representativeLabels.values().stream().limit(4)
        .reduce((left, right) -> left + " · " + right).orElse("");
    String summary = labels.isBlank()
        ? "可见设备 " + equipment.size() + " 个"
        : "关键设备  " + labels;
    body.append("<circle cx=\"24\" cy=\"205\" r=\"3\" fill=\"").append(accent)
        .append("\"/><text x=\"33\" y=\"208\" fill=\"").append(text)
        .append("\" fill-opacity=\".86\" font-family=\"system-ui,sans-serif\" font-size=\"9\" font-weight=\"600\">")
        .append(escape(truncate(summary, 48))).append("</text>");
  }

  private static void renderEmptyScene(StringBuilder body, String accent) {
    body.append("<path d=\"M240 65l45 25-45 25-45-25z\" fill=\"").append(accent)
        .append("\" fill-opacity=\".14\" stroke=\"").append(accent)
        .append("\" stroke-width=\"1.8\"/><path d=\"M195 90v38l45 25v-38M285 90v38l-45 25\" fill=\"")
        .append(accent).append("\" fill-opacity=\".05\" stroke=\"").append(accent)
        .append("\" stroke-width=\"1.8\"/><text x=\"240\" y=\"173\" fill=\"#e9f8ff\" font-family=\"system-ui,sans-serif\" font-size=\"11\" font-weight=\"700\" text-anchor=\"middle\">场景尚未配置设备</text>");
  }

  private static void appendCaption(
      StringBuilder body, String projectName, String kind, String text, String accent) {
    body.append("<text x=\"22\" y=\"236\" fill=\"").append(text)
        .append("\" font-family=\"system-ui,sans-serif\" font-size=\"15\" font-weight=\"700\">")
        .append(escape(truncate(projectName, 34))).append("</text><text x=\"458\" y=\"236\" fill=\"")
        .append(accent)
        .append("\" font-family=\"system-ui,sans-serif\" font-size=\"9\" font-weight=\"800\" letter-spacing=\"1.4\" text-anchor=\"end\">")
        .append(kind).append("</text>");
  }

  private static String svgStart(String label) {
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"480\" height=\"270\" viewBox=\"0 0 480 270\" role=\"img\" aria-label=\""
        + escape(label)
        + "\">";
  }

  private static String firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      JsonNode value = node.path(field);
      if (value.isTextual() && !value.asText().isBlank()) return value.asText();
    }
    return "";
  }

  private static String color(JsonNode value, String fallback) {
    String candidate = value.isTextual() ? value.asText() : "";
    return candidate.matches("(?i)^#[0-9a-f]{6}$") ? candidate : fallback;
  }

  private static double number(JsonNode value, double fallback) {
    if (!value.isNumber()) return fallback;
    double candidate = value.asDouble();
    return Double.isFinite(candidate) ? candidate : fallback;
  }

  private static double arrayNumber(JsonNode value, int index, double fallback) {
    return value.isArray() && value.size() > index ? number(value.path(index), fallback) : fallback;
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private static String truncate(String value, int maximum) {
    if (value.length() <= maximum) return value;
    return value.substring(0, Math.max(0, maximum - 1)) + "…";
  }

  static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}

@RestController
@RequestMapping("/api/v1/projects/{id}")
class ProjectCoverController {
  final Projects projects;
  final ProjectCovers covers;

  ProjectCoverController(Projects projects, ProjectCovers covers) {
    this.projects = projects;
    this.covers = covers;
  }

  @GetMapping(value = "/cover.svg", produces = "image/svg+xml")
  ResponseEntity<String> cover(@PathVariable String id, HttpServletRequest request) {
    var user = projects.auth.require(request);
    projects.access(user, id, false);
    var cover = covers.read(user.tenant(), id);
    String etag =
        "\"project-"
            + id
            + "-cover-"
            + cover.revision()
            + "-renderer-"
            + cover.rendererVersion()
            + "\"";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("image/svg+xml; charset=utf-8"));
    headers.setCacheControl("private, max-age=0, must-revalidate");
    headers.setETag(etag);
    headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
    headers.set("X-Content-Type-Options", "nosniff");
    if (etag.equals(request.getHeader("If-None-Match"))) {
      return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
    }
    return new ResponseEntity<>(cover.svg(), headers, HttpStatus.OK);
  }
}
