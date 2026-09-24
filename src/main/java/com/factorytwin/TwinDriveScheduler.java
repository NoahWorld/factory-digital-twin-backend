package com.factorytwin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Automatic source lifecycle belongs to the API service, not any browser connection. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "twin.mode", havingValue = "api")
public class TwinDriveScheduler {
  private final TwinDriveRuntime runtime;
  public TwinDriveScheduler(TwinDriveRuntime runtime) { this.runtime = runtime; }
  // Explicit default is essential: with just one scheduler bean Spring would share it with publishers.
  @Bean(name = "taskScheduler")
  public ThreadPoolTaskScheduler taskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("api-scheduled-");
    return scheduler;
  }

  @Bean(name = "twinAutomaticTaskScheduler")
  public ThreadPoolTaskScheduler twinAutomaticTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("twin-automatic-");
    return scheduler;
  }

  @Scheduled(fixedDelay = 100, scheduler = "twinAutomaticTaskScheduler")
  public void tick() { runtime.simulateAutomatically(); }
}
