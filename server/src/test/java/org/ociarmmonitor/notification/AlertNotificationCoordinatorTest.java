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
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class AlertNotificationCoordinatorTest {

  private static final String VALID_KEY = Base64.getEncoder().encodeToString(
    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
  );

  private JdbcTemplate jdbcTemplate;
  private CapturingSender sender;
  private AlertNotificationStateRepository stateRepository;
  private WechatDeliveryLogRepository deliveryLogRepository;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update("UPDATE alert_rule SET enabled = CASE WHEN id = 'cpu-high' THEN 1 ELSE 0 END");
    sender = new CapturingSender();
    stateRepository = new AlertNotificationStateRepository(jdbcTemplate);
    deliveryLogRepository = new WechatDeliveryLogRepository(jdbcTemplate);
  }

  @Test
  void sendsOnlyFirstActivationAndOneRecoveryAcrossRestart() {
    AlertNotificationCoordinator firstCoordinator = coordinator(true);

    firstCoordinator.afterSample(snapshot(95));
    firstCoordinator.afterSample(snapshot(96));
    AlertNotificationCoordinator restartedCoordinator = coordinator(true);
    restartedCoordinator.afterSample(snapshot(97));
    restartedCoordinator.afterSample(snapshot(20));
    restartedCoordinator.afterSample(snapshot(18));

    assertThat(sender.messages).hasSize(4);
    assertThat(sender.messages.get(0).first()).isEqualTo("OCI ARM Monitor 告警通知");
    assertThat(sender.messages.get(2).first()).isEqualTo("OCI ARM Monitor 恢复通知");
    assertThat(stateRepository.find("cpu_usage_percent").orElseThrow().active()).isFalse();
    assertThat(deliveryLogRepository.listRecent(20)).hasSize(2);
  }

  @Test
  void tracksTransitionsWithoutSendingWhenImmediatePushIsDisabled() {
    AlertNotificationCoordinator disabledCoordinator = coordinator(false);

    disabledCoordinator.afterSample(snapshot(95));
    disabledCoordinator.afterSample(snapshot(20));

    assertThat(sender.messages).isEmpty();
    assertThat(stateRepository.find("cpu_usage_percent").orElseThrow().active()).isFalse();
    assertThat(deliveryLogRepository.listRecent(20)).isEmpty();
  }

  @Test
  void failedDeliveryDoesNotRetryOnEverySample() {
    sender.failAll = true;
    AlertNotificationCoordinator coordinator = coordinator(true);

    coordinator.afterSample(snapshot(95));
    coordinator.afterSample(snapshot(96));

    assertThat(sender.messages).hasSize(2);
    WechatDeliveryResult delivery = deliveryLogRepository.listRecent(20).get(0);
    assertThat(delivery.successCount()).isZero();
    assertThat(delivery.failureCount()).isEqualTo(2);
  }

  private AlertNotificationCoordinator coordinator(boolean immediatePushEnabled) {
    WechatNotificationProperties properties = new WechatNotificationProperties(
      true,
      "wx_example_app_id",
      "wx_example_secret",
      "template_example_01",
      "openid_example_1,openid_example_2",
      immediatePushEnabled,
      false,
      "09:00",
      "Asia/Shanghai",
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
      Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneOffset.UTC)
    );
    return new AlertNotificationCoordinator(
      settingsRepository,
      new ServerAlertService(new AlertRuleRepository(jdbcTemplate)),
      new SyncRunRepository(jdbcTemplate),
      stateRepository,
      deliveryLogRepository,
      notificationService,
      Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneOffset.UTC)
    );
  }

  private ServerStatusSnapshot snapshot(double cpuUsagePercent) {
    return new ServerStatusSnapshot(
      "2026-07-27T01:00:00Z",
      cpuUsagePercent,
      0.5,
      0.4,
      0.3,
      16_000,
      8_000,
      50,
      4_000,
      4_000,
      0,
      100_000,
      50_000,
      50,
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
    private boolean failAll;

    @Override
    public void sendTemplate(
      WechatNotificationSettings settings,
      String openId,
      WechatTemplateType templateType,
      WechatTemplateMessage message
    ) {
      messages.add(message);
      if (failAll) {
        throw new WechatApiException("模拟发送失败");
      }
    }
  }
}
