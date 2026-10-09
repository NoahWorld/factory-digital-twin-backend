package com.factorytwin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** API observation lifecycle belongs to the server, not any browser connection. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "twin.mode", havingValue = "api")
public class TwinDriveScheduler {
  private final ApiMotionSources sources;
  public TwinDriveScheduler(ApiMotionSources sources) { this.sources = sources; }
  // Explicit default is essential: with just one scheduler bean Spring would share it with publishers.
  @Bean(name = "taskScheduler")
  public ThreadPoolTaskScheduler taskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("api-scheduled-");
    return scheduler;
  }

  @Bean(name = "twinSourceTaskScheduler")
  public ThreadPoolTaskScheduler twinSourceTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("twin-source-");
    return scheduler;
  }

  @Scheduled(fixedDelay = 100, scheduler = "twinSourceTaskScheduler")
  public void tick() { sources.collect(); }
}
