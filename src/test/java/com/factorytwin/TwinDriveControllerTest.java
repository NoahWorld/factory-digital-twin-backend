package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

class TwinDriveControllerTest {
  static TwinDriveDocuments proxied(TwinDriveDocuments target) {
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
    return (TwinDriveDocuments) factory.getProxy();
  }

  @Test void firstReadAndSaveUseProxiedServiceMethodsNotUninitializedProxyFields() {
    var auth = mock(Auth.class);
    var database = mock(JdbcTemplate.class);
    var contracts = new Contracts();
    var projects = spy(new Projects(database, auth, mock(TransactionTemplate.class), mock(ProjectCovers.class), contracts));
    var target = spy(new TwinDriveDocuments(projects, contracts, new SourceClient("")));
    var service = proxied(target);
    assertTrue(AopUtils.isCglibProxy(service));
    var user = new Auth.User("editor", "tenant", "editor@example.invalid", "editor", "Editor", "delivery_manager", true, true);
    var request = new MockHttpServletRequest();
    when(auth.require(request)).thenReturn(user);
    doReturn(Json.obj("projectType", "3d", "projectRole", "editor")).when(projects).access(user, "scene", false);
    when(database.queryForList(anyString(), eq("tenant"), eq("scene"))).thenReturn(List.of());
    var controller = new TwinDriveController(service, auth, mock(ApiMotionSources.class));
    var document = (ObjectNode) controller.read("scene", request);
    assertEquals(0, document.path("revision").asInt());
    assertTrue(document.path("editable").asBoolean());
    assertEquals(TwinDriveDocuments.emptyConfig(), document.path("config"));

    var patch = Json.obj("expectedRevision", 0, "config", TwinDriveDocuments.emptyConfig());
    var saved = document.deepCopy().put("revision", 1);
    doReturn(saved).when(target).save(user, "scene", patch);
    assertEquals(saved, controller.save("scene", patch, request));
    verify(target).save(user, "scene", patch);
  }
  @Test void unsavedSourceTestsRequireEditorPermissionBeforeNetworkAccessThroughProxy() {
    var auth = mock(Auth.class); var projects = mock(Projects.class); var sources = mock(ApiMotionSources.class);
    var service = proxied(new TwinDriveDocuments(projects, new Contracts(), new SourceClient("")));
    var controller = new TwinDriveController(service, auth, sources); var request = new MockHttpServletRequest();
    var user = new Auth.User("editor", "tenant", "editor@example.invalid", "editor", "Editor", "delivery_manager", true, true);
    when(auth.require(request)).thenReturn(user);
    when(projects.access(user, "scene", true)).thenReturn(Json.obj("projectType", "3d"));
    var connection = ApiMotionSourcesTest.connection("rest", ApiMotionSources.REST_PATH); var response = Json.obj("timestamp", "2026-10-09T00:00:00Z", "fields", List.of());
    when(sources.test(connection)).thenReturn(response);
    assertEquals(response, controller.test("scene", Json.obj("connection", connection), request));
    verify(projects).access(user, "scene", true); verify(sources).test(connection);
    clearInvocations(sources);
    when(projects.access(user, "scene", true)).thenThrow(new ApiException(403, "project_edit_denied", "Viewer cannot edit."));
    assertEquals("project_edit_denied", assertThrows(ApiException.class, () -> controller.test("scene", Json.obj("connection", connection), request)).code);
    verifyNoInteractions(sources);
  }
}
