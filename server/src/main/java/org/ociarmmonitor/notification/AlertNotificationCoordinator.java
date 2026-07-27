package org.ociarmmonitor.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ociarmmonitor.oci.SyncRunRepository;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerAlertService;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AlertNotificationCoordinator {

  private final WechatNotificationSettingsRepository settingsRepository;
  private final ServerAlertService serverAlertService;
  private final SyncRunRepository syncRunRepository;
  private final AlertNotificationStateRepository stateRepository;
  private final WechatDeliveryLogRepository deliveryLogRepository;
  private final WechatNotificationService notificationService;
  private final Clock clock;

  @Autowired
  public AlertNotificationCoordinator(
    WechatNotificationSettingsRepository settingsRepository,
    ServerAlertService serverAlertService,
    SyncRunRepository syncRunRepository,
    AlertNotificationStateRepository stateRepository,
    WechatDeliveryLogRepository deliveryLogRepository,
    WechatNotificationService notificationService
  ) {
    this(
      settingsRepository,
      serverAlertService,
      syncRunRepository,
      stateRepository,
      deliveryLogRepository,
      notificationService,
      Clock.systemUTC()
    );
  }

  AlertNotificationCoordinator(
    WechatNotificationSettingsRepository settingsRepository,
    ServerAlertService serverAlertService,
    SyncRunRepository syncRunRepository,
    AlertNotificationStateRepository stateRepository,
    WechatDeliveryLogRepository deliveryLogRepository,
    WechatNotificationService notificationService,
    Clock clock
  ) {
    this.settingsRepository = settingsRepository;
    this.serverAlertService = serverAlertService;
    this.syncRunRepository = syncRunRepository;
    this.stateRepository = stateRepository;
    this.deliveryLogRepository = deliveryLogRepository;
    this.notificationService = notificationService;
    this.clock = clock;
  }

  public void afterSample(ServerStatusSnapshot snapshot) {
    WechatNotificationSettings settings = settingsRepository.resolve();
    boolean shouldSend = settings.enabled()
      && settings.configured()
      && settings.immediatePushEnabled();
    List<ServerAlert> alerts = serverAlertService.evaluate(snapshot, syncRunRepository.latest());
    Map<String, ServerAlert> currentAlerts = new LinkedHashMap<>();
    for (ServerAlert alert : alerts) {
      currentAlerts.put(alert.metricName(), alert);
      AlertNotificationState previousState = stateRepository.find(alert.metricName()).orElse(null);
      if (previousState == null || !previousState.active()) {
        String changedAt = Instant.now(clock).toString();
        stateRepository.activate(alert, changedAt);
        if (shouldSend) {
          record(alert.metricName(), settings.openIds().size(), () -> notificationService.sendAlert(alert));
        }
      }
    }

    for (AlertNotificationState previousState : stateRepository.findActive()) {
      if (currentAlerts.containsKey(previousState.metricName())) {
        continue;
      }
      String changedAt = Instant.now(clock).toString();
      stateRepository.recover(previousState.metricName(), changedAt);
      if (shouldSend) {
        record(
          previousState.metricName(),
          settings.openIds().size(),
          () -> notificationService.sendRecovery(previousState.toAlert())
        );
      }
    }
  }

  private void record(String metricName, int recipientCount, DeliveryAction action) {
    WechatDeliveryResult result;
    try {
      result = action.send();
    } catch (RuntimeException exception) {
      result = new WechatDeliveryResult(
        "ALERT_TRANSITION",
        metricName,
        0,
        recipientCount,
        "发送失败，请检查公众号配置和服务日志",
        Instant.now(clock).toString()
      );
    }
    deliveryLogRepository.save(result);
    stateRepository.markNotified(metricName, result.createdAt());
  }

  @FunctionalInterface
  private interface DeliveryAction {

    WechatDeliveryResult send();
  }
}
