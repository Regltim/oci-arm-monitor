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
import org.ociarmmonitor.oci.SyncRunRepository;
import org.ociarmmonitor.serverstatus.AlertRuleRepository;
import org.ociarmmonitor.serverstatus.ServerAlertService;
import org.ociarmmonitor.serverstatus.ServerStatusRepository;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WechatDailySummarySchedulerTest {

  private static final String VALID_KEY = Base64.getEncoder().encodeToString(
    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
  );

  private JdbcTemplate jdbcTemplate;
  private ServerStatusRepository serverStatusRepository;
  private WechatDailySummaryStateRepository stateRepository;
  private WechatDeliveryLogRepository deliveryLogRepository;
  private CapturingSender sender;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update("UPDATE alert_rule SET enabled = CASE WHEN id = 'cpu-high' THEN 1 ELSE 0 END");
    serverStatusRepository = new ServerStatusRepository(jdbcTemplate, 72);
    stateRepository = new WechatDailySummaryStateRepository(jdbcTemplate);
    deliveryLogRepository = new WechatDeliveryLogRepository(jdbcTemplate);
    sender = new CapturingSender();
  }

  @Test
  void sendsOnceWhenConfiguredLocalTimeIsDueAcrossRepeatedTicksAndRestart() {
    serverStatusRepository.save(snapshot());
    Clock dueClock = fixedClock("2026-07-27T01:01:00Z");
    WechatDailySummaryScheduler scheduler = scheduler(true, "09:00", "Asia/Shanghai", dueClock);

    scheduler.checkDueSummary();
    scheduler.checkDueSummary();
    scheduler(true, "09:00", "Asia/Shanghai", dueClock).checkDueSummary();

    assertThat(sender.messages).hasSize(2);
    assertThat(sender.messages.get(0).first()).isEqualTo("OCI ARM Monitor 每日状态摘要");
    assertThat(stateRepository.lastAttemptedDate()).contains("2026-07-27");
    assertThat(deliveryLogRepository.listRecent(20)).hasSize(1);
  }

  @Test
  void sendsAgainOnTheNextLocalDate() {
    serverStatusRepository.save(snapshot());
    scheduler(true, "09:00", "Asia/Shanghai", fixedClock("2026-07-27T01:01:00Z")).checkDueSummary();

    scheduler(true, "09:00", "Asia/Shanghai", fixedClock("2026-07-28T01:01:00Z")).checkDueSummary();

    assertThat(sender.messages).hasSize(4);
    assertThat(stateRepository.lastAttemptedDate()).contains("2026-07-28");
  }

  @Test
  void doesNotSendBeforeLocalTimeOrWhenDailySummaryIsDisabled() {
    serverStatusRepository.save(snapshot());

    scheduler(true, "09:00", "Asia/Shanghai", fixedClock("2026-07-27T00:59:00Z")).checkDueSummary();
    scheduler(false, "09:00", "Asia/Shanghai", fixedClock("2026-07-27T01:01:00Z")).checkDueSummary();
    scheduler(true, "09:00", "UTC", fixedClock("2026-07-27T01:01:00Z")).checkDueSummary();

    assertThat(sender.messages).isEmpty();
    assertThat(stateRepository.lastAttemptedDate()).isEmpty();
  }

  @Test
  void recordsMissingSnapshotOnceWithoutRetryStorm() {
    WechatDailySummaryScheduler scheduler = scheduler(
      true,
      "09:00",
      "Asia/Shanghai",
      fixedClock("2026-07-27T01:01:00Z")
    );

    scheduler.checkDueSummary();
    scheduler.checkDueSummary();

    assertThat(sender.messages).isEmpty();
    assertThat(stateRepository.lastAttemptedDate()).contains("2026-07-27");
    WechatDeliveryResult delivery = deliveryLogRepository.listRecent(20).get(0);
    assertThat(delivery.notificationType()).isEqualTo("DAILY_SUMMARY");
    assertThat(delivery.failureCount()).isEqualTo(2);
    assertThat(delivery.message()).isEqualTo("暂无服务器状态采样数据");
  }

  private WechatDailySummaryScheduler scheduler(
    boolean dailySummaryEnabled,
    String dailySummaryTime,
    String zoneId,
    Clock clock
  ) {
    WechatNotificationProperties properties = new WechatNotificationProperties(
      true,
      "wx_example_app_id",
      "wx_example_secret",
      "template_example_01",
      "openid_example_1,openid_example_2",
      true,
      dailySummaryEnabled,
      dailySummaryTime,
      zoneId,
      "https://monitor.example.com",
      "https://api.weixin.qq.com"
    );
    WechatNotificationSettingsRepository settingsRepository = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      properties,
      new WechatSecretCipher(VALID_KEY)
    );
    WechatNotificationService notificationService = new WechatNotificationService(
      settingsRepository,
      sender,
      clock
    );
    return new WechatDailySummaryScheduler(
      settingsRepository,
      stateRepository,
      serverStatusRepository,
      new ServerAlertService(new AlertRuleRepository(jdbcTemplate)),
      new SyncRunRepository(jdbcTemplate),
      notificationService,
      deliveryLogRepository,
      clock
    );
  }

  private Clock fixedClock(String instant) {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
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

    @Override
    public void sendTemplate(
      WechatNotificationSettings settings,
      String openId,
      WechatTemplateMessage message
    ) {
      messages.add(message);
    }
  }
}
