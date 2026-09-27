package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class FluidContractTest {
  final Contracts contracts = new Contracts();

  JsonNode fixtures() throws Exception {
    try (var stream = getClass().getResourceAsStream("/fluid-validation-cases.json")) {
      assertNotNull(stream);
      return Json.M.readTree(stream);
    }
  }

  @Test
  void sharedTypescriptAndJavaFixturesEnforceIdenticalFluidContracts() throws Exception {
    var fixture = fixtures();
    for (var entry : fixture.path("cases")) {
      var fluid = (ObjectNode) fixture.path("base").deepCopy();
      entry.path("changes").fields().forEachRemaining(field -> fluid.set(field.getKey(), field.getValue()));
      if (entry.has("remove")) fluid.remove(entry.path("remove").asText());
      JsonNode input = entry.has("input") ? entry.get("input") : Json.M.valueToTree(List.of(fluid));
      if (entry.path("valid").asBoolean()) assertDoesNotThrow(() -> contracts.fluids(input), entry.path("name").asText());
      else assertEquals(400, assertThrows(ApiException.class, () -> contracts.fluids(input), entry.path("name").asText()).status);
    }
    var base = fixture.path("base");
    assertThrows(ApiException.class, () -> contracts.fluids(Json.M.valueToTree(List.of(base, base))));
    var entries = Json.M.createArrayNode();
    for (int index = 0; index < 32; index++) entries.add(((ObjectNode) base.deepCopy()).put("id", "fluid-" + index));
    contracts.fluids(entries);
    entries.add(((ObjectNode) base.deepCopy()).put("id", "overflow"));
    assertThrows(ApiException.class, () -> contracts.fluids(entries));
    var fluid = (ObjectNode) base.deepCopy();
    var points = fluid.putArray("points");
    for (int index = 0; index < 64; index++) points.addArray().add(index).add(0).add(0);
    contracts.fluids(Json.M.valueToTree(List.of(fluid)));
    points.addArray().add(64).add(0).add(0);
    assertThrows(ApiException.class, () -> contracts.fluids(Json.M.valueToTree(List.of(fluid))));
  }

  @Test
  void sceneStorageRoundTripPreservesFluidsAcrossSettingsOnlyChangesAndDefaultsOnlyMissing() throws Exception {
    var legacy = Documents.settings(contracts);
    assertEquals(Json.parse("[]"), contracts.sceneFluidsFromStorage(legacy));
    var fluid = fixtures().path("base");
    var stored = contracts.sceneStorageAfterPatch(legacy, Json.obj("fluids", List.of(fluid)));
    var expectedFluids = contracts.sceneFluidsFromStorage(stored);
    assertEquals(legacy, contracts.sceneSettingsFromStorage(stored));
    var settings = legacy.deepCopy().put("backgroundColor", "#123456");
    var updated = contracts.sceneStorageAfterPatch(stored, Json.obj("settings", settings));
    assertEquals(expectedFluids, contracts.sceneFluidsFromStorage(updated));
    assertEquals("#123456", contracts.sceneSettingsFromStorage(updated).path("backgroundColor").asText());
    assertFalse(contracts.sceneSettingsFromStorage(updated).has("fluids"));
    assertEquals(expectedFluids, contracts.sceneFluidsFromStorage(Json.parse(updated.toString()).deepCopy()));
    var empty = contracts.sceneStorageAfterPatch(updated, Json.obj("fluids", List.of()));
    assertTrue(contracts.sceneFluidsFromStorage(empty).isEmpty());
    updated.putNull("fluids");
    assertEquals(500, assertThrows(ApiException.class, () -> contracts.sceneFluidsFromStorage(updated)).status);
    // Public settings cannot smuggle an alternative nested fluid configuration.
    var badPatch = Json.obj("expectedRevision", 0, "settings", stored);
    contracts.normalizeScenePatch(badPatch);
    assertThrows(ApiException.class, () -> contracts.validate("StandaloneScenePatch", badPatch));
    assertFalse(legacy.has("fluids"), "Storage assembly cannot mutate the caller's legacy settings");
  }
}
