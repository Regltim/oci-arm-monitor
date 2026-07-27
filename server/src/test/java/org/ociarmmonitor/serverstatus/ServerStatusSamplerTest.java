package org.ociarmmonitor.serverstatus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.ociarmmonitor.notification.AlertNotificationCoordinator;

class ServerStatusSamplerTest {

  @Test
  void evaluatesNotificationTransitionsAfterScheduledSampleIsStored() {
    ServerStatusService serverStatusService = mock(ServerStatusService.class);
    AlertNotificationCoordinator notificationCoordinator = mock(AlertNotificationCoordinator.class);
    ServerStatusSnapshot snapshot = mock(ServerStatusSnapshot.class);
    when(serverStatusService.sampleAndStore()).thenReturn(snapshot);
    ServerStatusSampler sampler = new ServerStatusSampler(serverStatusService, notificationCoordinator, true);

    sampler.sample();

    verify(notificationCoordinator).afterSample(snapshot);
  }

  @Test
  void disabledSamplingDoesNotEvaluateNotifications() {
    ServerStatusService serverStatusService = mock(ServerStatusService.class);
    AlertNotificationCoordinator notificationCoordinator = mock(AlertNotificationCoordinator.class);
    ServerStatusSampler sampler = new ServerStatusSampler(serverStatusService, notificationCoordinator, false);

    sampler.sample();

    verify(serverStatusService, never()).sampleAndStore();
    verify(notificationCoordinator, never()).afterSample(org.mockito.ArgumentMatchers.any());
  }
}
