package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ModuleAccessTest {
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final TransactionTemplate tx = mock(TransactionTemplate.class);
  final Auth auth = spy(new Auth(db, mock(PasswordEncoder.class), tx, "x".repeat(32), "tenant", false));
  final Projects projects = new Projects(db, auth, tx, mock(ProjectCovers.class), mock(Contracts.class));
  final ProjectController controller = new ProjectController(projects);
  final Auth.User twoD = new Auth.User("viewer", "tenant", "viewer@example.invalid", "viewer", "Viewer", "delivery_manager", true, false);

  @Test void moduleGrantsAreValidatedAndAdminAlwaysHasBoth() {
    assertEquals(List.of("2d"), Auth.validateModules(Json.obj("modules", List.of("2d"))));
    assertEquals(List.of(), Auth.validateModules(Json.obj("modules", List.of())));
    for (var invalid : List.of(Json.obj(), Json.obj("modules", "2d"),
        Json.obj("modules", List.of("2d", "2d")), Json.obj("modules", List.of("4d")))) {
      assertEquals(400, assertThrows(ApiException.class, () -> Auth.validateModules(invalid)).status);
    }
    var admin = new Auth.User("admin", "tenant", "admin@example.invalid", "admin", "Admin", "platform_admin", false, false);
    assertEquals(List.of("2d", "3d"), admin.modules());
    assertTrue(admin.canAccess("3d"));
  }

  @Test void directProjectReadAndCreationRejectMissingModuleBeforeMutation() {
    when(db.queryForList(contains("AND p.id=?"), eq("viewer"), eq("tenant"), eq(false), eq("scene")))
        .thenReturn(List.of(Map.of("project_type", "3d")));
    var readError = assertThrows(ApiException.class, () -> projects.access(twoD, "scene", false));
    assertEquals(403, readError.status);
    assertEquals("module_access_denied", readError.code);

    var request = new MockHttpServletRequest();
    doReturn(twoD).when(auth).require(request);
    var createError = assertThrows(ApiException.class,
        () -> controller.create(Json.obj("name", "Denied scene", "projectType", "3d"), request));
    assertEquals(403, createError.status);
    assertEquals("module_access_denied", createError.code);
    verifyNoInteractions(tx);
    verify(db, never()).update(anyString(), any(Object[].class));
  }

  @Test void projectListFiltersByTheGrantedModule() {
    var request = new MockHttpServletRequest();
    doReturn(twoD).when(auth).require(request);
    when(db.queryForList(contains("p.project_type='2d'"), eq("viewer"), eq("tenant"), eq(false),
        eq(true), eq(false), eq(1001), eq(0))).thenReturn(List.of());
    var result = (com.fasterxml.jackson.databind.JsonNode) controller.list(request, null, 0);
    assertEquals(0, result.path("projects").size());
    verify(db).queryForList(contains("p.project_type='2d'"), eq("viewer"), eq("tenant"), eq(false),
        eq(true), eq(false), eq(1001), eq(0));
  }

  @Test void adminCanChangeAccountModulesButCannotRevokeAnotherAdmin() {
    var admin = new Auth.User("admin", "tenant", "admin@example.invalid", "admin", "Admin", "platform_admin", true, true);
    var request = new MockHttpServletRequest();
    doReturn(admin).when(auth).admin(request);
    when(tx.execute(any())).thenAnswer(invocation ->
        ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    var authController = new AuthController(auth);
    when(db.queryForList(contains("FOR UPDATE"), eq("tenant"), eq("viewer")))
        .thenReturn(List.of(Map.of("role", "viewer", "active", true)));
    var updated = (com.fasterxml.jackson.databind.JsonNode) authController.modules(
        "viewer", Json.obj("modules", List.of("3d")), request);
    assertEquals("3d", updated.path("modules").get(0).asText());
    verify(db).update(contains("UPDATE users SET can_access_2d"), eq(false), eq(true), eq("tenant"), eq("viewer"));
    verify(auth).audit(eq(admin), isNull(), eq("user.modules_changed"), any());

    when(db.queryForList(contains("FOR UPDATE"), eq("tenant"), eq("other-admin")))
        .thenReturn(List.of(Map.of("role", "platform_admin", "active", true)));
    var rejected = assertThrows(ApiException.class,
        () -> authController.modules("other-admin", Json.obj("modules", List.of()), request));
    assertEquals("admin_modules_fixed", rejected.code);
    when(db.queryForList(contains("FOR UPDATE"), eq("tenant"), eq("viewer")))
        .thenReturn(List.of(Map.of("role", "viewer", "active", false)));
    var inactive = assertThrows(ApiException.class,
        () -> authController.modules("viewer", Json.obj("modules", List.of("3d")), request));
    assertEquals("user_inactive", inactive.code);
    verify(db, times(1)).update(contains("UPDATE users SET can_access_2d"), any(), any(), any(), any());
  }

  @Test void adminCanReadAndEditUserProfileRoleModulesAndPassword() {
    var admin = new Auth.User("admin", "tenant", "admin@example.invalid", "admin", "Admin", "platform_admin", true, true);
    var request = new MockHttpServletRequest();
    doReturn(admin).when(auth).admin(request);
    when(tx.execute(any())).thenAnswer(invocation ->
        ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    var before = Map.<String, Object>of("id", "target", "email", "old@example.invalid",
        "login_name", "olduser", "display_name", "Old user", "role", "viewer",
        "active", true, "can_access_2d", false, "can_access_3d", false,
        "password_hash", "old-hash");
    var after = Map.<String, Object>of("id", "target", "email", "new@example.invalid",
        "login_name", "newuser", "display_name", "New user", "role", "delivery_manager",
        "active", true, "can_access_2d", false, "can_access_3d", true,
        "password_hash", "new-hash");
    when(db.queryForList(contains("SELECT * FROM users WHERE tenant_id=? AND id=?"),
        eq("tenant"), eq("target"))).thenReturn(List.of(before), List.of(before), List.of(after));
    when(auth.passwords.encode("new-password-2026")).thenReturn("new-hash");
    var controller = new AuthController(auth);
    var detail = (com.fasterxml.jackson.databind.JsonNode) controller.user("target", request);
    assertEquals("viewer", detail.path("user").path("role").asText());

    var saved = (com.fasterxml.jackson.databind.JsonNode) controller.update("target", Json.obj(
        "email", "new@example.invalid", "loginName", "newuser", "displayName", "New user",
        "role", "delivery_manager", "modules", List.of("3d"), "password", "new-password-2026"), request);
    assertEquals("delivery_manager", saved.path("user").path("role").asText());
    assertEquals("3d", saved.path("user").path("modules").get(0).asText());
    verify(db).update(contains("UPDATE users SET email=?"), eq("new@example.invalid"),
        eq("newuser"), eq("New user"), eq("delivery_manager"), eq(false), eq(true),
        eq("new-hash"), eq("tenant"), eq("target"));
    verify(db).update("DELETE FROM sessions WHERE tenant_id=? AND user_id=?", "tenant", "target");
    verify(auth).audit(eq(admin), isNull(), eq("user.updated"), any());
  }

  @Test void adminCannotRemoveOwnRoleOrLastAdministrator() {
    var admin = new Auth.User("admin", "tenant", "admin@example.invalid", "admin", "Admin", "platform_admin", true, true);
    var request = new MockHttpServletRequest();
    doReturn(admin).when(auth).admin(request);
    when(tx.execute(any())).thenAnswer(invocation ->
        ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    doAnswer(invocation -> {
      ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(null);
      return null;
    }).when(tx).executeWithoutResult(any());
    var row = Map.<String, Object>of("id", "admin", "role", "platform_admin", "active", true);
    when(db.queryForList(contains("SELECT * FROM users WHERE tenant_id=? AND id=? FOR UPDATE"),
        eq("tenant"), eq("admin"))).thenReturn(List.of(row));
    when(db.queryForList(contains("SELECT role,active FROM users WHERE tenant_id=? AND id=? FOR UPDATE"),
        eq("tenant"), eq("admin"))).thenReturn(List.of(row));
    var controller = new AuthController(auth);
    var demoteSelf = assertThrows(ApiException.class, () -> controller.update("admin", Json.obj(
        "email", "admin@example.invalid", "loginName", "admin", "displayName", "Admin",
        "role", "viewer", "modules", List.of()), request));
    assertEquals("self_admin_required", demoteSelf.code);

    var deleteSelf = assertThrows(ApiException.class, () -> controller.delete("admin", request));
    assertEquals("self_delete_forbidden", deleteSelf.code);
    when(db.queryForList(contains("SELECT role,active FROM users WHERE tenant_id=? AND id=? FOR UPDATE"),
        eq("tenant"), eq("other-admin"))).thenReturn(List.of(row));
    when(db.queryForObject(contains("SELECT count(*) FROM users"), eq(Integer.class), eq("tenant")))
        .thenReturn(1);
    var deleteLast = assertThrows(ApiException.class, () -> controller.delete("other-admin", request));
    assertEquals("last_admin_required", deleteLast.code);
    verify(db, never()).update(startsWith("UPDATE users SET active=false"), any(), any());
  }

  @Test void deletingUserRevokesSessionsAndRestoreKeepsTheAccount() {
    var admin = new Auth.User("admin", "tenant", "admin@example.invalid", "admin", "Admin", "platform_admin", true, true);
    var request = new MockHttpServletRequest();
    doReturn(admin).when(auth).admin(request);
    doAnswer(invocation -> {
      ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(null);
      return null;
    }).when(tx).executeWithoutResult(any());
    when(tx.execute(any())).thenAnswer(invocation ->
        ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    when(db.queryForList(contains("SELECT role,active FROM users WHERE tenant_id=? AND id=? FOR UPDATE"),
        eq("tenant"), eq("target"))).thenReturn(List.of(Map.of("role", "viewer", "active", true)));
    when(db.queryForList(contains("SELECT active FROM users WHERE tenant_id=? AND id=? FOR UPDATE"),
        eq("tenant"), eq("target"))).thenReturn(List.of(Map.of("active", false)));
    when(db.queryForList(contains("SELECT * FROM users WHERE tenant_id=? AND id=?"),
        eq("tenant"), eq("target"))).thenReturn(List.of(Map.of("id", "target",
            "email", "target@example.invalid", "login_name", "target", "display_name", "Target",
            "role", "viewer", "active", true, "can_access_2d", true, "can_access_3d", false)));
    var controller = new AuthController(auth);
    controller.delete("target", request);
    verify(db).update("UPDATE users SET active=false WHERE tenant_id=? AND id=?", "tenant", "target");
    verify(db).update("DELETE FROM sessions WHERE tenant_id=? AND user_id=?", "tenant", "target");
    verify(auth).audit(eq(admin), isNull(), eq("user.deleted"), any());

    var restored = (com.fasterxml.jackson.databind.JsonNode) controller.restore("target", request);
    assertTrue(restored.path("user").path("active").asBoolean());
    verify(db).update("UPDATE users SET active=true WHERE tenant_id=? AND id=?", "tenant", "target");
    verify(auth).audit(eq(admin), isNull(), eq("user.restored"), any());
  }
}
