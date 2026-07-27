package org.ociarmmonitor.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WechatNotificationServiceTest {

  private static final String VALID_KEY = Base64.getEncoder().encodeToString(
    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
  );

  private CapturingSender sender;
  private WechatNotificationService service;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    sender = new CapturingSender();
    WechatNotificationSettingsRepository settingsRepository = new WechatNotificationSettingsRepository(
      new JdbcTemplate(dataSource),
      properties(),
      new WechatSecretCipher(VALID_KEY)
    );
    service = new WechatNotificationService(
      settingsRepository,
      sender,
      Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneOffset.UTC)
    );
  }

  @Test
  void formatsManualTestMessage() {
    WechatDeliveryResult result = service.sendTest();

    WechatTemplateMessage message = sender.messages.get(0);
    assertThat(result.successCount()).isEqualTo(2);
    assertThat(result.failureCount()).isZero();
    assertThat(message.first()).isEqualTo("OCI ARM Monitor 测试通知");
    assertThat(message.level()).isEqualTo("信息");
    assertThat(message.metric()).isEqualTo("通知通道");
    assertThat(message.status()).isEqualTo("测试成功");
    assertThat(message.content()).isEqualTo("公众号模板消息配置有效。");
    assertThat(message.time()).isEqualTo("2026-07-27 09:00:00");
    assertThat(message.remark()).isEqualTo("点击查看监控面板");
  }

  @Test
  void formatsAlertAndRecoveryMessages() {
    ServerAlert alert = alert();

    service.sendAlert(alert);
    service.sendRecovery(alert);

    WechatTemplateMessage alertMessage = sender.messages.get(0);
    WechatTemplateMessage recoveryMessage = sender.messages.get(2);
    assertThat(alertMessage.first()).isEqualTo("OCI ARM Monitor 告警通知");
    assertThat(alertMessage.level()).isEqualTo("警告");
    assertThat(alertMessage.metric()).isEqualTo("CPU 使用率");
    assertThat(alertMessage.status()).isEqualTo("告警触发");
    assertThat(alertMessage.content()).isEqualTo("CPU 使用率当前 95.00%，阈值 90.00%。");
    assertThat(recoveryMessage.first()).isEqualTo("OCI ARM Monitor 恢复通知");
    assertThat(recoveryMessage.level()).isEqualTo("恢复");
    assertThat(recoveryMessage.metric()).isEqualTo("CPU 使用率");
    assertThat(recoveryMessage.status()).isEqualTo("已恢复");
    assertThat(recoveryMessage.content()).isEqualTo("CPU 使用率已恢复至阈值范围内。");
  }

  @Test
  void formatsDailyServerStatusSummary() {
    service.sendDailySummary(snapshot(), List.of(alert()), 28.5);

    WechatTemplateMessage message = sender.messages.get(0);
    assertThat(message.first()).isEqualTo("OCI ARM Monitor 每日状态摘要");
    assertThat(message.level()).isEqualTo("警告");
    assertThat(message.metric()).isEqualTo("服务器状态");
    assertThat(message.status()).isEqualTo("存在 1 项告警");
    assertThat(message.content()).isEqualTo("CPU 45.20%，内存 61.30%，磁盘 72.40%，OCI 最近同步 28.50 小时前。");
  }

  @Test
  void continuesOtherRecipientsAndReturnsSanitizedAggregateWhenOneSendFails() {
    sender.failingOpenId = "openid_example_2";

    WechatDeliveryResult result = service.sendTest();

    assertThat(result.successCount()).isEqualTo(1);
    assertThat(result.failureCount()).isEqualTo(1);
    assertThat(result.message()).isEqualTo("发送完成：成功 1，失败 1");
    assertThat(result.message()).doesNotContain("openid_example_2");
  }

  private WechatNotificationProperties properties() {
    return new WechatNotificationProperties(
      true,
      "wx_example_app_id",
      "wx_example_secret",
      "template_example_01",
      "openid_example_1,openid_example_2",
      true,
      false,
      "09:00",
      "Asia/Shanghai",
      "https://monitor.example.com",
      "https://api.weixin.qq.com"
    );
  }

  private ServerAlert alert() {
    return new ServerAlert(
      "cpu_usage_percent",
      "warning",
      "CPU 使用率",
      "CPU 使用率当前 95.00%，阈值 90.00%。",
      95,
      90,
      "%"
    );
  }

  private ServerStatusSnapshot snapshot() {
    return new ServerStatusSnapshot(
      "2026-07-27T01:00:00Z",
      45.2,
      0.5,
      0.4,
      0.3,
      16_000,
      6_192,
      61.3,
      4_000,
      4_000,
      0,
      100_000,
      27_600,
      72.4,
      1_000,
      2_000,
      10,
      20,
      86_400,
      3_600,
      100,
      200,
      20,
      10_000
    );
  }

  private static class CapturingSender implements WechatTemplateSender {

    private final List<WechatTemplateMessage> messages = new ArrayList<>();
    private String failingOpenId = "";

    @Override
    public void sendTemplate(
      WechatNotificationSettings settings,
      String openId,
      WechatTemplateMessage message
    ) {
      messages.add(message);
      if (openId.equals(failingOpenId)) {
        throw new WechatApiException("模拟发送失败");
      }
    }
  }
}
