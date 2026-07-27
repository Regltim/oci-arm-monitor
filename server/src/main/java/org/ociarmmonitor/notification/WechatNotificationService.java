package org.ociarmmonitor.notification;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WechatNotificationService {

  private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final WechatNotificationSettingsRepository settingsRepository;
  private final WechatTemplateSender templateSender;
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
    this.clock = clock;
  }

  public WechatDeliveryResult sendTest() {
    WechatNotificationSettings settings = requireAvailableSettings();
    return deliver(
      "TEST",
      "",
      settings,
      new WechatTemplateMessage(
        "OCI ARM Monitor 测试通知",
        "信息",
        "通知通道",
        "测试成功",
        "公众号模板消息配置有效。",
        messageTime(settings),
        "点击查看监控面板"
      )
    );
  }

  public WechatDeliveryResult sendAlert(ServerAlert alert) {
    WechatNotificationSettings settings = requireAvailableSettings();
    return deliver(
      "ALERT",
      alert.metricName(),
      settings,
      new WechatTemplateMessage(
        "OCI ARM Monitor 告警通知",
        "danger".equals(alert.severity()) ? "严重" : "警告",
        alert.title(),
        "告警触发",
        alert.description(),
        messageTime(settings),
        "点击查看监控面板"
      )
    );
  }

  public WechatDeliveryResult sendRecovery(ServerAlert previousAlert) {
    WechatNotificationSettings settings = requireAvailableSettings();
    return deliver(
      "RECOVERY",
      previousAlert.metricName(),
      settings,
      new WechatTemplateMessage(
        "OCI ARM Monitor 恢复通知",
        "恢复",
        previousAlert.title(),
        "已恢复",
        previousAlert.title() + "已恢复至阈值范围内。",
        messageTime(settings),
        "点击查看监控面板"
      )
    );
  }

  public WechatDeliveryResult sendDailySummary(
    ServerStatusSnapshot snapshot,
    List<ServerAlert> alerts,
    double syncAgeHours
  ) {
    WechatNotificationSettings settings = requireAvailableSettings();
    int alertCount = alerts.size();
    String syncAge = Double.isFinite(syncAgeHours) && syncAgeHours < 999
      ? format("%.2f 小时前", syncAgeHours)
      : "暂无成功记录";
    String content = format(
      "CPU %.2f%%，内存 %.2f%%，磁盘 %.2f%%，OCI 最近同步 %s。",
      snapshot.cpuUsagePercent(),
      snapshot.memoryUsagePercent(),
      snapshot.diskUsagePercent(),
      syncAge
    );
    return deliver(
      "DAILY_SUMMARY",
      "",
      settings,
      new WechatTemplateMessage(
        "OCI ARM Monitor 每日状态摘要",
        alertCount == 0 ? "信息" : "警告",
        "服务器状态",
        alertCount == 0 ? "运行正常" : "存在 " + alertCount + " 项告警",
        content,
        messageTime(settings),
        "点击查看监控面板"
      )
    );
  }

  private WechatDeliveryResult deliver(
    String notificationType,
    String metricName,
    WechatNotificationSettings settings,
    WechatTemplateMessage message
  ) {
    int successCount = 0;
    int failureCount = 0;
    for (String openId : settings.openIds()) {
      try {
        templateSender.sendTemplate(settings, openId, message);
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
      java.time.Instant.now(clock).toString()
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

  private String format(String template, Object... values) {
    return String.format(Locale.ROOT, template, values);
  }
}
