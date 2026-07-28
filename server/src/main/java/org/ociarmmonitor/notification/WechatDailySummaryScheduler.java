package org.ociarmmonitor.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WechatDailySummaryScheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(WechatDailySummaryScheduler.class);
  private static final String STATUS_REPORT = "STATUS";
  private static final String COST_TRAFFIC_REPORT = "COST_TRAFFIC";

  private final WechatNotificationSettingsRepository settingsRepository;
  private final WechatDailySummaryStateRepository stateRepository;
  private final DailyReportDataProvider dataProvider;
  private final WechatNotificationService notificationService;
  private final WechatDeliveryLogRepository deliveryLogRepository;
  private final Clock clock;

  @Autowired
  public WechatDailySummaryScheduler(
    WechatNotificationSettingsRepository settingsRepository,
    WechatDailySummaryStateRepository stateRepository,
    DailyReportDataProvider dataProvider,
    WechatNotificationService notificationService,
    WechatDeliveryLogRepository deliveryLogRepository
  ) {
    this(
      settingsRepository,
      stateRepository,
      dataProvider,
      notificationService,
      deliveryLogRepository,
      Clock.systemUTC()
    );
  }

  WechatDailySummaryScheduler(
    WechatNotificationSettingsRepository settingsRepository,
    WechatDailySummaryStateRepository stateRepository,
    DailyReportDataProvider dataProvider,
    WechatNotificationService notificationService,
    WechatDeliveryLogRepository deliveryLogRepository,
    Clock clock
  ) {
    this.settingsRepository = settingsRepository;
    this.stateRepository = stateRepository;
    this.dataProvider = dataProvider;
    this.notificationService = notificationService;
    this.deliveryLogRepository = deliveryLogRepository;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 30000)
  public void checkDueSummary() {
    try {
      checkDueSummaryInternal();
    } catch (RuntimeException exception) {
      LOGGER.warn("微信公众号日报检查失败，请查看服务诊断日志");
    }
  }

  private void checkDueSummaryInternal() {
    WechatNotificationSettings settings = settingsRepository.resolve();
    if (!settings.enabled() || !settings.dailySummaryConfigured() || !settings.dailySummaryEnabled()) {
      return;
    }
    DailyReportContext context = DailyReportContext.from(clock, settings.zoneId());
    ZonedDateTime localNow = context.reportAt().atZone(settings.zoneId());
    if (localNow.toLocalTime().isBefore(settings.dailySummaryTime())) {
      return;
    }

    DailyReportData data = dataProvider.load(context);
    attemptReport(
      STATUS_REPORT,
      "DAILY_STATUS",
      context,
      settings,
      () -> notificationService.sendDailyStatus(data)
    );
    attemptReport(
      COST_TRAFFIC_REPORT,
      "DAILY_COST_TRAFFIC",
      context,
      settings,
      () -> notificationService.sendDailyCostTraffic(data)
    );
  }

  private void attemptReport(
    String reportType,
    String notificationType,
    DailyReportContext context,
    WechatNotificationSettings settings,
    Supplier<WechatDeliveryResult> delivery
  ) {
    String attemptedAt = Instant.now(clock).toString();
    try {
      if (!stateRepository.tryClaim(reportType, context.localDate(), attemptedAt)) {
        return;
      }
    } catch (RuntimeException exception) {
      LOGGER.warn("微信公众号日报发送资格抢占失败：{}", reportType);
      return;
    }

    try {
      deliveryLogRepository.save(delivery.get());
    } catch (RuntimeException exception) {
      saveFailure(notificationType, settings.openIds().size(), attemptedAt);
    }
  }

  private void saveFailure(String notificationType, int recipientCount, String attemptedAt) {
    try {
      deliveryLogRepository.save(new WechatDeliveryResult(
        notificationType,
        "",
        0,
        recipientCount,
        "发送失败，请检查公众号配置和服务日志",
        attemptedAt
      ));
    } catch (RuntimeException exception) {
      LOGGER.warn("微信公众号日报投递结果保存失败：{}", notificationType);
    }
  }
}
