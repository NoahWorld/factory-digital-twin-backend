package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TestBusinessTest {
  private static final TestBusiness.Geometry G = TestBusiness.GEOMETRY;

  @Test void clockProducesOneCompleteInspectionAndRecoveryCycleWithoutObservers() {
    long epoch = 1_700_000_000_000L / G.cycleMs() * G.cycleMs();
    for (long delta = 0; delta < G.cycleMs(); delta += 200) {
      var sample = TestBusiness.stateAt(epoch + delta);
      assertEquals("handling-cell", sample.path("scenario").asText());
      assertTrue(sample.path("agv").path("positionM").asDouble() >= 0 && sample.path("agv").path("positionM").asDouble() <= G.dockZM() - G.startZM());
      assertTrue(sample.path("robot").path("angleDeg").asDouble() >= -60 && sample.path("robot").path("angleDeg").asDouble() <= 60);
      assertEquals((epoch + delta) / 200, sample.path("sequence").asLong());
      var repeated = TestBusiness.stateAt(epoch + delta + G.cycleMs());
      for (String key : List.of("agv", "robot", "gripper", "cargo", "cycle")) assertEquals(sample.path(key), repeated.path(key), key);
    }
    assertEquals(22, G.phases().size()); assertEquals(64000, G.cycleMs());
    assertEquals("送件到站", TestBusiness.stateAt(epoch).path("cycle").path("label").asText());
    assertEquals("工件检验", TestBusiness.stateAt(epoch + 30000).path("cycle").path("label").asText());
    var clock = Clock.fixed(Instant.ofEpochMilli(epoch + 1234), ZoneOffset.UTC);
    assertEquals(TestBusiness.stateAt(epoch + 1200), new TestBusiness(clock).state());
  }

  @Test void actualJointFeedbackMatchesTcpAndExportsFullContinuousCycleForMeshVerification() throws Exception {
    ArrayNode states = Json.M.createArrayNode();
    for (long time = 0; time < G.cycleMs(); time += 20) {
      var frame = TestBusiness.frameAt(time); var arm = frame.arm();
      assertVec(frame.tcp(), forwardKinematics(arm), 1e-9, "TCP at " + time);
      assertEquals(0, arm.shoulderDeg() + arm.elbowDeg() + arm.wristDeg(), 1e-9, "tool remains vertical at " + time);
      assertTrue(arm.elbowDeg() > 0 && arm.elbowDeg() < 180, "continuous elbow branch at " + time);
      assertTrue(frame.openingM() >= G.closedGap() && frame.openingM() <= G.openGap());
      if (frame.attachment().equals("gripper")) {
        assertVec(frame.cargo(), forwardKinematics(arm), 1e-9, "held cargo at " + time);
        assertEquals(G.closedGap(), frame.openingM(), 1e-9);
        assertEquals(arm.yawDeg(), frame.cargoYawDeg(), 1e-9);
      }
      states.add(Json.M.valueToTree(frame));
    }
    Files.createDirectories(Path.of("target"));
    Json.M.writeValue(Path.of("target/handling-cell-states.json").toFile(), states);
  }

  @Test void oneWorkpieceChangesOwnershipOnlyAtStationaryContactAndNeverTeleports() {
    var expected = Map.of(10000.0, "gripper", 17000.0, "inspection", 42000.0, "gripper", 49000.0, "agv");
    Set<Double> transitions = new HashSet<>();
    var previous = TestBusiness.frameAt(0);
    for (double time = 20; time < G.cycleMs(); time += 20) {
      var frame = TestBusiness.frameAt(time);
      assertTrue(distance(previous.cargo(), frame.cargo()) < .04, "continuous cargo at " + time);
      if (!previous.attachment().equals(frame.attachment())) {
        transitions.add(time); assertEquals(expected.get(time), frame.attachment(), "ownership at " + time);
        var before = TestBusiness.frameAt(time - .001);
        assertVec(before.cargo(), frame.cargo(), 1e-9, "contact transfer at " + time);
        assertEquals(0, frame.velocityMps(), "AGV stopped at transfer");
        assertVec(frame.cargo(), frame.tcp(), 1e-9, "tool at contact before opening/after closing");
        assertEquals(G.closedGap(), frame.openingM(), 1e-9);
      }
      switch (frame.attachment()) {
        case "agv" -> assertVec(new TestBusiness.Vec(G.pickup().x(), G.pickup().y(), G.startZM() + frame.positionM()), frame.cargo(), 1e-9, "cargo on deck");
        case "inspection" -> assertVec(G.inspection(), frame.cargo(), 1e-9, "cargo rests on inspection table");
        case "gripper" -> assertVec(frame.tcp(), frame.cargo(), 1e-9, "cargo held");
        default -> fail("Unknown attachment: " + frame.attachment());
      }
      previous = frame;
    }
    assertEquals(expected.keySet(), transitions);
    assertVec(TestBusiness.frameAt(0).cargo(), TestBusiness.frameAt(G.cycleMs() - .001).cargo(), 1e-9, "cycle closes with the same loaded cargo");
  }

  @Test void signedWheelRotationMatchesTravelAndAllArmOperationsRequireDockedStoppedVehicle() {
    var previous = TestBusiness.frameAt(0);
    for (double time = 20; time < G.cycleMs(); time += 20) {
      var frame = TestBusiness.frameAt(time);
      assertEquals(frame.positionM() - previous.positionM(), G.wheelRadius() * Math.toRadians(frame.wheelAngleDeg() - previous.wheelAngleDeg()) / G.wheelSign(), 1e-10, "no slip at " + time);
      if (frame.velocityMps() != 0) {
        assertEquals(Math.signum(frame.velocityMps()), Math.signum(frame.wheelAngleDeg() - previous.wheelAngleDeg()));
        assertVec(G.home(), frame.tcp(), 1e-9, "arm retracted while moving");
        assertEquals(G.openGap(), frame.openingM(), 1e-9);
      }
      int phase = frame.phase().code();
      if ((phase >= 2 && phase <= 8) || (phase >= 13 && phase <= 19)) {
        assertEquals(0, frame.velocityMps());
        assertEquals(G.dockZM() - G.startZM(), frame.positionM());
      }
      if (phase == 5 || phase == 16) {
        assertEquals(G.liftY(), frame.cargo().y(), 1e-9, "cargo lifted before turret transfer");
        assertEquals(Math.hypot(G.pickup().x() - G.shoulder().x(), G.pickup().z() - G.shoulder().z()),
            Math.hypot(frame.cargo().x() - G.shoulder().x(), frame.cargo().z() - G.shoulder().z()), 1e-9);
      }
      previous = frame;
    }
    assertEquals(0, previous.wheelAngleDeg());
  }

  @Test void everyStageAndApproachWaypointHasContinuousZeroBoundaryVelocity() {
    Set<Double> boundaries = new HashSet<>();
    for (var phase : G.phases()) boundaries.add((double) phase.startMs());
    for (int code : List.of(2, 8, 13, 19)) {
      var phase = G.phases().get(code); boundaries.add(phase.startMs() + phase.durationMs() / 2.0);
    }
    for (double boundary : boundaries) {
      var center = TestBusiness.frameAt(boundary);
      var before = TestBusiness.frameAt(boundary == 0 ? G.cycleMs() - 1 : boundary - 1);
      var after = TestBusiness.frameAt(boundary + 1);
      for (int side = 0; side < 2; side++) {
        var neighbor = side == 0 ? before : after;
        double halfTime = boundary + (side == 0 ? -.5 : .5);
        var half = TestBusiness.frameAt(halfTime < 0 ? halfTime + G.cycleMs() : halfTime);
        // For a zero first derivative, halving dt quarters displacement. Verify convergence,
        // rather than imposing an arbitrary acceleration threshold on a longer turret arc.
        assertTrue(distance(center.tcp(), half.tcp()) <= distance(center.tcp(), neighbor.tcp()) * .26 + 1e-12, "TCP zero derivative at " + boundary);
        assertTrue(distance(center.cargo(), half.cargo()) <= distance(center.cargo(), neighbor.cargo()) * .26 + 1e-12, "cargo zero derivative at " + boundary);
        assertVec(center.tcp(), neighbor.tcp(), .00002, "continuous TCP at " + boundary);
        assertVec(center.cargo(), neighbor.cargo(), .00002, "continuous cargo at " + boundary);
        assertEquals(center.positionM(), neighbor.positionM(), .000001, "AGV smooth stop at " + boundary);
        assertEquals(center.arm().yawDeg(), neighbor.arm().yawDeg(), .0005, "turret smooth stop at " + boundary);
        assertEquals(center.arm().shoulderDeg(), neighbor.arm().shoulderDeg(), .0005, "shoulder smooth stop at " + boundary);
        assertEquals(center.arm().elbowDeg(), neighbor.arm().elbowDeg(), .0005, "elbow smooth stop at " + boundary);
      }
      assertEquals(0, center.velocityMps(), 0, "zero vehicle velocity at " + boundary);
    }
  }

  @Test void unreachableOrInvalidTrajectoryInputsFailWithContext() {
    var error = assertThrows(IllegalStateException.class, () -> TestBusiness.inverseKinematics(new TestBusiness.Vec(100, 100, 100)));
    assertTrue(error.getMessage().contains("Unreachable handling-cell TCP"));
    assertThrows(IllegalArgumentException.class, () -> TestBusiness.frameAt(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> TestBusiness.frameAt(G.cycleMs()));
  }

  private static TestBusiness.Vec forwardKinematics(TestBusiness.Arm arm) {
    double shoulder = Math.toRadians(arm.shoulderDeg()), fore = Math.toRadians(arm.shoulderDeg() + arm.elbowDeg());
    double radius = G.upperLength() * Math.sin(shoulder) + G.foreLength() * Math.sin(fore), yaw = Math.toRadians(arm.yawDeg());
    return new TestBusiness.Vec(G.shoulder().x() - radius * Math.cos(yaw),
        G.shoulder().y() + G.upperLength() * Math.cos(shoulder) + G.foreLength() * Math.cos(fore) - G.wristToTcp(),
        G.shoulder().z() + radius * Math.sin(yaw));
  }
  private static double distance(TestBusiness.Vec a, TestBusiness.Vec b) { return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2)); }
  private static void assertVec(TestBusiness.Vec expected, TestBusiness.Vec actual, double tolerance, String context) {
    assertEquals(expected.x(), actual.x(), tolerance, context + " X"); assertEquals(expected.y(), actual.y(), tolerance, context + " Y"); assertEquals(expected.z(), actual.z(), tolerance, context + " Z");
  }
}
