package org.ociarmmonitor.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WechatNotificationService {

  private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final WechatNotificationSettingsRepository settingsRepository;
  private final WechatTemplateSender templateSender;
  private final DailyReportMessageAssembler dailyReportMessageAssembler;
  private final Clock clock;

  @Autowired
  public WechatNotificationService(
    WechatNotificationSettingsRepository settingsRepository,
    WechatTemplateSender templateSender
  ) {
    this(settingsRepository, templateSender, Clock.systemUTC());
  }

  WechatNotificationService(
    WechatNotificationSettingsRepository settingsRepository,
    WechatTemplateSender templateSender,
    Clock clock
  ) {
    this.settingsRepository = settingsRepository;
    this.templateSender = templateSender;
    this.dailyReportMessageAssembler = new DailyReportMessageAssembler();
    this.clock = clock;
  }

  public WechatTestDeliveryResult sendTest() {
    WechatNotificationSettings settings = requireAvailableSettings();
    WechatDeliveryResult status = deliver(
      "TEST_STATUS",
      "",
      settings,
      WechatTemplateType.STATUS,
      new WechatTemplateMessage(
        "OCI ARM Monitor 运行模板测试",
        "信息",
        "运行状态模板",
        "测试成功",
        "运行状态模板消息配置有效，可用于告警、恢复和每日运行状态。",
        messageTime(settings),
        "本消息仅用于验证公众号运行状态模板"
      )
    );
    WechatDeliveryResult costTraffic = settings.costTemplateId().isBlank()
      ? unavailableCostTemplateResult(settings)
      : deliver(
        "TEST_COST_TRAFFIC",
        "",
        settings,
        WechatTemplateType.COST_TRAFFIC,
        new WechatTemplateMessage(
          "OCI ARM Monitor 费用与流量模板测试",
          "信息",
          "费用与流量模板",
          "测试成功",
          "费用与流量模板消息配置有效，可用于每日费用与流量摘要。",
          messageTime(settings),
          "本消息仅用于验证公众号费用与流量模板"
        )
      );
    int successCount = status.successCount() + costTraffic.successCount();
    int failureCount = status.failureCount() + costTraffic.failureCount();
    return new WechatTestDeliveryResult(
      status,
      costTraffic,
      successCount,
      failureCount,
      "测试发送完成：成功 " + successCount + "，失败 " + failureCount
    );
  }

  public WechatDeliveryResult sendAlert(ServerAlert alert) {
    WechatNotificationSettings settings = requireAvailableSettings();
    return deliver(
      "ALERT",
      alert.metricName(),
      settings,
      WechatTemplateType.STATUS,
      new WechatTemplateMessage(
        "OCI ARM Monitor 告警通知",
        "danger".equals(alert.severity()) ? "严重" : "警告",
        alert.title(),
        "告警触发",
        alert.description(),
        messageTime(settings),
        "请及时检查对应指标和 OCI 同步状态"
      )
    );
  }

  public WechatDeliveryResult sendRecovery(ServerAlert previousAlert) {
    WechatNotificationSettings settings = requireAvailableSettings();
    return deliver(
      "RECOVERY",
      previousAlert.metricName(),
      settings,
      WechatTemplateType.STATUS,
      new WechatTemplateMessage(
        "OCI ARM Monitor 恢复通知",
        "恢复",
        previousAlert.title(),
        "已恢复",
        previousAlert.title() + "已恢复至阈值范围内。",
        messageTime(settings),
        "指标已恢复，后续仍将持续监控"
      )
    );
  }

  public WechatDeliveryResult sendDailyStatus(DailyReportData data) {
    WechatNotificationSettings settings = requireAvailableSettings();
    return deliver(
      "DAILY_STATUS",
      "",
      settings,
      WechatTemplateType.STATUS,
      dailyReportMessageAssembler.statusMessage(data)
    );
  }

  public WechatDeliveryResult sendDailyCostTraffic(DailyReportData data) {
    WechatNotificationSettings settings = requireAvailableSettings();
    if (settings.costTemplateId().isBlank()) {
      throw new IllegalArgumentException("费用与流量模板未配置");
    }
    return deliver(
      "DAILY_COST_TRAFFIC",
      "",
      settings,
      WechatTemplateType.COST_TRAFFIC,
      dailyReportMessageAssembler.costTrafficMessage(data)
    );
  }

  private WechatDeliveryResult unavailableCostTemplateResult(WechatNotificationSettings settings) {
    return new WechatDeliveryResult(
      "TEST_COST_TRAFFIC",
      "",
      0,
      settings.openIds().size(),
      "费用与流量模板未配置",
      Instant.now(clock).toString()
    );
  }

  private WechatDeliveryResult deliver(
    String notificationType,
    String metricName,
    WechatNotificationSettings settings,
    WechatTemplateType templateType,
    WechatTemplateMessage message
  ) {
    int successCount = 0;
    int failureCount = 0;
    for (String openId : settings.openIds()) {
      try {
        templateSender.sendTemplate(settings, openId, templateType, message);
        successCount++;
      } catch (RuntimeException exception) {
        failureCount++;
      }
    }
    return new WechatDeliveryResult(
      notificationType,
      metricName,
      successCount,
      failureCount,
      "发送完成：成功 " + successCount + "，失败 " + failureCount,
      Instant.now(clock).toString()
    );
  }

  private WechatNotificationSettings requireAvailableSettings() {
    WechatNotificationSettings settings = settingsRepository.resolve();
    if (!settings.enabled()) {
      throw new IllegalArgumentException("微信公众号通知尚未启用");
    }
    if (!settings.configured()) {
      throw new IllegalArgumentException("微信公众号通知配置不完整");
    }
    return settings;
  }

  private String messageTime(WechatNotificationSettings settings) {
    return ZonedDateTime.ofInstant(clock.instant(), settings.zoneId()).format(MESSAGE_TIME_FORMATTER);
  }
}
