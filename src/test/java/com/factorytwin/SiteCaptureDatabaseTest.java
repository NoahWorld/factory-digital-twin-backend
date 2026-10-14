package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;

/** Opt-in against an EMPTY disposable PostgreSQL database; never point at an existing deployment. */
@EnabledIfEnvironmentVariable(named="TWIN_CAPTURE_TEST_JDBC_URL", matches=".+")
class SiteCaptureDatabaseTest {
  @Test void durableDraftConcurrentIdempotencyUploadStartCancelRetryAndLeaseRecovery() throws Exception {
    String url=System.getenv("TWIN_CAPTURE_TEST_JDBC_URL");
    var dataSource=new DriverManagerDataSource(url,"postgres","");
    var db=new JdbcTemplate(dataSource);
    assertEquals(0,db.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class),"Integration database must be empty and disposable.");
    Flyway.configure().dataSource(dataSource).load().migrate();
    db.update("INSERT INTO tenants(id,name) VALUES('capture-test','Capture test')");
    db.update("INSERT INTO projects(id,tenant_id,name,project_type) VALUES('capture-project','capture-test','Capture room','3d')");
    var user=new Auth.User("test-user","capture-test","test@example.invalid","test-user","Test","delivery_manager",true,true);
    var tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    var projects=spy(new Projects(db,mock(Auth.class),tx,mock(ProjectCovers.class),new Contracts()));
    doReturn(Json.obj()).when(projects).access(user,"capture-project",true);
    doReturn(Json.obj()).when(projects).access(user,"capture-project",false);
    var storage=mock(ObjectStorage.class); var s3=mock(S3Client.class);
    ReflectionTestUtils.setField(storage,"client",s3); ReflectionTestUtils.setField(storage,"bucket","capture-test");
    var service=new SiteCaptures(projects,storage);
    byte[] image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aFOsAAAAASUVORK5CYII=");
    var files=Json.M.createArrayNode(); for(int i=0;i<8;i++) files.add(Json.obj("filename","photo-"+i+".png","byteSize",image.length,"contentType","image/png"));
    var request=Json.obj("name","Room","inputKind","images","clientRequestId",Json.id(),"files",files);
    String id;
    try(var threads=Executors.newFixedThreadPool(2)) {
      var first=threads.submit(()->service.draft(user,"capture-project",request));
      var second=threads.submit(()->service.draft(user,"capture-project",request));
      id=first.get(10,TimeUnit.SECONDS).path("id").asText(); assertEquals(id,second.get(10,TimeUnit.SECONDS).path("id").asText());
    }
    assertEquals(1,db.queryForObject("SELECT count(*) FROM site_captures",Integer.class));
    assertEquals(8,db.queryForObject("SELECT count(*) FROM site_capture_sources",Integer.class));
    assertFalse(service.capabilities().path("available").asBoolean());
    assertEquals("site_capture_sources_incomplete",assertThrows(ApiException.class,()->service.start(user,"capture-project",id,false)).code);
    var bad=new MockHttpServletRequest(); bad.setContentType("image/png"); bad.setContent(new byte[image.length]);
    assertEquals("site_capture_source_signature",assertThrows(ApiException.class,()->service.upload(user,"capture-project",id,0,bad)).code);
    assertEquals(0,db.queryForObject("SELECT count(*) FROM site_capture_sources WHERE uploaded",Integer.class));
    var expiredBody=new MockHttpServletRequest() {
      @Override public jakarta.servlet.ServletInputStream getInputStream() {
        db.update("UPDATE site_capture_sources SET upload_until=now()-interval '1 second' WHERE capture_id=?",id);
        return super.getInputStream();
      }
    };
    expiredBody.setContentType("image/png"); expiredBody.setContent(image);
    assertEquals("site_capture_state_conflict",assertThrows(ApiException.class,()->service.upload(user,"capture-project",id,0,expiredBody)).code);
    verify(s3,never()).putObject(any(java.util.function.Consumer.class),any(software.amazon.awssdk.core.sync.RequestBody.class));
    for(int i=0;i<8;i++) {
      var upload=new MockHttpServletRequest(); upload.setContentType("image/png"); upload.setContent(image);
      service.upload(user,"capture-project",id,i,upload);
    }
    var stored=service.present(service.row(user,"capture-project",id));
    assertEquals(8,stored.path("completed").asInt()); assertTrue(stored.path("files").get(0).path("uploaded").asBoolean());
    assertEquals("site_capture_unavailable",assertThrows(ApiException.class,()->service.start(user,"capture-project",id,false)).code);
    db.update("INSERT INTO site_capture_workers(id,available,heartbeat_at) VALUES('test-worker',true,now()-interval '2 minutes')");
    assertFalse(service.capabilities().path("available").asBoolean());
    db.update("UPDATE site_capture_workers SET heartbeat_at=now()"); assertTrue(service.capabilities().path("available").asBoolean());
    assertEquals("queued",service.start(user,"capture-project",id,false).path("status").asText());
    assertEquals("queued",service.start(user,"capture-project",id,false).path("status").asText());
    var worker=spy(new SiteCaptureWorker(service,mock(SiteCaptureProcess.class)));
    doNothing().when(worker).execute(anyMap(),anyString()); worker.ready=true;
    try {
      worker.next(); var running=service.row(user,"capture-project",id); String lease=(String)running.get("lease_owner");
      assertEquals("running",running.get("status")); assertEquals(1,((Number)running.get("attempts")).intValue());
      worker.heartbeat(id,lease); worker.progress(id,lease,Json.obj("stage","training","completed",100,"total",1000));
      assertEquals(100,((Number)service.row(user,"capture-project",id).get("completed")).intValue());
      assertEquals("site_capture_active",assertThrows(ApiException.class,()->service.deleteProjectSources("capture-test","capture-project")).code);
      db.update("UPDATE site_captures SET lease_until=now()-interval '1 second' WHERE id=?",id);
      // An expired executor may finish late. Its failure must not prevent lease recovery.
      doCallRealMethod().when(worker).execute(anyMap(),anyString());
      worker.execute(running,lease);
      assertEquals("running",service.row(user,"capture-project",id).get("status"));
      doNothing().when(worker).execute(anyMap(),anyString());
      worker.next(); var recovered=service.row(user,"capture-project",id); assertEquals(2,((Number)recovered.get("attempts")).intValue());
      assertNotEquals(lease,recovered.get("lease_owner")); assertThrows(IllegalStateException.class,()->worker.heartbeat(id,lease));
      service.cancel(user,"capture-project",id); assertEquals("canceled",service.row(user,"capture-project",id).get("status"));
      assertThrows(IllegalStateException.class,()->worker.heartbeat(id,(String)recovered.get("lease_owner")));
      // A canceled task stays traceable while a storage PUT may still be in flight.
      db.update("INSERT INTO site_capture_objects(capture_id,object_key) VALUES(?,?)",id,"in-flight-output");
      assertEquals(recovered.get("lease_owner"),service.row(user,"capture-project",id).get("lease_owner"));
      assertEquals("site_capture_active",assertThrows(ApiException.class,()->service.delete(user,"capture-project",id)).code);
      assertEquals("site_capture_active",assertThrows(ApiException.class,()->service.deleteProjectSources("capture-test","capture-project")).code);
      worker.releaseCanceledLease(id,lease); // stale executor must not release its successor's ownership
      assertNotNull(service.row(user,"capture-project",id).get("lease_owner"));
      worker.releaseCanceledLease(id,(String)recovered.get("lease_owner"));
      assertNull(service.row(user,"capture-project",id).get("lease_owner"));
      service.delete(user,"capture-project",id); assertEquals(0,db.queryForObject("SELECT count(*) FROM site_capture_sources",Integer.class));
      assertEquals(0,db.queryForObject("SELECT count(*) FROM site_capture_objects",Integer.class));
      var retry=service.draft(user,"capture-project",request); String retryId=retry.path("id").asText();
      db.update("UPDATE site_capture_sources SET uploaded=true WHERE capture_id=?",retryId);
      db.update("UPDATE site_captures SET status='running',stage='training',attempts=3,lease_until=now()-interval '1 second' WHERE id=?",retryId);
      worker.next(); assertEquals("site_capture_worker_interrupted",service.row(user,"capture-project",retryId).get("error_code"));
      assertEquals("queued",service.start(user,"capture-project",retryId,true).path("status").asText());
      assertEquals(0,((Number)service.row(user,"capture-project",retryId).get("attempts")).intValue());

      // The unfinished-task budget is tenant-wide and must survive simultaneous drafts
      // in different projects; project row locks alone cannot enforce this boundary.
      db.update("INSERT INTO projects(id,tenant_id,name,project_type) VALUES('second-project','capture-test','Second room','3d')");
      doReturn(Json.obj()).when(projects).access(user,"second-project",true);
      doReturn(Json.obj()).when(projects).access(user,"second-project",false);
      var created = new ArrayList<String>();
      try(var threads=Executors.newFixedThreadPool(12)) {
        var gate=new CountDownLatch(1);
        var futures=new ArrayList<Future<String>>();
        for(int i=0;i<12;i++) {
          String project=i%2==0 ? "capture-project" : "second-project";
          var input=request.deepCopy().put("clientRequestId",Json.id());
          futures.add(threads.submit(()->{
            gate.await();
            try { return service.draft(user,project,input).path("id").asText(); }
            catch(ApiException error) { assertEquals("site_capture_queue_full",error.code); return null; }
          }));
        }
        gate.countDown();
        for(var future:futures) { String capture=future.get(20,TimeUnit.SECONDS); if(capture!=null) created.add(capture); }
      }
      assertEquals(9,created.size()); // One queued retry already occupies the tenth slot.
      assertEquals(10,db.queryForObject("SELECT count(*) FROM site_captures WHERE status IN ('uploading','queued','running')",Integer.class));
      assertEquals(80,db.queryForObject("SELECT count(*) FROM site_capture_sources",Integer.class));
      assertEquals(retryId,service.draft(user,"capture-project",request).path("id").asText());
      service.cancel(user,"capture-project",retryId);
      assertEquals("uploading",service.draft(user,"capture-project",request.deepCopy().put("clientRequestId",Json.id())).path("status").asText());
      assertEquals(10,db.queryForObject("SELECT count(*) FROM site_captures WHERE status IN ('uploading','queued','running')",Integer.class));
    } finally { worker.stop(); }
  }
}
