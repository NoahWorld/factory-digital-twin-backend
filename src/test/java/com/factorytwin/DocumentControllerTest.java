package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentControllerTest {
  @Test
  void manifestExposesFluidConfigurationBesideSettingsAndPreservesItsExactValues() throws Exception {
    var contracts = new Contracts();
    var db = mock(JdbcTemplate.class);
    var auth = mock(Auth.class);
    var projects = spy(new Projects(db, auth, mock(TransactionTemplate.class), mock(ProjectCovers.class), contracts));
    var controller = new DocumentController(mock(Documents.class), projects, contracts);
    var request = new MockHttpServletRequest();
    var user = new Auth.User("user", "tenant", "user@example.invalid", "user", "User", "delivery_manager", true, true);
    when(auth.require(request)).thenReturn(user);
    doReturn(Json.obj("id", "project")).when(projects).access(user, "project", false);
    var settings = Documents.settings(contracts);
    JsonNode fluid;
    try (var stream = getClass().getResourceAsStream("/fluid-validation-cases.json")) {
      fluid = Json.M.readTree(stream).path("base");
    }
    settings.set("fluids", Json.M.valueToTree(java.util.List.of(fluid)));
    when(db.queryForMap(contains("FROM documents"), eq("tenant"), eq("project")))
        .thenReturn(new HashMap<>(Map.of("kind", "scene", "settings", settings.toString())));
    var manifest = (Map<?, ?>) controller.manifest("project", request);
    assertEquals(settings.path("fluids"), manifest.get("fluids"));
    assertFalse(((JsonNode) manifest.get("settings")).has("fluids"));
    verify(projects).access(user, "project", false);
  }

  @Test
  void manifestNormalizesLegacySettingsWithoutReadingFieldsFromTheDocumentServiceProxy() {
    var contracts = new Contracts();
    var db = mock(JdbcTemplate.class);
    var auth = mock(Auth.class);
    var projects = spy(new Projects(db, auth, mock(TransactionTemplate.class), mock(ProjectCovers.class), contracts));
    // A mocked/proxied service must be used through methods, not its uninitialized fields.
    var controller = new DocumentController(mock(Documents.class), projects, contracts);
    var request = new MockHttpServletRequest();
    var user = new Auth.User("user", "tenant", "user@example.invalid", "user", "User", "delivery_manager", true, true);
    when(auth.require(request)).thenReturn(user);
    doReturn(Json.obj("id", "project")).when(projects).access(user, "project", false);
    for (String kind : new String[] {"scene", "canvas"}) {
      var settings = kind.equals("scene") ? Documents.settings(contracts) : Documents.theme();
      for (boolean missing : new boolean[] {true, false}) {
        settings.remove("preventBottomView");
        if (!missing && kind.equals("scene")) settings.put("preventBottomView", false);
        var row = new HashMap<String, Object>(Map.of("kind", kind, "settings", settings.toString()));
        when(db.queryForMap(contains("FROM documents"), eq("tenant"), eq("project"))).thenReturn(row);
        var manifest = (Map<?, ?>) controller.manifest("project", request);
        var actual = (JsonNode) manifest.get("settings");
        if (kind.equals("scene")) assertEquals(missing, actual.path("preventBottomView").asBoolean());
        else assertFalse(actual.has("preventBottomView"));
      }
    }
  }
}
