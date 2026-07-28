package org.ociarmmonitor.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WechatNotificationService {

  private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int MAX_FAILURE_REASON_LENGTH = 180;

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
        "模板：运行状态",
        "结果：测试成功",
        "时间：" + messageTime(settings)
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
          "模板：费用与流量",
          "结果：测试成功",
          "时间：" + messageTime(settings)
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
        "级别：" + ("danger".equals(alert.severity()) ? "严重" : "警告") + "｜指标：" + alert.title(),
        "详情：" + alert.description(),
        "时间：" + messageTime(settings)
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
        "状态：已恢复｜指标：" + previousAlert.title(),
        "说明：" + previousAlert.title() + "已恢复至阈值范围内。",
        "时间：" + messageTime(settings)
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
      dailyReportMessageAssembler.statusMessages(data)
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
    return deliver(notificationType, metricName, settings, templateType, List.of(message));
  }

  private WechatDeliveryResult deliver(
    String notificationType,
    String metricName,
    WechatNotificationSettings settings,
    WechatTemplateType templateType,
    List<WechatTemplateMessage> messages
  ) {
    int successCount = 0;
    int failureCount = 0;
    String failureReason = "";
    for (WechatTemplateMessage message : messages) {
      for (String openId : settings.openIds()) {
        try {
          templateSender.sendTemplate(settings, openId, templateType, message);
          successCount++;
        } catch (RuntimeException exception) {
          failureCount++;
          if (failureReason.isBlank()) {
            failureReason = sanitizedFailureReason(exception, settings);
          }
        }
      }
    }
    String resultMessage = "发送完成：成功 " + successCount + "，失败 " + failureCount;
    if (!failureReason.isBlank()) {
      resultMessage += "；失败原因：" + failureReason;
    }
    return new WechatDeliveryResult(
      notificationType,
      metricName,
      successCount,
      failureCount,
      resultMessage,
      Instant.now(clock).toString()
    );
  }

  private String sanitizedFailureReason(
    RuntimeException exception,
    WechatNotificationSettings settings
  ) {
    if (!(exception instanceof WechatApiException) || exception.getMessage() == null) {
      return "微信公众号接口调用异常";
    }
    String reason = exception.getMessage().replaceAll("[\\p{Cntrl}\\r\\n\\t]+", " ").trim();
    reason = redact(reason, settings.appId());
    reason = redact(reason, settings.appSecret());
    reason = redact(reason, settings.templateId());
    reason = redact(reason, settings.costTemplateId());
    for (String openId : settings.openIds()) {
      reason = redact(reason, openId);
    }
    if (reason.length() > MAX_FAILURE_REASON_LENGTH) {
      reason = reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
    return reason;
  }

  private String redact(String value, String secret) {
    return secret == null || secret.isBlank() ? value : value.replace(secret, "[REDACTED]");
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
