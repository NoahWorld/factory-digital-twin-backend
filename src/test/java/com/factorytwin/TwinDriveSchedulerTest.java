package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class TwinDriveSchedulerTest {
  @Test void stalledPublicationCannotBlockAutomaticSourceScheduling() throws Exception {
    var runtime = mock(TwinDriveRuntime.class);
    var config = new TwinDriveScheduler(runtime);
    var publication = config.taskScheduler();
    var source = config.twinAutomaticTaskScheduler();
    publication.initialize(); source.initialize();
    var publishing = new CountDownLatch(1); var releasePublication = new CountDownLatch(1);
    var produced = new CountDownLatch(1);
    doAnswer(call -> { produced.countDown(); return null; }).when(runtime).simulateAutomatically();
    try {
      publication.schedule(() -> {
        publishing.countDown();
        try { assertTrue(releasePublication.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
      }, Instant.now());
      assertTrue(publishing.await(2, TimeUnit.SECONDS));
      source.schedule(config::tick, Instant.now());
      assertTrue(produced.await(2, TimeUnit.SECONDS), "Blocked publication must not block the independent simulator");
      assertEquals(1, releasePublication.getCount());
      assertEquals("twinAutomaticTaskScheduler", TwinDriveScheduler.class.getMethod("tick").getAnnotation(Scheduled.class).scheduler());
      verify(runtime).simulateAutomatically();
    } finally { releasePublication.countDown(); source.shutdown(); publication.shutdown(); }
  }
}
