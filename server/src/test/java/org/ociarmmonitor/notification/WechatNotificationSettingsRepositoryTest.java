package org.ociarmmonitor.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WechatNotificationSettingsRepositoryTest {

  private static final String VALID_KEY = Base64.getEncoder().encodeToString(
    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
  );

  private JdbcTemplate jdbcTemplate;
  private WechatNotificationSettingsRepository repository;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    jdbcTemplate = new JdbcTemplate(dataSource);
    repository = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      environmentProperties(),
      new WechatSecretCipher(VALID_KEY)
    );
  }

  @Test
  void resolvesEnvironmentConfigurationAndReturnsOnlyMaskedStatus() {
    WechatNotificationSettings settings = repository.resolve();
    WechatNotificationSettingsStatus status = repository.status();

    assertThat(settings.source()).isEqualTo("ENVIRONMENT");
    assertThat(settings.appId()).isEqualTo("wx_example_app_id");
    assertThat(settings.costTemplateId()).isEqualTo("template_example_cost");
    assertThat(settings.openIds()).containsExactly("openid_example_1", "openid_example_2");
    assertThat(status.configured()).isTrue();
    assertThat(status.dailySummaryConfigured()).isTrue();
    assertThat(status.dailySummaryMissingReason()).isEmpty();
    assertThat(status.appIdMasked()).isEqualTo("wx_e****p_id");
    assertThat(status.templateIdMasked()).isEqualTo("temp****e_01");
    assertThat(status.costTemplateIdMasked()).isEqualTo("temp****cost");
    assertThat(status.appSecretConfigured()).isTrue();
    assertThat(status.recipientCount()).isEqualTo(2);
    assertThat(status.detailPageEnabled()).isFalse();
    assertThat(status.detailPageTokenTtlDays()).isEqualTo(1);
  }

  @Test
  void savesEncryptedDatabaseOverrideAndDeduplicatesRecipients() {
    WechatNotificationSettings updated = repository.update(new WechatNotificationSettingsUpdateRequest(
      true,
      "wx_database_app",
      "database-secret",
      "database-template",
      "database-cost-template",
      "openid_database_1, openid_database_1\nopenid_database_2",
      false,
      true,
      "21:30",
      "Asia/Shanghai",
      true,
      1
    ));

    assertThat(updated.source()).isEqualTo("DATABASE");
    assertThat(updated.openIds()).containsExactly("openid_database_1", "openid_database_2");
    assertThat(updated.immediatePushEnabled()).isFalse();
    assertThat(updated.dailySummaryEnabled()).isTrue();
    assertThat(updated.dailySummaryConfigured()).isTrue();
    assertThat(updated.dailySummaryTime().toString()).isEqualTo("21:30");
    assertThat(updated.detailPageEnabled()).isTrue();
    assertThat(updated.detailPageTokenTtlDays()).isEqualTo(1);

    String storedValues = jdbcTemplate.queryForObject("""
      SELECT s.encrypted_app_id || s.encrypted_app_secret || s.encrypted_template_id
        || s.encrypted_open_ids || c.encrypted_template_id
      FROM wechat_notification_setting s
      JOIN wechat_cost_template_setting c ON c.id = s.id
      WHERE s.id = 'default'
      """, String.class);
    assertThat(storedValues)
      .doesNotContain("wx_database_app")
      .doesNotContain("database-secret")
      .doesNotContain("database-cost-template")
      .doesNotContain("openid_database_1");
  }

  @Test
  void blankCredentialFieldsPreserveCurrentDatabaseValues() {
    repository.update(databaseRequest());

    WechatNotificationSettings updated = repository.update(new WechatNotificationSettingsUpdateRequest(
      true,
      "",
      "",
      "",
      "",
      "",
      true,
      false,
      "08:15",
      "UTC"
    ));

    assertThat(updated.appId()).isEqualTo("wx_database_app");
    assertThat(updated.appSecret()).isEqualTo("database-secret");
    assertThat(updated.templateId()).isEqualTo("database-template");
    assertThat(updated.costTemplateId()).isEqualTo("database-cost-template");
    assertThat(updated.openIds()).containsExactly("openid_database_1", "openid_database_2");
    assertThat(updated.zoneId().getId()).isEqualTo("UTC");
  }

  @Test
  void keepsSingleTemplateAlertConfigurationUsableButReportsDailySummaryMissing() {
    WechatNotificationSettingsRepository singleTemplateRepository = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      propertiesWithCostTemplate(""),
      new WechatSecretCipher(VALID_KEY)
    );

    WechatNotificationSettings settings = singleTemplateRepository.resolve();
    WechatNotificationSettingsStatus status = singleTemplateRepository.status();

    assertThat(settings.configured()).isTrue();
    assertThat(settings.dailySummaryConfigured()).isFalse();
    assertThat(status.configured()).isTrue();
    assertThat(status.dailySummaryConfigured()).isFalse();
    assertThat(status.dailySummaryMissingReason()).isEqualTo("费用与流量模板未配置");
  }

  @Test
  void reportsEnvironmentSourceWhenOnlyCostTemplateIsConfigured() {
    WechatNotificationSettingsRepository costTemplateOnlyRepository = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      new WechatNotificationProperties(
        false,
        "",
        "",
        "",
        "template_example_cost",
        "",
        true,
        false,
        "09:00",
        "Asia/Shanghai",
        "https://api.weixin.qq.com"
      ),
      new WechatSecretCipher(VALID_KEY)
    );

    WechatNotificationSettingsStatus status = costTemplateOnlyRepository.status();

    assertThat(status.source()).isEqualTo("ENVIRONMENT");
    assertThat(status.costTemplateIdMasked()).isEqualTo("temp****cost");
  }

  @Test
  void upgradesLegacyDatabaseWithoutLosingStoredSettings() {
    SingleConnectionDataSource legacyDataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    JdbcTemplate legacyJdbcTemplate = new JdbcTemplate(legacyDataSource);
    WechatSecretCipher secretCipher = new WechatSecretCipher(VALID_KEY);
    legacyJdbcTemplate.execute("""
      CREATE TABLE wechat_notification_setting (
        id TEXT PRIMARY KEY,
        enabled INTEGER NOT NULL,
        encrypted_app_id TEXT NOT NULL,
        encrypted_app_secret TEXT NOT NULL,
        encrypted_template_id TEXT NOT NULL,
        encrypted_open_ids TEXT NOT NULL,
        public_url TEXT NOT NULL,
        immediate_push_enabled INTEGER NOT NULL,
        daily_summary_enabled INTEGER NOT NULL,
        daily_summary_time TEXT NOT NULL,
        zone_id TEXT NOT NULL,
        updated_at TEXT NOT NULL
      )
      """);
    legacyJdbcTemplate.update("""
      INSERT INTO wechat_notification_setting(
        id, enabled, encrypted_app_id, encrypted_app_secret, encrypted_template_id,
        encrypted_open_ids, public_url, immediate_push_enabled, daily_summary_enabled,
        daily_summary_time, zone_id, updated_at
      ) VALUES ('default', 1, ?, ?, ?, ?, ?, 1, 0, '09:00', 'Asia/Shanghai', ?)
      """,
      secretCipher.encrypt("wx_legacy_app"),
      secretCipher.encrypt("legacy-secret"),
      secretCipher.encrypt("legacy-status-template"),
      secretCipher.encrypt("openid_legacy_1"),
      "https://legacy.example.com",
      "2026-07-01T00:00:00Z"
    );
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(legacyDataSource);
    WechatNotificationSettingsRepository legacyRepository = new WechatNotificationSettingsRepository(
      legacyJdbcTemplate,
      propertiesWithCostTemplate("template_example_cost"),
      secretCipher
    );

    WechatNotificationSettings upgraded = legacyRepository.resolve();
    legacyRepository.update(new WechatNotificationSettingsUpdateRequest(
      true, "", "", "", "", "", true, true, "09:00", "Asia/Shanghai"
    ));

    assertThat(upgraded.appId()).isEqualTo("wx_legacy_app");
    assertThat(upgraded.templateId()).isEqualTo("legacy-status-template");
    assertThat(upgraded.costTemplateId()).isEqualTo("template_example_cost");
    assertThat(upgraded.openIds()).containsExactly("openid_legacy_1");
    assertThat(legacyJdbcTemplate.queryForObject(
      "SELECT public_url FROM wechat_notification_setting WHERE id = 'default'",
      String.class
    )).isEqualTo("https://monitor.example.com");
  }

  @Test
  void rejectsEnabledIncompleteOrInvalidConfiguration() {
    WechatNotificationSettingsRepository emptyRepository = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      emptyEnvironmentProperties(),
      new WechatSecretCipher(VALID_KEY)
    );
    WechatNotificationSettingsRepository repositoryWithoutCostTemplate = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      propertiesWithCostTemplate(""),
      new WechatSecretCipher(VALID_KEY)
    );

    assertThatThrownBy(() -> emptyRepository.update(new WechatNotificationSettingsUpdateRequest(
      true, "", "", "", "", "", true, false, "09:00", "Asia/Shanghai"
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("启用微信公众号通知前，请完整填写 AppID、AppSecret、运行状态 Template ID 和接收人 OpenID");

    assertThatThrownBy(() -> repositoryWithoutCostTemplate.update(new WechatNotificationSettingsUpdateRequest(
      true, "", "", "", "", "", true, true, "09:00", "Asia/Shanghai"
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("启用每日摘要前，请配置费用与流量 Template ID");

    assertThatThrownBy(() -> repository.update(new WechatNotificationSettingsUpdateRequest(
      false, "", "", "", "", "", true, true, "25:00", "Asia/Shanghai"
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("每日推送时间格式必须为 HH:mm");

    assertThatThrownBy(() -> repository.update(new WechatNotificationSettingsUpdateRequest(
      false, "", "", "", "", "", true, false, "09:00", "Invalid/Zone"
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("时区无效：Invalid/Zone");

    assertThatThrownBy(() -> repository.update(new WechatNotificationSettingsUpdateRequest(
      false, "", "", "", "", "", true, false, "09:00", "Asia/Shanghai", false, 91
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("明细访问令牌有效期必须为 1 至 90 天");

    assertThatThrownBy(() -> repository.update(new WechatNotificationSettingsUpdateRequest(
      false, "", "", "", "", "", true, false, "09:00", "Asia/Shanghai", false, 0
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("明细访问令牌有效期必须为 1 至 90 天");

    assertThatThrownBy(() -> repository.update(new WechatNotificationSettingsUpdateRequest(
      false, "", "", "", "", "", true, false, "09:00", "Asia/Shanghai", true, 1
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("启用免登录明细前，请先启用每日摘要");
  }

  @Test
  void requiresHttpsPublicUrlWhenDetailPageIsEnabled() {
    WechatNotificationSettingsRepository repositoryWithoutPublicUrl = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      new WechatNotificationProperties(
        true,
        "wx_example_app_id",
        "wx_example_secret",
        "template_example_01",
        "template_example_cost",
        "openid_example_1",
        true,
        false,
        "09:00",
        "Asia/Shanghai",
        "https://api.weixin.qq.com"
      ),
      new WechatSecretCipher(VALID_KEY)
    );

    assertThatThrownBy(() -> repositoryWithoutPublicUrl.update(new WechatNotificationSettingsUpdateRequest(
      true,
      "",
      "",
      "",
      "",
      "",
      true,
      true,
      "09:00",
      "Asia/Shanghai",
      true,
      1
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("启用免登录明细前，请将 MONITOR_PUBLIC_URL 配置为 HTTPS 地址");
  }

  @Test
  void rejectsDatabaseSaveWhenEncryptionKeyIsMissing() {
    WechatNotificationSettingsRepository repositoryWithoutKey = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      environmentProperties(),
      new WechatSecretCipher("")
    );

    assertThatThrownBy(() -> repositoryWithoutKey.update(databaseRequest()))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("未配置 MONITOR_SETTINGS_ENCRYPTION_KEY，无法保存公众号配置");
  }

  private WechatNotificationProperties environmentProperties() {
    return propertiesWithCostTemplate("template_example_cost");
  }

  private WechatNotificationProperties propertiesWithCostTemplate(String costTemplateId) {
    return new WechatNotificationProperties(
      true,
      "wx_example_app_id",
      "wx_example_secret",
      "template_example_01",
      costTemplateId,
      "openid_example_1,openid_example_2",
      true,
      false,
      "09:00",
      "Asia/Shanghai",
      "https://monitor.example.com",
      "https://api.weixin.qq.com"
    );
  }

  private WechatNotificationProperties emptyEnvironmentProperties() {
    return new WechatNotificationProperties(
      false,
      "",
      "",
      "",
      "",
      "",
      true,
      false,
      "09:00",
      "Asia/Shanghai",
      "https://api.weixin.qq.com"
    );
  }

  private WechatNotificationSettingsUpdateRequest databaseRequest() {
    return new WechatNotificationSettingsUpdateRequest(
      true,
      "wx_database_app",
      "database-secret",
      "database-template",
      "database-cost-template",
      "openid_database_1,openid_database_2",
      true,
      false,
      "09:00",
      "Asia/Shanghai"
    );
  }
}
