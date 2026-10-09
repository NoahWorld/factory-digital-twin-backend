package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.*;

class TwinDriveWebSocketTest {
  final Auth auth = mock(Auth.class);
  final Projects projects = mock(Projects.class);
  final PublicShares publications = mock(PublicShares.class);
  final TwinDriveRuntime runtime = mock(TwinDriveRuntime.class);
  final TwinDriveWebSocket endpoint = new TwinDriveWebSocket(auth, projects, publications, new RequestFilter("http://127.0.0.1:5174", "api"), runtime);
  final Auth.User user = new Auth.User("viewer", "tenant", "viewer@example.invalid", "viewer", "Viewer", "viewer", true, true);
  final WebSocketSession socket = mock(WebSocketSession.class);
  @BeforeEach void setup() throws Exception {
    when(socket.getId()).thenReturn("connection"); when(socket.isOpen()).thenReturn(true);
    when(socket.getAttributes()).thenReturn(Map.of("token", "private-token", "project", "scene", "origin", "http://127.0.0.1:5174"));
    when(auth.fromToken("private-token")).thenReturn(user);
    when(projects.access(user, "scene", false)).thenReturn(Json.obj("projectType", "3d"));
    endpoint.afterConnectionEstablished(socket);
  }
  ObjectNode snapshot(long revision, long sequence) {
    return Json.obj("type", "snapshot", "projectId", "scene", "revision", revision, "sequence", sequence, "source", "api", "status", "running", "points", Json.obj(), "procedure", null);
  }
  void subscribe(long revision) throws Exception {
    var request = Json.obj("type", "subscribe", "expectedRevision", revision, "topics", List.of());
    when(runtime.subscribe(eq(user), eq("scene"), any(ObjectNode.class))).thenReturn(Json.obj("type", "subscribed", "revision", revision, "topics", List.of()));
    endpoint.handleTextMessage(socket, new TextMessage(request.toString()));
  }
  @Test void authenticatedApiFramesRequireSubscriptionAndRevisionReload() throws Exception {
    when(runtime.frame(user, "scene")).thenReturn(new TwinDriveRuntime.StreamFrame(snapshot(1, 1), List.of()));
    clearInvocations(socket); endpoint.push(); verify(socket, never()).sendMessage(any());
    subscribe(1); clearInvocations(socket); endpoint.push(); verify(socket).sendMessage(any(TextMessage.class));
    when(runtime.frame(user, "scene")).thenReturn(new TwinDriveRuntime.StreamFrame(snapshot(2, 1), List.of()));
    clearInvocations(socket); endpoint.push(); endpoint.push();
    var sent = ArgumentCaptor.forClass(WebSocketMessage.class); verify(socket).sendMessage(sent.capture());
    assertEquals("config_changed", Json.parse(sent.getValue().getPayload().toString()).path("type").asText());
    assertEquals(-1, endpoint.connections.get("connection").subscribedRevision);
  }
  @Test void sourceErrorSnapshotsRemainOnAuthorizedGatewayAndAreDelivered() throws Exception {
    subscribe(1); var failure = snapshot(1, 2).put("status", "error").put("error", "source_timeout").put("retryCount", 1);
    when(runtime.frame(user, "scene")).thenReturn(new TwinDriveRuntime.StreamFrame(failure, List.of()));
    clearInvocations(socket); endpoint.push();
    var sent = ArgumentCaptor.forClass(WebSocketMessage.class); verify(socket).sendMessage(sent.capture());
    assertEquals("source_timeout", Json.parse(sent.getValue().getPayload().toString()).path("error").asText());
    assertTrue(endpoint.connections.containsKey("connection")); verify(socket, never()).close(any());
  }
  @Test void revokedSessionAndPublicationStopReadsImmediately() throws Exception {
    when(auth.fromToken("private-token")).thenThrow(new ApiException(401, "unauthenticated", "Revoked"));
    endpoint.push(); verifyNoInteractions(runtime); assertTrue(endpoint.connections.isEmpty());
    when(socket.getAttributes()).thenReturn(Map.of("share", "link", "project", "scene", "origin", "http://127.0.0.1:5174"));
    when(publications.reader("link", "scene")).thenThrow(new ApiException(404, "publication_not_found", "Revoked"));
    endpoint.afterConnectionEstablished(socket); endpoint.push(); assertTrue(endpoint.connections.isEmpty()); verifyNoInteractions(runtime);
  }
  @Test void commandsReturnExplicitRetirementAndPublicationCannotCommand() throws Exception {
    var command = Json.obj("type", "command", "commandId", "c1", "expectedRevision", 1, "operation", "reset");
    when(runtime.command(user, "scene", command)).thenThrow(new ApiException(410, "legacy_simulation_removed", "Removed"));
    endpoint.handleTextMessage(socket, new TextMessage(command.toString()));
    var sent = ArgumentCaptor.forClass(WebSocketMessage.class); verify(socket, atLeastOnce()).sendMessage(sent.capture());
    assertTrue(sent.getAllValues().stream().anyMatch(message -> message.getPayload().toString().contains("legacy_simulation_removed")));
    endpoint.connections.clear(); when(socket.getAttributes()).thenReturn(Map.of("share", "link", "project", "scene", "origin", "http://127.0.0.1:5174"));
    var publicUser = Auth.User.publicationReader("tenant", "scene"); when(publications.reader("link", "scene")).thenReturn(publicUser);
    when(projects.access(publicUser, "scene", false)).thenReturn(Json.obj("projectType", "3d"));
    endpoint.afterConnectionEstablished(socket); endpoint.handleTextMessage(socket, new TextMessage(command.toString()));
    verify(runtime, never()).command(eq(publicUser), anyString(), any()); assertTrue(endpoint.connections.isEmpty());
  }
  @Test void transportErrorDeliveryRacesAlwaysCleanUp() throws Exception {
    var connection = endpoint.connections.get(socket.getId());
    doThrow(new IOException("Peer disconnected")).when(socket).sendMessage(any()); doThrow(new IllegalStateException("Already closed")).when(socket).close(any());
    assertDoesNotThrow(() -> endpoint.failConnection(connection, new ApiException(401, "unauthenticated", "Revoked"), CloseStatus.POLICY_VIOLATION));
    assertTrue(endpoint.connections.isEmpty());
  }
}
