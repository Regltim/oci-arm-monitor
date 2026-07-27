package org.ociarmmonitor.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import org.ociarmmonitor.oci.SyncRunRepository;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerAlertService;
import org.ociarmmonitor.serverstatus.ServerStatusRepository;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WechatDailySummaryScheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(WechatDailySummaryScheduler.class);

  private final WechatNotificationSettingsRepository settingsRepository;
  private final WechatDailySummaryStateRepository stateRepository;
  private final ServerStatusRepository serverStatusRepository;
  private final ServerAlertService serverAlertService;
  private final SyncRunRepository syncRunRepository;
  private final WechatNotificationService notificationService;
  private final WechatDeliveryLogRepository deliveryLogRepository;
  private final Clock clock;

  @Autowired
  public WechatDailySummaryScheduler(
    WechatNotificationSettingsRepository settingsRepository,
    WechatDailySummaryStateRepository stateRepository,
    ServerStatusRepository serverStatusRepository,
    ServerAlertService serverAlertService,
    SyncRunRepository syncRunRepository,
    WechatNotificationService notificationService,
    WechatDeliveryLogRepository deliveryLogRepository
  ) {
    this(
      settingsRepository,
      stateRepository,
      serverStatusRepository,
      serverAlertService,
      syncRunRepository,
      notificationService,
      deliveryLogRepository,
      Clock.systemUTC()
    );
  }

  WechatDailySummaryScheduler(
    WechatNotificationSettingsRepository settingsRepository,
    WechatDailySummaryStateRepository stateRepository,
    ServerStatusRepository serverStatusRepository,
    ServerAlertService serverAlertService,
    SyncRunRepository syncRunRepository,
    WechatNotificationService notificationService,
    WechatDeliveryLogRepository deliveryLogRepository,
    Clock clock
  ) {
    this.settingsRepository = settingsRepository;
    this.stateRepository = stateRepository;
    this.serverStatusRepository = serverStatusRepository;
    this.serverAlertService = serverAlertService;
    this.syncRunRepository = syncRunRepository;
    this.notificationService = notificationService;
    this.deliveryLogRepository = deliveryLogRepository;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 30000)
  public void checkDueSummary() {
    try {
      checkDueSummaryInternal();
    } catch (RuntimeException exception) {
      LOGGER.warn("Wechat daily summary check failed: {}", exception.getMessage());
    }
  }

  private void checkDueSummaryInternal() {
    WechatNotificationSettings settings = settingsRepository.resolve();
    if (!settings.enabled() || !settings.configured() || !settings.dailySummaryEnabled()) {
      return;
    }
    ZonedDateTime localNow = ZonedDateTime.ofInstant(clock.instant(), settings.zoneId());
    if (localNow.toLocalTime().isBefore(settings.dailySummaryTime())) {
      return;
    }
    String localDate = localNow.toLocalDate().toString();
    if (stateRepository.lastAttemptedDate().filter(localDate::equals).isPresent()) {
      return;
    }

    String attemptedAt = Instant.now(clock).toString();
    stateRepository.markAttempted(localNow.toLocalDate(), attemptedAt);
    ServerStatusSnapshot snapshot = serverStatusRepository.latest().orElse(null);
    if (snapshot == null) {
      deliveryLogRepository.save(new WechatDeliveryResult(
        "DAILY_SUMMARY",
        "",
        0,
        settings.openIds().size(),
        "暂无服务器状态采样数据",
        attemptedAt
      ));
      return;
    }

    try {
      List<ServerAlert> alerts = serverAlertService.evaluate(snapshot, syncRunRepository.latest());
      double syncAgeHours = serverAlertService.syncAgeHours(syncRunRepository.latest());
      deliveryLogRepository.save(notificationService.sendDailySummary(snapshot, alerts, syncAgeHours));
    } catch (RuntimeException exception) {
      deliveryLogRepository.save(new WechatDeliveryResult(
        "DAILY_SUMMARY",
        "",
        0,
        settings.openIds().size(),
        "发送失败，请检查公众号配置和服务日志",
        attemptedAt
      ));
    }
  }
}
