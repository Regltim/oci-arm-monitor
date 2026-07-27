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
    assertThat(settings.openIds()).containsExactly("openid_example_1", "openid_example_2");
    assertThat(status.configured()).isTrue();
    assertThat(status.appIdMasked()).isEqualTo("wx_e****p_id");
    assertThat(status.templateIdMasked()).isEqualTo("temp****e_01");
    assertThat(status.appSecretConfigured()).isTrue();
    assertThat(status.recipientCount()).isEqualTo(2);
  }

  @Test
  void savesEncryptedDatabaseOverrideAndDeduplicatesRecipients() {
    WechatNotificationSettings updated = repository.update(new WechatNotificationSettingsUpdateRequest(
      true,
      "wx_database_app",
      "database-secret",
      "database-template",
      "openid_database_1, openid_database_1\nopenid_database_2",
      "https://monitor.example.com",
      false,
      true,
      "21:30",
      "Asia/Shanghai"
    ));

    assertThat(updated.source()).isEqualTo("DATABASE");
    assertThat(updated.openIds()).containsExactly("openid_database_1", "openid_database_2");
    assertThat(updated.immediatePushEnabled()).isFalse();
    assertThat(updated.dailySummaryEnabled()).isTrue();
    assertThat(updated.dailySummaryTime().toString()).isEqualTo("21:30");

    String storedValues = jdbcTemplate.queryForObject("""
      SELECT encrypted_app_id || encrypted_app_secret || encrypted_template_id || encrypted_open_ids
      FROM wechat_notification_setting
      WHERE id = 'default'
      """, String.class);
    assertThat(storedValues)
      .doesNotContain("wx_database_app")
      .doesNotContain("database-secret")
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
      "https://dashboard.example.com",
      true,
      false,
      "08:15",
      "UTC"
    ));

    assertThat(updated.appId()).isEqualTo("wx_database_app");
    assertThat(updated.appSecret()).isEqualTo("database-secret");
    assertThat(updated.templateId()).isEqualTo("database-template");
    assertThat(updated.openIds()).containsExactly("openid_database_1", "openid_database_2");
    assertThat(updated.publicUrl()).isEqualTo("https://dashboard.example.com");
    assertThat(updated.zoneId().getId()).isEqualTo("UTC");
  }

  @Test
  void rejectsEnabledIncompleteOrInvalidConfiguration() {
    WechatNotificationSettingsRepository emptyRepository = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      emptyEnvironmentProperties(),
      new WechatSecretCipher(VALID_KEY)
    );

    assertThatThrownBy(() -> emptyRepository.update(new WechatNotificationSettingsUpdateRequest(
      true, "", "", "", "", "https://monitor.example.com", true, false, "09:00", "Asia/Shanghai"
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("启用微信公众号通知前，请完整填写 AppID、AppSecret、Template ID、接收人 OpenID 和面板地址");

    assertThatThrownBy(() -> repository.update(new WechatNotificationSettingsUpdateRequest(
      false, "", "", "", "", "not-a-url", true, false, "09:00", "Asia/Shanghai"
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("面板地址必须是有效的 HTTP 或 HTTPS 地址");

    assertThatThrownBy(() -> repository.update(new WechatNotificationSettingsUpdateRequest(
      false, "", "", "", "", "https://monitor.example.com", true, true, "25:00", "Asia/Shanghai"
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("每日推送时间格式必须为 HH:mm");

    assertThatThrownBy(() -> repository.update(new WechatNotificationSettingsUpdateRequest(
      false, "", "", "", "", "https://monitor.example.com", true, false, "09:00", "Invalid/Zone"
    ))).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("时区无效：Invalid/Zone");
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

  private WechatNotificationProperties emptyEnvironmentProperties() {
    return new WechatNotificationProperties(
      false,
      "",
      "",
      "",
      "",
      true,
      false,
      "09:00",
      "Asia/Shanghai",
      "",
      "https://api.weixin.qq.com"
    );
  }

  private WechatNotificationSettingsUpdateRequest databaseRequest() {
    return new WechatNotificationSettingsUpdateRequest(
      true,
      "wx_database_app",
      "database-secret",
      "database-template",
      "openid_database_1,openid_database_2",
      "https://monitor.example.com",
      true,
      false,
      "09:00",
      "Asia/Shanghai"
    );
  }
}
