package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A single workpiece travels to inspection and back. All observations use the server clock. */
@Service
public class TestBusiness {
  static final Geometry GEOMETRY = Geometry.load();
  private final Clock clock;
  public TestBusiness() { this(Clock.systemUTC()); }
  TestBusiness(Clock clock) { this.clock = clock; }
  public ObjectNode state() { return stateAt(clock.millis()); }

  static ObjectNode stateAt(long now) {
    long sample = Math.floorDiv(now, 200) * 200;
    Frame frame = frameAt(Math.floorMod(sample, GEOMETRY.cycleMs()));
    var arm = frame.arm(); var cargo = frame.cargo(); var tcp = frame.tcp();
    return Json.obj("timestamp", Instant.ofEpochMilli(sample).toString(), "sequence", Math.floorDiv(sample, 200),
        "scenario", "handling-cell", "geometryVersion", GEOMETRY.version(),
        "agv", Json.obj("positionM", frame.positionM(), "velocityMps", frame.velocityMps(),
            "wheelAngleDeg", frame.wheelAngleDeg()),
        "robot", Json.obj("angleDeg", arm.yawDeg() * 4 / 3 - 60, "baseYawDeg", arm.yawDeg(),
            "shoulderDeg", arm.shoulderDeg(), "elbowDeg", arm.elbowDeg(), "wristDeg", arm.wristDeg(),
            "tcp", Json.obj("xM", tcp.x(), "yM", tcp.y(), "zM", tcp.z())),
        "gripper", Json.obj("openingM", frame.openingM()),
        "cargo", Json.obj("xM", cargo.x(), "yM", cargo.y(), "zM", cargo.z(),
            "yawDeg", frame.cargoYawDeg(), "attachment", frame.attachment()),
        "cycle", Json.obj("phaseCode", frame.phase().code(), "phase", frame.phase().label(),
            "label", frame.phase().label(), "durationMs", GEOMETRY.cycleMs(), "elapsedMs", frame.elapsedMs()));
  }

  /** Continuous trajectory before the 200 ms transport sampling, also used for geometry verification. */
  static Frame frameAt(double elapsedMs) {
    var g = GEOMETRY;
    if (!Double.isFinite(elapsedMs) || elapsedMs < 0 || elapsedMs >= g.cycleMs())
      throw new IllegalArgumentException("handling-cell elapsedMs must be inside its cycle: " + elapsedMs);
    Phase phase = g.phases().getLast();
    for (var candidate : g.phases()) if (elapsedMs < candidate.endMs()) { phase = candidate; break; }
    double progress = (elapsedMs - phase.startMs()) / phase.durationMs();
    double eased = smooth(progress), travel = g.dockZM() - g.startZM();
    double position = switch (phase.code()) {
      case 0, 11 -> travel * eased;
      case 9, 20 -> travel * (1 - eased);
      case 10, 21 -> 0;
      default -> travel;
    };
    double velocity = switch (phase.code()) {
      case 0, 11 -> travel * smoothDerivative(progress) * 1000 / phase.durationMs();
      case 9, 20 -> -travel * smoothDerivative(progress) * 1000 / phase.durationMs();
      default -> 0;
    };
    Vec pickup = g.pickup(), inspection = g.inspection();
    Vec raisedPickup = pickup.atY(g.liftY()), raisedInspection = inspection.atY(g.liftY());
    Vec tcp = switch (phase.code()) {
      case 2 -> approach(g.home(), raisedPickup, pickup, progress);
      case 3, 18 -> pickup;
      case 4 -> interpolate(pickup, raisedPickup, eased);
      case 5 -> polar(raisedPickup, raisedInspection, eased);
      case 6 -> interpolate(raisedInspection, inspection, eased);
      case 7, 14 -> inspection;
      case 8 -> retract(inspection, raisedInspection, g.home(), progress);
      case 13 -> approach(g.home(), raisedInspection, inspection, progress);
      case 15 -> interpolate(inspection, raisedInspection, eased);
      case 16 -> polar(raisedInspection, raisedPickup, eased);
      case 17 -> interpolate(raisedPickup, pickup, eased);
      case 19 -> retract(pickup, raisedPickup, g.home(), progress);
      default -> g.home();
    };
    Arm arm = inverseKinematics(tcp);
    double opening = switch (phase.code()) {
      case 3, 14 -> lerp(g.openGap(), g.closedGap(), eased);
      case 4, 5, 6, 15, 16, 17 -> g.closedGap();
      case 7, 18 -> lerp(g.closedGap(), g.openGap(), eased);
      default -> g.openGap();
    };
    String attachment = phase.code() <= 3 || phase.code() >= 18 ? "agv"
        : phase.code() <= 6 || phase.code() >= 15 ? "gripper" : "inspection";
    Vec cargo = switch (attachment) {
      case "agv" -> new Vec(pickup.x(), pickup.y(), g.startZM() + position);
      case "inspection" -> inspection;
      case "gripper" -> tcp;
      default -> throw new IllegalStateException("Unsupported handling-cell cargo attachment " + attachment);
    };
    double cargoYaw = switch (attachment) {
      case "agv" -> yaw(pickup);
      case "inspection" -> yaw(inspection);
      default -> arm.yawDeg();
    };
    return new Frame(elapsedMs, phase, position, velocity,
        Math.toDegrees(position / g.wheelRadius()) * g.wheelSign(), tcp, arm, opening,
        cargo, cargoYaw, attachment);
  }

  private static Vec approach(Vec home, Vec raised, Vec contact, double progress) {
    return progress < .5 ? polar(home, raised, smooth(progress * 2))
        : interpolate(raised, contact, smooth(progress * 2 - 1));
  }
  private static Vec retract(Vec contact, Vec raised, Vec home, double progress) {
    return progress < .5 ? interpolate(contact, raised, smooth(progress * 2))
        : polar(raised, home, smooth(progress * 2 - 1));
  }
  private static Vec interpolate(Vec a, Vec b, double progress) {
    return new Vec(lerp(a.x(), b.x(), progress), lerp(a.y(), b.y(), progress), lerp(a.z(), b.z(), progress));
  }
  /** Transfer follows the turret arc above both surfaces, rather than cutting through the robot base. */
  private static Vec polar(Vec a, Vec b, double progress) {
    var shoulder = GEOMETRY.shoulder();
    double radius = lerp(Math.hypot(a.x() - shoulder.x(), a.z() - shoulder.z()),
        Math.hypot(b.x() - shoulder.x(), b.z() - shoulder.z()), progress);
    double angle = Math.toRadians(lerp(yaw(a), yaw(b), progress));
    return new Vec(shoulder.x() - radius * Math.cos(angle), lerp(a.y(), b.y(), progress),
        shoulder.z() + radius * Math.sin(angle));
  }
  private static double yaw(Vec tcp) {
    var shoulder = GEOMETRY.shoulder();
    return Math.toDegrees(Math.atan2(tcp.z() - shoulder.z(), shoulder.x() - tcp.x()));
  }
  static Arm inverseKinematics(Vec tcp) {
    var g = GEOMETRY; var shoulder = g.shoulder();
    double radius = Math.hypot(tcp.x() - shoulder.x(), tcp.z() - shoulder.z());
    double height = tcp.y() + g.wristToTcp() - shoulder.y();
    double distance = Math.hypot(radius, height);
    if (distance <= Math.abs(g.foreLength() - g.upperLength()) || distance >= g.upperLength() + g.foreLength())
      throw new IllegalStateException("Unreachable handling-cell TCP " + tcp + "; wrist reach=" + distance);
    double cosine = (radius * radius + height * height - g.upperLength() * g.upperLength() - g.foreLength() * g.foreLength())
        / (2 * g.upperLength() * g.foreLength());
    double elbow = Math.acos(cosine);
    double upper = Math.atan2(radius, height) - Math.atan2(g.foreLength() * Math.sin(elbow),
        g.upperLength() + g.foreLength() * Math.cos(elbow));
    return new Arm(yaw(tcp), Math.toDegrees(upper), Math.toDegrees(elbow), -Math.toDegrees(upper + elbow));
  }
  private static double smooth(double value) { return value * value * (3 - 2 * value); }
  private static double smoothDerivative(double value) { return 6 * value * (1 - value); }
  private static double lerp(double a, double b, double progress) { return a + (b - a) * progress; }

  record Vec(double x, double y, double z) { Vec atY(double value) { return new Vec(x, value, z); } }
  record Arm(double yawDeg, double shoulderDeg, double elbowDeg, double wristDeg) {}
  record Phase(int code, String label, long startMs, long durationMs) { long endMs() { return startMs + durationMs; } }
  record Frame(double elapsedMs, Phase phase, double positionM, double velocityMps, double wheelAngleDeg,
      Vec tcp, Arm arm, double openingM, Vec cargo, double cargoYawDeg, String attachment) {}
  record Geometry(int version, Vec shoulder, double upperLength, double foreLength, double wristToTcp,
      double openGap, double closedGap, double wheelRadius, double wheelSign, double startZM, double dockZM,
      Vec pickup, Vec inspection, Vec home, double liftY, List<Phase> phases, long cycleMs) {
    static Geometry load() {
      String path = "/contracts/handling-cell-geometry.json";
      try (var input = TestBusiness.class.getResourceAsStream(path)) {
        if (input == null) throw new IllegalStateException("Missing generated handling-cell geometry contract " + path);
        JsonNode json = Json.M.readTree(input), robot = json.path("robot"), cargo = json.path("cargo"), agv = json.path("agv");
        require(json.path("version").isIntegralNumber() && json.path("version").asInt() == 1, "geometry version");
        require("Y".equals(json.path("upAxis").asText()) && "meters".equals(json.path("units").asText())
            && "degrees".equals(json.path("angles").asText()), "geometry coordinate system");
        require(vector(robot, "jointAxis").equals(new Vec(0, 0, 1)) && vector(robot, "turretAxis").equals(new Vec(0, 1, 0))
            && vector(robot, "linkRestAxis").equals(new Vec(0, 1, 0)), "geometry joint axes");
        require("positive-elbow-above".equals(robot.path("elbowBranch").asText()), "geometry elbow branch");
        var phases = new ArrayList<Phase>(); long total = 0;
        require(json.path("phases").isArray() && json.path("phases").size() == 22, "22 handling phases");
        for (JsonNode phase : json.path("phases")) {
          require(phase.path("code").isIntegralNumber() && phase.path("code").asInt() == phases.size(), "ordered phase code");
          require(phase.path("label").isTextual() && !phase.path("label").asText().isBlank(), "phase label");
          require(phase.path("durationMs").isIntegralNumber() && phase.path("durationMs").asLong() >= 200
              && phase.path("durationMs").asLong() <= 60000, "phase duration");
          long duration = phase.path("durationMs").asLong();
          phases.add(new Phase(phases.size(), phase.path("label").asText(), total, duration)); total += duration;
        }
        double lift = number(json.path("clearance"), "liftTcpYM");
        require(lift == number(json.path("motion"), "clearanceTcpY"), "matching clearance height");
        var geometry = new Geometry(1, vector(robot, "shoulderPosition"), positive(robot, "upperLengthM"),
            positive(robot, "foreLengthM"), positive(robot, "wristToTcpM"), positive(robot, "openGapM"),
            positive(robot, "closedGapM"), positive(agv, "wheelRadiusM"), number(agv, "wheelAngleSign"),
            number(agv, "startZM"), number(agv, "dockZM"), vector(cargo, "pickupPosition"),
            vector(cargo, "inspectionPosition"), vector(json.path("motion"), "homeTcp"), lift, List.copyOf(phases), total);
        require(geometry.dockZM() > geometry.startZM() && geometry.pickup().z() == geometry.dockZM(), "AGV docking position");
        require(geometry.openGap() > geometry.closedGap() && Math.abs(geometry.wheelSign()) == 1, "gripper/wheel dimensions");
        require(lift > geometry.pickup().y() && lift > geometry.inspection().y(), "lift clears both contact surfaces");
        return geometry;
      } catch (IOException error) { throw new IllegalStateException("Cannot read generated handling-cell geometry contract " + path, error); }
    }
    private static Vec vector(JsonNode parent, String key) {
      JsonNode value = parent.path(key); require(value.isArray() && value.size() == 3, key + " must be xyz");
      for (var axis : value) require(axis.isNumber() && Double.isFinite(axis.asDouble()), key + " must be finite");
      return new Vec(value.get(0).asDouble(), value.get(1).asDouble(), value.get(2).asDouble());
    }
    private static double positive(JsonNode parent, String key) { double value = number(parent, key); require(value > 0, key + " must be positive"); return value; }
    private static double number(JsonNode parent, String key) { JsonNode value = parent.path(key); require(value.isNumber() && Double.isFinite(value.asDouble()), key + " must be finite"); return value.asDouble(); }
    private static void require(boolean valid, String context) { if (!valid) throw new IllegalStateException("Invalid handling-cell geometry contract: " + context); }
  }
}

@RestController
class TestBusinessController {
  final TestBusiness business;
  TestBusinessController(TestBusiness business) { this.business = business; }
  @GetMapping("/api/v1/test-business/handling-cell/state") Object state() { return business.state(); }
}
