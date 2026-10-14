package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.*;

class SiteCapturesTest {
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Auth auth = mock(Auth.class);
  final TransactionTemplate tx = mock(TransactionTemplate.class);
  final Projects projects = spy(new Projects(db, auth, tx, mock(ProjectCovers.class), new Contracts()));
  final ObjectStorage store = mock(ObjectStorage.class);
  final SiteCaptures service = new SiteCaptures(projects, store);
  final Auth.User user = new Auth.User("user", "tenant", "user@example.invalid", "user", "User", "delivery_manager", true, true);
  @TempDir Path temp;

  ObjectNode draft(int count) {
    var files = Json.M.createArrayNode();
    for (int i=0;i<count;i++) files.add(Json.obj("filename", "image-"+i+".jpg", "contentType", "image/jpeg", "byteSize", 100));
    return Json.obj("name", "Room", "inputKind", "images", "clientRequestId", "71cd4435-9f78-44d7-8ccc-d6f1ad75505f", "files", files);
  }
  Map<String,Object> job(String status) {
    var row=new HashMap<String,Object>();
    row.put("id","capture"); row.put("status",status); row.put("name","Room"); row.put("input_kind","images"); row.put("stage",status);
    row.put("completed",0); row.put("total",8); row.put("created_at",Instant.now()); row.put("updated_at",Instant.now());
    return row;
  }
  void allowed() {
    doReturn(Json.obj()).when(projects).access(user,"project",true);
    doReturn(Json.obj()).when(projects).access(user,"project",false);
    doNothing().when(projects).lock(user,"project");
    when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
  }
  void current(Map<String,Object> row) {
    when(db.queryForList(contains("SELECT * FROM site_captures WHERE tenant_id=? AND project_id=? AND id=?"),eq("tenant"),eq("project"),eq("capture"))).thenReturn(List.of(row));
  }
  @Test void inputsHaveExactCountsFormatsAndBudgets() {
    assertDoesNotThrow(()->SiteCaptures.validateDraft(draft(8)));
    assertDoesNotThrow(()->SiteCaptures.validateDraft(draft(120)));
    for(int count:List.of(0,7,121)) assertThrows(ApiException.class,()->SiteCaptures.validateDraft(draft(count)));
    var large=draft(64); for(var f:large.path("files")) ((ObjectNode)f).put("byteSize",SiteCaptures.IMAGE_BYTES);
    assertThrows(ApiException.class,()->SiteCaptures.validateDraft(large));
    var image=draft(8); ((ObjectNode)image.path("files").get(0)).put("byteSize",SiteCaptures.IMAGE_BYTES+1);
    assertThrows(ApiException.class,()->SiteCaptures.validateDraft(image));
    var video=draft(1).put("inputKind","video");
    ((ObjectNode)video.path("files").get(0)).put("filename","room.mp4").put("contentType","video/mp4").put("byteSize",SiteCaptures.VIDEO_BYTES);
    assertDoesNotThrow(()->SiteCaptures.validateDraft(video));
    ((ObjectNode)video.path("files").get(0)).put("byteSize",SiteCaptures.VIDEO_BYTES+1);
    assertThrows(ApiException.class,()->SiteCaptures.validateDraft(video));
  }
  @Test void rejectsPathsDuplicateNamesSpoofedMimeMixedKindsAndNonUuid() {
    for(String name:List.of("../image.jpg","dir\\image.jpg","image\n.jpg")) {
      var input=draft(8); ((ObjectNode)input.path("files").get(0)).put("filename",name);
      assertThrows(ApiException.class,()->SiteCaptures.validateDraft(input));
    }
    var duplicate=draft(8); ((ObjectNode)duplicate.path("files").get(1)).put("filename","IMAGE-0.JPG");
    assertThrows(ApiException.class,()->SiteCaptures.validateDraft(duplicate));
    var mime=draft(8); ((ObjectNode)mime.path("files").get(0)).put("contentType","video/mp4");
    assertThrows(ApiException.class,()->SiteCaptures.validateDraft(mime));
    var mixed=draft(8); ((ObjectNode)mixed.path("files").get(0)).put("filename","video.mp4").put("contentType","video/mp4");
    assertThrows(ApiException.class,()->SiteCaptures.validateDraft(mixed));
    assertThrows(ApiException.class,()->SiteCaptures.validateDraft(draft(8).put("clientRequestId","invalid-request")));
  }
  @Test void permissionDenialPrecedesBodyValidationStorageOrCapability() throws Exception {
    doThrow(new ApiException(403,"project_write_forbidden","Editor required")).when(projects).access(user,"project",true);
    assertEquals(403,assertThrows(ApiException.class,()->service.draft(user,"project",null)).status);
    var request=mock(jakarta.servlet.http.HttpServletRequest.class);
    assertEquals(403,assertThrows(ApiException.class,()->service.upload(user,"project","capture",0,request)).status);
    assertEquals(403,assertThrows(ApiException.class,()->service.start(user,"project","capture",false)).status);
    verifyNoInteractions(db,store,request,tx);
  }
  @Test void noGpuIsUnavailableWithoutCreatingFakeSuccess() {
    assertFalse(service.capabilities().path("available").asBoolean());
    assertEquals("site_capture_unavailable",assertThrows(ApiException.class,service::requireReady).code);
    verify(db,atLeastOnce()).queryForList(contains("available=true AND heartbeat_at>now()-interval '90 seconds'"));
    verifyNoInteractions(store);
  }
  @Test void capabilityRequiresFreshReadyWorkerRecord() {
    when(db.queryForList(contains("available=true AND heartbeat_at>now()-interval '90 seconds'"))).thenReturn(List.of(Map.of("id","worker")));
    var caps=service.capabilities(); assertTrue(caps.path("available").asBoolean()); assertTrue(caps.path("reason").isNull());
    assertEquals(209715200,caps.path("maxOutputBytes").asLong());
  }
  @Test void unconfiguredGpuProcessIsExplicitlyUnavailable() {
    var process=new SiteCaptureProcess("","",7200);
    assertFalse(process.probe().path("available").asBoolean());
    assertFalse(process.probe().path("reason").asText().isBlank());
  }
  @Test void sameRequestReturnsExistingDraftWithoutInsertingAndMismatchConflicts() {
    allowed(); var row=job("uploading"); row.put("request_json",SiteCaptures.validateDraft(draft(8)).toString());
    when(db.queryForList(contains("AND client_request_id=?"),eq("tenant"),eq("project"),anyString())).thenReturn(List.of(row));
    assertEquals("capture",service.draft(user,"project",draft(8)).path("id").asText());
    assertEquals("site_capture_request_conflict",assertThrows(ApiException.class,()->service.draft(user,"project",draft(8).put("name","different"))).code);
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void startRequiresEverySourceBeforeCheckingGpu() {
    allowed(); current(job("uploading"));
    when(db.queryForList(contains("SELECT * FROM site_capture_sources WHERE capture_id=? ORDER BY"),eq("capture"))).thenReturn(List.of(Map.of("uploaded",false)));
    assertEquals("site_capture_sources_incomplete",assertThrows(ApiException.class,()->service.start(user,"project","capture",false)).code);
    verify(db,never()).queryForList(contains("site_capture_workers"));
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void fullyUploadedWithoutGpuStaysDraft() {
    allowed(); current(job("uploading"));
    when(db.queryForList(contains("SELECT * FROM site_capture_sources WHERE capture_id=? ORDER BY"),eq("capture"))).thenReturn(List.of(Map.of("uploaded",true)));
    assertEquals("site_capture_unavailable",assertThrows(ApiException.class,()->service.start(user,"project","capture",false)).code);
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void queuedStartIsIdempotentWithoutResettingAttempts() {
    allowed(); current(job("queued"));
    assertEquals("queued",service.start(user,"project","capture",false).path("status").asText());
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void readsAndOutputAreProjectScoped() {
    doThrow(new ApiException(404,"project_not_found","No access")).when(projects).access(user,"project",false);
    assertThrows(ApiException.class,()->service.content(user,"project","capture",new org.springframework.mock.web.MockHttpServletResponse()));
    assertThrows(ApiException.class,()->service.row(user,"project","capture"));
    verifyNoInteractions(db,store);
  }
  @Test void incompleteOutputDoesNotReadStorage() {
    allowed(); current(job("running"));
    assertThrows(ApiException.class,()->service.content(user,"project","capture",new org.springframework.mock.web.MockHttpServletResponse())); verifyNoInteractions(store);
  }
  @Test void successfulContentStreamsOwnedBytesWithoutRedirectAndRejectsStorageSizeMismatch() throws Exception {
    allowed(); var row=job("succeeded"); row.put("output_key","tenant/project/site-captures/capture/result.ply"); row.put("result",Json.obj("byteSize",4).toString()); current(row);
    var s3=mock(software.amazon.awssdk.services.s3.S3Client.class);
    org.springframework.test.util.ReflectionTestUtils.setField(store,"client",s3);
    org.springframework.test.util.ReflectionTestUtils.setField(store,"bucket","private");
    var bytes=new byte[]{1,2,3,4};
    var source=new software.amazon.awssdk.core.ResponseInputStream<>(software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().contentLength(4L).build(),new ByteArrayInputStream(bytes));
    when(s3.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenReturn(source);
    var response=new org.springframework.mock.web.MockHttpServletResponse();
    service.content(user,"project","capture",response);
    assertArrayEquals(bytes,response.getContentAsByteArray()); assertEquals(4,response.getContentLengthLong()); assertEquals("application/octet-stream",response.getContentType());
    assertNull(response.getHeader("Location")); assertEquals("private, no-store",response.getHeader("Cache-Control"));
    verify(s3).getObject(argThat((software.amazon.awssdk.services.s3.model.GetObjectRequest request)->request.bucket().equals("private") && request.key().equals(row.get("output_key"))));
    var bad=new software.amazon.awssdk.core.ResponseInputStream<>(software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().contentLength(5L).build(),new ByteArrayInputStream(bytes));
    when(s3.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenReturn(bad);
    var rejected=new org.springframework.mock.web.MockHttpServletResponse();
    assertEquals("site_capture_result_invalid",assertThrows(ApiException.class,()->service.content(user,"project","capture",rejected)).code);
    assertEquals(0,rejected.getContentAsByteArray().length);
  }
  @Test void activeProjectDeleteStopsBeforeAnyObjectRemoval() {
    when(db.queryForList(contains("SELECT * FROM site_captures WHERE tenant_id=? AND project_id=?"),eq("tenant"),eq("project"))).thenReturn(List.of(job("running")));
    assertEquals("site_capture_active",assertThrows(ApiException.class,()->service.deleteProjectSources("tenant","project")).code);
    verifyNoInteractions(store);
  }
  @Test void uploadInProgressBlocksDeletionEvenAfterCancellation() {
    when(db.queryForObject(contains("lease_owner IS NOT NULL"),eq(Integer.class),eq("capture"))).thenReturn(0);
    when(db.queryForObject(contains("upload_until>now()"),eq(Integer.class),eq("capture"))).thenReturn(1);
    assertEquals("site_capture_upload_busy",assertThrows(ApiException.class,()->service.assertDeletable(List.of(job("canceled")))).code);
    verifyNoInteractions(store);
  }
  @Test void canceledWorkerStillWritingBlocksDeletionUntilCleanupAcknowledged() {
    when(db.queryForObject(contains("lease_owner IS NOT NULL"),eq(Integer.class),eq("capture"))).thenReturn(1);
    assertEquals("site_capture_active",assertThrows(ApiException.class,()->service.assertDeletable(List.of(job("canceled")))).code);
    verifyNoInteractions(store);
  }
  @Test void canceledOrLostLeaseCannotRenewOrReportProgress() {
    var worker=new SiteCaptureWorker(service,mock(SiteCaptureProcess.class));
    try {
      assertThrows(IllegalStateException.class,()->worker.heartbeat("capture","lease"));
      assertThrows(IllegalStateException.class,()->worker.progress("capture","lease",Json.obj("stage","training","completed",1,"total",10)));
      assertThrows(ApiException.class,()->worker.progress("capture","lease",Json.obj("stage","surprise","completed",1,"total",10)));
      assertThrows(ApiException.class,()->worker.progress("capture","lease",Json.obj("stage","training","completed",11,"total",10)));
    } finally { worker.stop(); }
  }
  @Test void nativeErrorsDoNotExposePathsOrUntrustedMessages() {
    var error=SiteCaptureWorker.failure(new IllegalStateException("secret/path"),"capture");
    assertEquals("site_capture_worker_failed",error.path("code").asText()); assertFalse(error.toString().contains("secret"));
    assertEquals("site_capture_worker_failed",SiteCaptureWorker.failure(new ApiException(400,"invalid_input","secret/path"),"capture").path("code").asText());
  }
  ObjectNode result() throws Exception {
    var header=new StringBuilder("ply\nformat binary_little_endian 1.0\nelement vertex 2\n");
    for(String field:SiteCaptureResult.FIELDS) header.append("property float ").append(field).append('\n');
    header.append("end_header\n");
    try(var out=Files.newOutputStream(temp.resolve("scene.ply"))) {
      out.write(header.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      var values=ByteBuffer.allocate(SiteCaptureResult.FIELDS.size()*4).order(ByteOrder.LITTLE_ENDIAN);
      for(int i=0;i<SiteCaptureResult.FIELDS.size();i++) values.putFloat(i==13?1:0);
      out.write(values.array());
      values.putFloat(0,1); out.write(values.array());
    }
    return Json.obj("format","ply","file","scene.ply","byteSize",Files.size(temp.resolve("scene.ply")),"gaussianCount",2,
        "bounds",Json.obj("min",List.of(0,0,0),"max",List.of(1,0,0)),"initialCamera",Json.obj("position",List.of(0,0,1),"target",List.of(0,0,0),"up",List.of(0,1,0)),
        "quality",Json.obj("inputImages",8,"registeredImages",8,"points3D",500,"meanReprojectionError",0.5),"warnings",List.of(),"coordinateSystem","colmap","units","arbitrary");
  }
  @Test void validatedPlyPublishesOnlyViewerMetadata() throws Exception {
    var input=result().put("internalPath","secret/path");
    var output=SiteCaptureResult.validate(temp,input);
    assertEquals(2,output.path("gaussianCount").asInt()); assertFalse(output.has("file")); assertFalse(output.has("internalPath"));
  }
  @Test void resultRejectsSizeBoundsCountsUnsafePathsNonfiniteValuesAndTrailingBytes() throws Exception {
    var input=result(); input.put("byteSize",1); final var wrongSize=input; assertThrows(ApiException.class,()->SiteCaptureResult.validate(temp,wrongSize));
    input=result().put("file","../scene.ply"); final var wrongPath=input; assertThrows(ApiException.class,()->SiteCaptureResult.validate(temp,wrongPath));
    input=result(); ((ObjectNode)input.path("bounds")).set("max",Json.M.valueToTree(List.of(2,0,0))); final var wrongBounds=input; assertThrows(ApiException.class,()->SiteCaptureResult.validate(temp,wrongBounds));
    input=result(); final var invalidFloat=input;
    try(var file=new RandomAccessFile(temp.resolve("scene.ply").toFile(),"rw")) { file.seek(file.length()-4); file.writeInt(Integer.reverseBytes(Float.floatToIntBits(Float.NaN))); }
    assertThrows(ApiException.class,()->SiteCaptureResult.validate(temp,invalidFloat));
    input=result(); Files.write(temp.resolve("scene.ply"),new byte[]{0},StandardOpenOption.APPEND); input.put("byteSize",Files.size(temp.resolve("scene.ply"))); final var trailing=input;
    assertThrows(ApiException.class,()->SiteCaptureResult.validate(temp,trailing));
  }
  @Test void resultRejectsViewerUnsafeScalesDegenerateBoundsAndImpreciseMetadata() throws Exception {
    for(float scale:new float[]{-12.01f,9.01f}) {
      var input=result();
      try(var file=new RandomAccessFile(temp.resolve("scene.ply").toFile(),"rw")) {
        file.seek(file.length()-SiteCaptureResult.FIELDS.size()*4+10*4);
        file.writeInt(Integer.reverseBytes(Float.floatToIntBits(scale)));
      }
      assertThrows(ApiException.class,()->SiteCaptureResult.validate(temp,input));
    }
    var degenerate=result(); ((ObjectNode)degenerate.path("bounds")).set("max",Json.M.valueToTree(List.of(0,0,0)));
    assertThrows(ApiException.class,()->SiteCaptureResult.validate(temp,degenerate));
    var imprecise=result(); ((ObjectNode)imprecise.path("bounds")).set("max",Json.M.valueToTree(List.of(1.00001,0,0)));
    assertThrows(ApiException.class,()->SiteCaptureResult.validate(temp,imprecise));
    var overflow=result(); overflow.set("gaussianCount",Json.M.readTree("18446744073709551618"));
    assertThrows(ApiException.class,()->SiteCaptureResult.validate(temp,overflow));
  }
  @Test void nativeProtocolReadIsBounded() {
    assertThrows(IOException.class,()->SiteCaptureProcess.boundedText(new ByteArrayInputStream(new byte[20]),10));
  }
}
