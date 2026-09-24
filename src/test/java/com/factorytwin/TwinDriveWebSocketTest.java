package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.*;

class TwinDriveWebSocketTest {
  final Auth auth = mock(Auth.class);
  final Projects projects = mock(Projects.class);
  final Publications publications = mock(Publications.class);
  final TwinDriveRuntime runtime = mock(TwinDriveRuntime.class);
  final RequestFilter origins = new RequestFilter("http://127.0.0.1:5174", "api");
  final TwinDriveWebSocket endpoint = new TwinDriveWebSocket(auth, projects, publications, origins, runtime);
  final Auth.User user = new Auth.User("viewer", "tenant", "viewer@example.invalid", "viewer", "Viewer", "viewer", true, true);
  final WebSocketSession socket = mock(WebSocketSession.class);

  @BeforeEach void setup() throws Exception {
    when(socket.getId()).thenReturn("connection"); when(socket.isOpen()).thenReturn(true);
    when(socket.getAttributes()).thenReturn(Map.of("token", "private-token", "project", "scene", "origin", "http://127.0.0.1:5174"));
    when(auth.fromToken("private-token")).thenReturn(user);
    when(projects.access(user, "scene", false)).thenReturn(Json.obj("projectType", "3d"));
    endpoint.afterConnectionEstablished(socket);
  }

  @Test void viewerCommandRejectionIsCorrelatedAndClosesForbiddenControlChannel() throws Exception {
    var command = TwinDriveEngineTest.command("reset");
    when(runtime.command(eq(user), eq("scene"), eq(command))).thenThrow(new ApiException(403, "project_write_forbidden", "Read only"));
    endpoint.handleTextMessage(socket, new TextMessage(command.toString()));
    var messages = ArgumentCaptor.forClass(WebSocketMessage.class); verify(socket, atLeastOnce()).sendMessage(messages.capture());
    assertTrue(messages.getAllValues().stream().anyMatch(message -> message.getPayload().toString().contains(command.path("commandId").asText()) && message.getPayload().toString().contains("project_write_forbidden")));
    verify(socket).close(CloseStatus.POLICY_VIOLATION);
  }

  @Test void revokedSessionStopsSnapshotsImmediately() {
    when(auth.fromToken("private-token")).thenThrow(new ApiException(401, "unauthenticated", "Session revoked"));
    endpoint.push();
    verifyNoInteractions(runtime); assertTrue(endpoint.connections.isEmpty());
  }

  @Test void publicViewerCanSubscribeButCannotIssueCommandsAndRevocationStopsSnapshots() throws Exception {
    endpoint.connections.clear();
    WebSocketSession publicSocket = mock(WebSocketSession.class);
    var publicUser = Auth.User.publicationReader("tenant", "scene");
    when(publicSocket.getId()).thenReturn("public-connection");
    when(publicSocket.isOpen()).thenReturn(true);
    when(publicSocket.getAttributes()).thenReturn(Map.of("share", "public-link", "project", "scene",
        "origin", "http://127.0.0.1:5174"));
    when(publications.reader("public-link", "scene")).thenReturn(publicUser);
    when(projects.access(publicUser, "scene", false)).thenReturn(Json.obj("projectType", "3d"));
    endpoint.afterConnectionEstablished(publicSocket);
    var topics = List.of("plant/arm/angle");
    var subscribe = Json.obj("type", "subscribe", "expectedRevision", 1, "topics", topics);
    when(runtime.subscribe(publicUser, "scene", subscribe)).thenReturn(
        Json.obj("type", "subscribed", "revision", 1, "topics", topics));
    endpoint.handleTextMessage(publicSocket, new TextMessage(subscribe.toString()));
    verify(runtime).subscribe(publicUser, "scene", subscribe);

    endpoint.handleTextMessage(publicSocket,
        new TextMessage(TwinDriveEngineTest.command("reset").toString()));
    verify(runtime, never()).command(any(), anyString(), any());
    verify(publicSocket).close(CloseStatus.POLICY_VIOLATION);
    assertFalse(endpoint.connections.containsKey("public-connection"));

    clearInvocations(publicSocket);
    endpoint.afterConnectionEstablished(publicSocket);
    when(publications.reader("public-link", "scene")).thenThrow(
        new ApiException(404, "publication_not_found", "Revoked"));
    endpoint.push();
    verify(publicSocket).close(CloseStatus.POLICY_VIOLATION);
    assertFalse(endpoint.connections.containsKey("public-connection"));
    verify(runtime, never()).frame(publicUser, "scene");
  }

  @Test void snapshotConfigRevisionChangeNotifiesClientAndBusyDoesNotInventSample() throws Exception {
    when(runtime.frame(user, "scene")).thenReturn(new TwinDriveRuntime.StreamFrame(TwinDriveEngine.snapshot(TwinDriveEngine.initial("scene", 1, 1000)), List.of()));
    endpoint.push();
    when(runtime.frame(user, "scene")).thenReturn(new TwinDriveRuntime.StreamFrame(TwinDriveEngine.snapshot(TwinDriveEngine.initial("scene", 2, 2000)), List.of()));
    endpoint.push();
    var messages = ArgumentCaptor.forClass(WebSocketMessage.class); verify(socket, atLeastOnce()).sendMessage(messages.capture());
    assertTrue(messages.getAllValues().stream().anyMatch(message -> message.getPayload().toString().contains("config_changed")));
    clearInvocations(socket);
    when(runtime.frame(user, "scene")).thenThrow(new ApiException(409, "twin_runtime_busy", "Another operation"));
    endpoint.push(); verify(socket, never()).sendMessage(any()); assertFalse(endpoint.connections.isEmpty());
  }

  @Test void multipleViewersShareOneProjectIntegrationPerPush() throws Exception {
    WebSocketSession second = mock(WebSocketSession.class);
    var attributes = socket.getAttributes();
    when(second.getId()).thenReturn("second"); when(second.isOpen()).thenReturn(true); when(second.getAttributes()).thenReturn(attributes);
    endpoint.afterConnectionEstablished(second);
    when(runtime.frame(user, "scene")).thenReturn(new TwinDriveRuntime.StreamFrame(TwinDriveEngine.snapshot(TwinDriveEngine.initial("scene", 1, 1000)), List.of()));
    endpoint.push(); verify(runtime, times(1)).frame(user, "scene");
    assertEquals(2, endpoint.connections.size());
  }

  @Test void topicStreamRequiresAcknowledgedSubscriptionAndRevisionChangeRequiresResubscribe() throws Exception {
    var topics = List.of("plant/arm/angle");
    var snapshot = TwinDriveEngine.snapshot(TwinDriveEngine.initial("scene", 1, 1000));
    when(runtime.frame(user, "scene")).thenReturn(new TwinDriveRuntime.StreamFrame(snapshot, topics));
    clearInvocations(socket);
    endpoint.push(); verify(socket, never()).sendMessage(any());
    var subscribe = Json.obj("type", "subscribe", "expectedRevision", 1, "topics", topics);
    when(runtime.subscribe(user, "scene", subscribe)).thenReturn(Json.obj("type", "subscribed", "revision", 1, "topics", topics));
    endpoint.handleTextMessage(socket, new TextMessage(subscribe.toString()));
    endpoint.push();
    var messages = ArgumentCaptor.forClass(WebSocketMessage.class); verify(socket, times(2)).sendMessage(messages.capture());
    assertEquals("subscribed", Json.parse(messages.getAllValues().get(0).getPayload().toString()).path("type").asText());
    assertEquals("snapshot", Json.parse(messages.getAllValues().get(1).getPayload().toString()).path("type").asText());
    clearInvocations(socket);
    snapshot.put("revision", 2);
    endpoint.push(); endpoint.push();
    verify(socket, times(1)).sendMessage(messages.capture());
    assertEquals("config_changed", Json.parse(messages.getValue().getPayload().toString()).path("type").asText());
    assertEquals(-1, endpoint.connections.get("connection").subscribedRevision);
    var next = subscribe.deepCopy().put("expectedRevision", 2);
    when(runtime.subscribe(user, "scene", next)).thenReturn(Json.obj("type", "subscribed", "revision", 2, "topics", topics));
    endpoint.handleTextMessage(socket, new TextMessage(next.toString())); endpoint.push();
    assertEquals(2, endpoint.connections.get("connection").subscribedRevision);
  }

  @Test void cachedOlderFrameDoesNotRevokeNewerSubscriptionAcceptedMidPublication() throws Exception {
    WebSocketSession second = mock(WebSocketSession.class);
    var attributes = socket.getAttributes();
    when(second.getId()).thenReturn("second"); when(second.isOpen()).thenReturn(true); when(second.getAttributes()).thenReturn(attributes);
    endpoint.afterConnectionEstablished(second);
    var topics = List.of("plant/arm/angle");
    var subscribe = Json.obj("type", "subscribe", "expectedRevision", 1, "topics", topics);
    when(runtime.subscribe(user, "scene", subscribe)).thenReturn(Json.obj("type", "subscribed", "revision", 1, "topics", topics));
    endpoint.handleTextMessage(socket, new TextMessage(subscribe.toString()));
    endpoint.handleTextMessage(second, new TextMessage(subscribe.toString()));
    var order = new ArrayList<>(endpoint.connections.values());
    var firstSocket = order.getFirst().socket.getId().equals(socket.getId()) ? socket : second;
    var nextSocket = firstSocket == socket ? second : socket;
    var nextSubscribe = subscribe.deepCopy().put("expectedRevision", 2);
    when(runtime.subscribe(user, "scene", nextSubscribe)).thenReturn(Json.obj("type", "subscribed", "revision", 2, "topics", topics));
    when(runtime.frame(user, "scene")).thenReturn(new TwinDriveRuntime.StreamFrame(TwinDriveEngine.snapshot(TwinDriveEngine.initial("scene", 1, 1000)), topics));
    clearInvocations(socket, second);
    doAnswer(call -> {
      endpoint.handleTextMessage(nextSocket, new TextMessage(nextSubscribe.toString()));
      return null;
    }).when(firstSocket).sendMessage(any());
    endpoint.push();
    var sent = ArgumentCaptor.forClass(WebSocketMessage.class); verify(nextSocket, times(1)).sendMessage(sent.capture());
    assertEquals("subscribed", Json.parse(sent.getValue().getPayload().toString()).path("type").asText());
    assertEquals(2, endpoint.connections.get(nextSocket.getId()).subscribedRevision);
    assertEquals(2, endpoint.connections.get(nextSocket.getId()).revision);
    verify(runtime, times(1)).frame(user, "scene");
  }

  @Test void closedConnectionsAreRemovedWithoutSendingErrorsOrReadingRuntime() throws Exception {
    when(socket.isOpen()).thenReturn(false);
    clearInvocations(socket, runtime);
    assertDoesNotThrow(endpoint::push);
    assertTrue(endpoint.connections.isEmpty());
    verify(socket, never()).sendMessage(any()); verify(socket, never()).close(any());
    verifyNoInteractions(runtime);
  }

  @Test void peerClosingDuringSnapshotDoesNotStopLaterObserversInTheSameRound() throws Exception {
    WebSocketSession second = mock(WebSocketSession.class);
    var attributes = socket.getAttributes();
    when(second.getId()).thenReturn("second"); when(second.isOpen()).thenReturn(true); when(second.getAttributes()).thenReturn(attributes);
    endpoint.afterConnectionEstablished(second);
    var order = new ArrayList<>(endpoint.connections.values());
    var closing = order.getFirst().socket.getId().equals(socket.getId()) ? socket : second;
    var healthy = closing == socket ? second : socket;
    var open = new AtomicBoolean(true);
    when(closing.isOpen()).thenAnswer(call -> open.get());
    when(runtime.frame(user, "scene")).thenReturn(new TwinDriveRuntime.StreamFrame(TwinDriveEngine.snapshot(TwinDriveEngine.initial("scene", 1, 1000)), List.of()));
    clearInvocations(socket, second);
    doAnswer(call -> { open.set(false); throw new IllegalStateException("Session closed during snapshot send"); }).when(closing).sendMessage(any());
    assertDoesNotThrow(endpoint::push);
    verify(closing, times(1)).sendMessage(any()); // No attempt to send an error to the closed socket.
    verify(closing, never()).close(any());
    var delivered = ArgumentCaptor.forClass(WebSocketMessage.class); verify(healthy).sendMessage(delivered.capture());
    assertEquals("snapshot", Json.parse(delivered.getValue().getPayload().toString()).path("type").asText());
    assertFalse(endpoint.connections.containsKey(closing.getId()));
    assertTrue(endpoint.connections.containsKey(healthy.getId()));
    verify(runtime, times(1)).frame(user, "scene");
  }

  @Test void errorDeliveryAndCloseRacesAlwaysCleanUpWithoutEscapingThePublisher() throws Exception {
    var connection = endpoint.connections.get(socket.getId());
    doThrow(new IOException("Peer disconnected while reporting error")).when(socket).sendMessage(any());
    doThrow(new IllegalStateException("Session already closing")).when(socket).close(any());
    assertDoesNotThrow(() -> endpoint.failConnection(connection, new ApiException(401, "unauthenticated", "Revoked"), CloseStatus.POLICY_VIOLATION));
    assertTrue(endpoint.connections.isEmpty());
  }
}
