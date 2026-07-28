package org.ociarmmonitor.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ociarmmonitor.cost.CostSummary;
import org.ociarmmonitor.oci.SyncResult;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.ociarmmonitor.traffic.TrafficSummary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WechatNotificationServiceTest {

  private static final String VALID_KEY = Base64.getEncoder().encodeToString(
    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
  );
  private static final Clock FIXED_CLOCK = Clock.fixed(
    Instant.parse("2026-07-27T01:00:00Z"),
    ZoneOffset.UTC
  );

  private JdbcTemplate jdbcTemplate;
  private CapturingSender sender;
  private WechatNotificationService service;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    jdbcTemplate = new JdbcTemplate(dataSource);
    sender = new CapturingSender();
    service = createService(properties(true, "template_example_cost"));
  }

  @Test
  void sendsSeparateStatusAndCostTemplateTests() {
    WechatTestDeliveryResult result = service.sendTest();

    assertThat(result.status().notificationType()).isEqualTo("TEST_STATUS");
    assertThat(result.status().successCount()).isEqualTo(2);
    assertThat(result.costTraffic().notificationType()).isEqualTo("TEST_COST_TRAFFIC");
    assertThat(result.costTraffic().successCount()).isEqualTo(2);
    assertThat(result.successCount()).isEqualTo(4);
    assertThat(result.failureCount()).isZero();
    assertThat(result.message()).isEqualTo("测试发送完成：成功 4，失败 0");

    assertThat(sender.deliveries).extracting(SentTemplate::templateType)
      .containsExactly(
        WechatTemplateType.STATUS,
        WechatTemplateType.STATUS,
        WechatTemplateType.COST_TRAFFIC,
        WechatTemplateType.COST_TRAFFIC
      );
    WechatTemplateMessage statusMessage = sender.deliveries.get(0).message();
    assertThat(statusMessage.first()).isEqualTo("OCI ARM Monitor 运行模板测试");
    assertThat(statusMessage.metric()).isEqualTo("运行状态模板");
    assertThat(statusMessage.time()).isEqualTo("2026-07-27 09:00:00");
    assertThat(statusMessage.remark()).isEqualTo("本消息仅用于验证公众号运行状态模板");
    WechatTemplateMessage costMessage = sender.deliveries.get(2).message();
    assertThat(costMessage.first()).isEqualTo("OCI ARM Monitor 费用与流量模板测试");
    assertThat(costMessage.metric()).isEqualTo("费用与流量模板");
    assertThat(costMessage.remark()).isEqualTo("本消息仅用于验证公众号费用与流量模板");
  }

  @Test
  void reportsMissingCostTemplateWithoutDiscardingSuccessfulStatusTest() {
    service = createService(properties(true, ""));

    WechatTestDeliveryResult result = service.sendTest();

    assertThat(result.status().successCount()).isEqualTo(2);
    assertThat(result.costTraffic().successCount()).isZero();
    assertThat(result.costTraffic().failureCount()).isEqualTo(2);
    assertThat(result.costTraffic().message()).isEqualTo("费用与流量模板未配置");
    assertThat(result.successCount()).isEqualTo(2);
    assertThat(result.failureCount()).isEqualTo(2);
    assertThat(sender.deliveries).hasSize(2);
    assertThat(sender.deliveries).extracting(SentTemplate::templateType).containsOnly(WechatTemplateType.STATUS);
  }

  @Test
  void rejectsTestBeforeSendingWhenBaseConfigurationIsUnavailable() {
    service = createService(properties(false, "template_example_cost"));

    assertThatThrownBy(service::sendTest)
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("微信公众号通知尚未启用");
    assertThat(sender.deliveries).isEmpty();
  }

  @Test
  void sendsAlertsRecoveriesAndDailyReportsThroughTheExpectedTemplates() {
    ServerAlert alert = alert();

    service.sendAlert(alert);
    service.sendRecovery(alert);
    service.sendDailyStatus(dailyReportData());
    service.sendDailyCostTraffic(dailyReportData());

    assertThat(sender.deliveries).extracting(SentTemplate::templateType)
      .containsExactly(
        WechatTemplateType.STATUS,
        WechatTemplateType.STATUS,
        WechatTemplateType.STATUS,
        WechatTemplateType.STATUS,
        WechatTemplateType.STATUS,
        WechatTemplateType.STATUS,
        WechatTemplateType.COST_TRAFFIC,
        WechatTemplateType.COST_TRAFFIC
      );
    assertThat(sender.deliveries.get(0).message().first()).isEqualTo("OCI ARM Monitor 告警通知");
    assertThat(sender.deliveries.get(2).message().first()).isEqualTo("OCI ARM Monitor 恢复通知");
    assertThat(sender.deliveries.get(4).message().first()).isEqualTo("OCI ARM Monitor 每日运行状态");
    assertThat(sender.deliveries.get(6).message().first()).isEqualTo("OCI ARM Monitor 费用与流量日报");
    assertThat(sender.deliveries).allSatisfy(delivery -> {
      assertThat(delivery.message().remark()).doesNotContain("点击");
      assertThat(delivery.message().remark()).doesNotContain("监控面板");
    });
  }

  @Test
  void continuesOtherRecipientsAndAggregatesBothTemplatesWhenOneRecipientFails() {
    sender.failingOpenId = "openid_example_2";

    WechatTestDeliveryResult result = service.sendTest();

    assertThat(result.status().successCount()).isEqualTo(1);
    assertThat(result.status().failureCount()).isEqualTo(1);
    assertThat(result.costTraffic().successCount()).isEqualTo(1);
    assertThat(result.costTraffic().failureCount()).isEqualTo(1);
    assertThat(result.successCount()).isEqualTo(2);
    assertThat(result.failureCount()).isEqualTo(2);
    assertThat(result.message()).isEqualTo("测试发送完成：成功 2，失败 2");
    assertThat(result.message()).doesNotContain("openid_example_2");
  }

  private WechatNotificationService createService(WechatNotificationProperties properties) {
    WechatNotificationSettingsRepository settingsRepository = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      properties,
      new WechatSecretCipher(VALID_KEY)
    );
    return new WechatNotificationService(settingsRepository, sender, FIXED_CLOCK);
  }

  private WechatNotificationProperties properties(boolean enabled, String costTemplateId) {
    return new WechatNotificationProperties(
      enabled,
      "wx_example_app_id",
      "wx_example_secret",
      "template_example_status",
      costTemplateId,
      "openid_example_1,openid_example_2",
      true,
      false,
      "09:00",
      "Asia/Shanghai",
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

  private DailyReportData dailyReportData() {
    SyncResult successfulSync = new SyncResult(
      "SUCCESS",
      "同步完成",
      "2026-07-27T00:00:00Z",
      "2026-07-27T00:02:16Z",
      1,
      2,
      1,
      1
    );
    return new DailyReportData(
      new DailyReportContext(
        FIXED_CLOCK.instant(),
        ZoneId.of("Asia/Shanghai"),
        LocalDate.of(2026, 7, 27),
        YearMonth.of(2026, 7)
      ),
      List.of(),
      snapshot(),
      List.of(alert()),
      successfulSync,
      successfulSync,
      new CostSummary(12.34, 8, 20.34, 28.62, "CNY", List.of(), List.of()),
      new TrafficSummary(123.45, 67.89, 10_000, 0.68, List.of())
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

  private record SentTemplate(
    String openId,
    WechatTemplateType templateType,
    WechatTemplateMessage message
  ) {
  }

  private static class CapturingSender implements WechatTemplateSender {

    private final List<SentTemplate> deliveries = new ArrayList<>();
    private String failingOpenId = "";

    @Override
    public void sendTemplate(
      WechatNotificationSettings settings,
      String openId,
      WechatTemplateType templateType,
      WechatTemplateMessage message
    ) {
      deliveries.add(new SentTemplate(openId, templateType, message));
      if (openId.equals(failingOpenId)) {
        throw new WechatApiException("模拟发送失败");
      }
    }
  }
}
