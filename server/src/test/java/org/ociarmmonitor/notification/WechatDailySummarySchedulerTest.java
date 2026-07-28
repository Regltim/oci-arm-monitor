package org.ociarmmonitor.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ociarmmonitor.config.FreeQuotaRepository;
import org.ociarmmonitor.cost.CostRepository;
import org.ociarmmonitor.cost.CostService;
import org.ociarmmonitor.cost.ManualCostRepository;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.oci.SyncRunRepository;
import org.ociarmmonitor.serverstatus.AlertRuleRepository;
import org.ociarmmonitor.serverstatus.ServerAlertService;
import org.ociarmmonitor.serverstatus.ServerStatusRepository;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.ociarmmonitor.traffic.TrafficRepository;
import org.ociarmmonitor.traffic.TrafficService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
  void sendsStatusThenCostOnceAcrossRepeatedTicksAndRestart() {
    serverStatusRepository.save(snapshot());
    Clock dueClock = fixedClock("2026-07-27T01:01:00Z");
    WechatDailySummaryScheduler scheduler = scheduler(true, "09:00", "Asia/Shanghai", dueClock);

    scheduler.checkDueSummary();
    scheduler.checkDueSummary();
    scheduler(true, "09:00", "Asia/Shanghai", dueClock).checkDueSummary();

    assertThat(sender.deliveries).hasSize(4);
    assertThat(sender.deliveries).extracting(SentTemplate::templateType)
      .containsExactly(
        WechatTemplateType.STATUS,
        WechatTemplateType.STATUS,
        WechatTemplateType.COST_TRAFFIC,
        WechatTemplateType.COST_TRAFFIC
      );
    assertThat(sender.deliveries.get(0).message().first()).isEqualTo("OCI ARM Monitor 每日运行状态");
    assertThat(sender.deliveries.get(2).message().first()).isEqualTo("OCI ARM Monitor 费用与流量");
    assertThat(claimedTypes()).containsExactlyInAnyOrder("STATUS", "COST_TRAFFIC");
    assertThat(deliveryLogRepository.listRecent(20)).extracting(WechatDeliveryResult::notificationType)
      .containsExactly("DAILY_COST_TRAFFIC", "DAILY_STATUS");
  }

  @Test
  void sendsBothReportsAgainOnTheNextLocalDate() {
    serverStatusRepository.save(snapshot());
    scheduler(true, "09:00", "Asia/Shanghai", fixedClock("2026-07-27T01:01:00Z")).checkDueSummary();

    scheduler(true, "09:00", "Asia/Shanghai", fixedClock("2026-07-28T01:01:00Z")).checkDueSummary();

    assertThat(sender.deliveries).hasSize(8);
    assertThat(jdbcTemplate.queryForList(
      "SELECT DISTINCT last_attempted_date FROM wechat_daily_summary_state",
      String.class
    )).containsExactly("2026-07-28");
    assertThat(deliveryLogRepository.listRecent(20)).hasSize(4);
  }

  @Test
  void doesNotSendBeforeLocalTimeOrWhenDailySummaryIsDisabled() {
    serverStatusRepository.save(snapshot());

    scheduler(true, "09:00", "Asia/Shanghai", fixedClock("2026-07-27T00:59:00Z")).checkDueSummary();
    scheduler(false, "09:00", "Asia/Shanghai", fixedClock("2026-07-27T01:01:00Z")).checkDueSummary();
    scheduler(true, "09:00", "UTC", fixedClock("2026-07-27T01:01:00Z")).checkDueSummary();

    assertThat(sender.deliveries).isEmpty();
    assertThat(claimedTypes()).isEmpty();
  }

  @Test
  void sendsBothReportsWhenHostSampleIsMissing() {
    WechatDailySummaryScheduler scheduler = scheduler(
      true,
      "09:00",
      "Asia/Shanghai",
      fixedClock("2026-07-27T01:01:00Z")
    );

    scheduler.checkDueSummary();
    scheduler.checkDueSummary();

    assertThat(sender.deliveries).hasSize(4);
    assertThat(sender.deliveries.get(0).message().item2()).contains("暂无采样数据");
    assertThat(sender.deliveries.get(2).templateType()).isEqualTo(WechatTemplateType.COST_TRAFFIC);
    assertThat(deliveryLogRepository.listRecent(20)).extracting(WechatDeliveryResult::notificationType)
      .containsExactly("DAILY_COST_TRAFFIC", "DAILY_STATUS");
  }

  @Test
  void restartBetweenReportsOnlySendsTheUnclaimedCostReport() {
    Clock dueClock = fixedClock("2026-07-27T01:01:00Z");
    assertThat(stateRepository.tryClaim("STATUS", LocalDate.of(2026, 7, 27), "2026-07-27T01:00:30Z"))
      .isTrue();

    scheduler(true, "09:00", "Asia/Shanghai", dueClock).checkDueSummary();

    assertThat(sender.deliveries).hasSize(2);
    assertThat(sender.deliveries).extracting(SentTemplate::templateType)
      .containsOnly(WechatTemplateType.COST_TRAFFIC);
    assertThat(deliveryLogRepository.listRecent(20)).extracting(WechatDeliveryResult::notificationType)
      .containsExactly("DAILY_COST_TRAFFIC");
  }

  @Test
  void statusExceptionIsRecordedWithoutBlockingCostReport() {
    Clock dueClock = fixedClock("2026-07-27T01:01:00Z");
    WechatNotificationSettingsRepository settingsRepository = settingsRepository(true, "09:00", "Asia/Shanghai");
    WechatNotificationService notificationService = new WechatNotificationService(settingsRepository, sender, dueClock) {
      @Override
      public WechatDeliveryResult sendDailyStatus(DailyReportData data) {
        throw new WechatApiException("模拟运行日报异常");
      }
    };

    newScheduler(settingsRepository, notificationService, dueClock).checkDueSummary();

    assertThat(sender.deliveries).hasSize(2);
    assertThat(sender.deliveries).extracting(SentTemplate::templateType)
      .containsOnly(WechatTemplateType.COST_TRAFFIC);
    List<WechatDeliveryResult> deliveries = deliveryLogRepository.listRecent(20);
    assertThat(deliveries).extracting(WechatDeliveryResult::notificationType)
      .containsExactly("DAILY_COST_TRAFFIC", "DAILY_STATUS");
    assertThat(deliveries.get(1).failureCount()).isEqualTo(2);
    assertThat(deliveries.get(1).message()).isEqualTo("发送失败，请检查公众号配置和服务日志");
  }

  @Test
  void recordsEachReportWhenOneRecipientFails() {
    sender.failingOpenId = "openid_example_2";

    scheduler(true, "09:00", "Asia/Shanghai", fixedClock("2026-07-27T01:01:00Z")).checkDueSummary();

    assertThat(sender.deliveries).hasSize(4);
    assertThat(deliveryLogRepository.listRecent(20)).allSatisfy(delivery -> {
      assertThat(delivery.successCount()).isEqualTo(1);
      assertThat(delivery.failureCount()).isEqualTo(1);
      assertThat(delivery.message()).doesNotContain("openid_example_2");
    });
  }

  @Test
  void atomicClaimAllowsOnlyOneConcurrentOwner(@TempDir Path tempDir) throws Exception {
    String databaseUrl = "jdbc:sqlite:" + tempDir.resolve("daily-claim.db") + "?busy_timeout=5000";
    DriverManagerDataSource dataSource = new DriverManagerDataSource(databaseUrl);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    WechatDailySummaryStateRepository firstRepository = new WechatDailySummaryStateRepository(new JdbcTemplate(dataSource));
    WechatDailySummaryStateRepository secondRepository = new WechatDailySummaryStateRepository(new JdbcTemplate(dataSource));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<Boolean> first = executor.submit(() -> {
        start.await();
        return firstRepository.tryClaim("STATUS", LocalDate.of(2026, 7, 27), "2026-07-27T01:00:00Z");
      });
      Future<Boolean> second = executor.submit(() -> {
        start.await();
        return secondRepository.tryClaim("STATUS", LocalDate.of(2026, 7, 27), "2026-07-27T01:00:00Z");
      });
      start.countDown();

      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    } finally {
      executor.shutdownNow();
    }
  }

  private WechatDailySummaryScheduler scheduler(
    boolean dailySummaryEnabled,
    String dailySummaryTime,
    String zoneId,
    Clock clock
  ) {
    WechatNotificationSettingsRepository settingsRepository = settingsRepository(
      dailySummaryEnabled,
      dailySummaryTime,
      zoneId
    );
    return newScheduler(
      settingsRepository,
      new WechatNotificationService(settingsRepository, sender, clock),
      clock
    );
  }

  private WechatDailySummaryScheduler newScheduler(
    WechatNotificationSettingsRepository settingsRepository,
    WechatNotificationService notificationService,
    Clock clock
  ) {
    SyncRunRepository syncRunRepository = new SyncRunRepository(jdbcTemplate);
    DailyReportDataProvider dataProvider = new DailyReportDataProvider(
      new CloudInstanceRepository(jdbcTemplate),
      new MetricRepository(jdbcTemplate),
      serverStatusRepository,
      new ServerAlertService(new AlertRuleRepository(jdbcTemplate)),
      syncRunRepository,
      new CostService(new CostRepository(jdbcTemplate), new ManualCostRepository(jdbcTemplate)),
      new TrafficService(new TrafficRepository(jdbcTemplate), new FreeQuotaRepository(jdbcTemplate))
    );
    return new WechatDailySummaryScheduler(
      settingsRepository,
      stateRepository,
      dataProvider,
      notificationService,
      deliveryLogRepository,
      clock
    );
  }

  private WechatNotificationSettingsRepository settingsRepository(
    boolean dailySummaryEnabled,
    String dailySummaryTime,
    String zoneId
  ) {
    WechatNotificationProperties properties = new WechatNotificationProperties(
      true,
      "wx_example_app_id",
      "wx_example_secret",
      "template_example_status",
      "template_example_cost",
      "openid_example_1,openid_example_2",
      true,
      dailySummaryEnabled,
      dailySummaryTime,
      zoneId,
      "https://api.weixin.qq.com"
    );
    return new WechatNotificationSettingsRepository(
      jdbcTemplate,
      properties,
      new WechatSecretCipher(VALID_KEY)
    );
  }

  private List<String> claimedTypes() {
    return jdbcTemplate.queryForList("SELECT id FROM wechat_daily_summary_state ORDER BY id", String.class);
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
