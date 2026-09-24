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
    var target = spy(new TwinDriveDocuments(projects, contracts));
    var service = proxied(target);
    assertTrue(AopUtils.isCglibProxy(service));
    var user = new Auth.User("editor", "tenant", "editor@example.invalid", "editor", "Editor", "delivery_manager", true, true);
    var request = new MockHttpServletRequest();
    when(auth.require(request)).thenReturn(user);
    doReturn(Json.obj("projectType", "3d", "projectRole", "editor")).when(projects).access(user, "scene", false);
    when(database.queryForList(anyString(), eq("tenant"), eq("scene"))).thenReturn(List.of());
    var controller = new TwinDriveController(service, auth);
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
}
