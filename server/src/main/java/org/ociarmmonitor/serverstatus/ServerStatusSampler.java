package org.ociarmmonitor.serverstatus;

import org.ociarmmonitor.notification.AlertNotificationCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ServerStatusSampler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ServerStatusSampler.class);

  private final ServerStatusService serverStatusService;
  private final AlertNotificationCoordinator alertNotificationCoordinator;
  private final boolean enabled;

  public ServerStatusSampler(
    ServerStatusService serverStatusService,
    AlertNotificationCoordinator alertNotificationCoordinator,
    @Value("${monitor.server.metrics-enabled:true}") boolean enabled
  ) {
    this.serverStatusService = serverStatusService;
    this.alertNotificationCoordinator = alertNotificationCoordinator;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${monitor.server.metrics-sample-delay-millis:15000}", initialDelayString = "2000")
  public void sample() {
    if (!enabled) {
      return;
    }
    try {
      ServerStatusSnapshot snapshot = serverStatusService.sampleAndStore();
      try {
        alertNotificationCoordinator.afterSample(snapshot);
      } catch (RuntimeException exception) {
        LOGGER.warn("Alert notification evaluation failed: {}", exception.getMessage());
      }
    } catch (RuntimeException exception) {
      LOGGER.warn("Server status sample failed: {}", exception.getMessage());
    }
  }
}
