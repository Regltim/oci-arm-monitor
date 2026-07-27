package org.ociarmmonitor.notification;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WechatNotificationSettingsRepository {

  private static final String DEFAULT_ID = "default";
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

  private final JdbcTemplate jdbcTemplate;
  private final WechatNotificationProperties properties;
  private final WechatSecretCipher secretCipher;

  public WechatNotificationSettingsRepository(
    JdbcTemplate jdbcTemplate,
    WechatNotificationProperties properties,
    WechatSecretCipher secretCipher
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
    this.secretCipher = secretCipher;
  }

  public WechatNotificationSettings resolve() {
    List<WechatNotificationSettings> settings = jdbcTemplate.query("""
      SELECT enabled, encrypted_app_id, encrypted_app_secret, encrypted_template_id,
        encrypted_open_ids, public_url, immediate_push_enabled, daily_summary_enabled,
        daily_summary_time, zone_id, updated_at
      FROM wechat_notification_setting
      WHERE id = ?
      """, (resultSet, rowNum) -> new WechatNotificationSettings(
        resultSet.getInt("enabled") == 1,
        secretCipher.decrypt(resultSet.getString("encrypted_app_id")),
        secretCipher.decrypt(resultSet.getString("encrypted_app_secret")),
        secretCipher.decrypt(resultSet.getString("encrypted_template_id")),
        parseOpenIds(secretCipher.decrypt(resultSet.getString("encrypted_open_ids"))),
        normalize(resultSet.getString("public_url")),
        resultSet.getInt("immediate_push_enabled") == 1,
        resultSet.getInt("daily_summary_enabled") == 1,
        parseTime(resultSet.getString("daily_summary_time")),
        parseZoneId(resultSet.getString("zone_id")),
        "DATABASE",
        resultSet.getString("updated_at")
      ), DEFAULT_ID);
    return settings.isEmpty() ? environmentSettings() : settings.get(0);
  }

  public WechatNotificationSettingsStatus status() {
    WechatNotificationSettings settings = resolve();
    return new WechatNotificationSettingsStatus(
      settings.enabled(),
      settings.configured(),
      settings.source(),
      mask(settings.appId()),
      !settings.appSecret().isBlank(),
      mask(settings.templateId()),
      settings.openIds().size(),
      settings.publicUrl(),
      settings.immediatePushEnabled(),
      settings.dailySummaryEnabled(),
      settings.dailySummaryTime().format(TIME_FORMATTER),
      settings.zoneId().getId(),
      secretCipher.isReady(),
      settings.updatedAt()
    );
  }

  public WechatNotificationSettings update(WechatNotificationSettingsUpdateRequest request) {
    if (!secretCipher.isReady()) {
      throw new IllegalArgumentException("未配置 MONITOR_SETTINGS_ENCRYPTION_KEY，无法保存公众号配置");
    }
    WechatNotificationSettings current = resolve();
    String appId = preserveBlank(request.appId(), current.appId());
    String appSecret = preserveBlank(request.appSecret(), current.appSecret());
    String templateId = preserveBlank(request.templateId(), current.templateId());
    List<String> openIds = normalize(request.openIds()).isBlank()
      ? current.openIds()
      : parseOpenIds(request.openIds());
    String publicUrl = normalize(request.publicUrl());
    LocalTime dailySummaryTime = parseTime(request.dailySummaryTime());
    ZoneId zoneId = parseZoneId(request.zoneId());

    validate(
      request.enabled(),
      appId,
      appSecret,
      templateId,
      openIds,
      publicUrl
    );

    String updatedAt = Instant.now().toString();
    jdbcTemplate.update("""
      INSERT INTO wechat_notification_setting(
        id, enabled, encrypted_app_id, encrypted_app_secret, encrypted_template_id,
        encrypted_open_ids, public_url, immediate_push_enabled, daily_summary_enabled,
        daily_summary_time, zone_id, updated_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        enabled = excluded.enabled,
        encrypted_app_id = excluded.encrypted_app_id,
        encrypted_app_secret = excluded.encrypted_app_secret,
        encrypted_template_id = excluded.encrypted_template_id,
        encrypted_open_ids = excluded.encrypted_open_ids,
        public_url = excluded.public_url,
        immediate_push_enabled = excluded.immediate_push_enabled,
        daily_summary_enabled = excluded.daily_summary_enabled,
        daily_summary_time = excluded.daily_summary_time,
        zone_id = excluded.zone_id,
        updated_at = excluded.updated_at
      """,
      DEFAULT_ID,
      request.enabled() ? 1 : 0,
      secretCipher.encrypt(appId),
      secretCipher.encrypt(appSecret),
      secretCipher.encrypt(templateId),
      secretCipher.encrypt(String.join(",", openIds)),
      publicUrl,
      request.immediatePushEnabled() ? 1 : 0,
      request.dailySummaryEnabled() ? 1 : 0,
      dailySummaryTime.format(TIME_FORMATTER),
      zoneId.getId(),
      updatedAt
    );
    return resolve();
  }

  private WechatNotificationSettings environmentSettings() {
    String appId = normalize(properties.appId());
    String appSecret = normalize(properties.appSecret());
    String templateId = normalize(properties.templateId());
    List<String> openIds = parseOpenIds(properties.openIds());
    String publicUrl = normalize(properties.publicUrl());
    String source = properties.enabled()
      || !appId.isBlank()
      || !appSecret.isBlank()
      || !templateId.isBlank()
      || !openIds.isEmpty()
      ? "ENVIRONMENT"
      : "NONE";
    return new WechatNotificationSettings(
      properties.enabled(),
      appId,
      appSecret,
      templateId,
      openIds,
      publicUrl,
      properties.immediatePushEnabled(),
      properties.dailySummaryEnabled(),
      parseTime(properties.dailySummaryTime()),
      parseZoneId(properties.zoneId()),
      source,
      ""
    );
  }

  private void validate(
    boolean enabled,
    String appId,
    String appSecret,
    String templateId,
    List<String> openIds,
    String publicUrl
  ) {
    if (enabled && (
      appId.isBlank()
        || appSecret.isBlank()
        || templateId.isBlank()
        || openIds.isEmpty()
        || publicUrl.isBlank()
    )) {
      throw new IllegalArgumentException("启用微信公众号通知前，请完整填写 AppID、AppSecret、Template ID、接收人 OpenID 和面板地址");
    }
    if (!publicUrl.isBlank()) {
      validatePublicUrl(publicUrl);
    }
  }

  private void validatePublicUrl(String publicUrl) {
    try {
      URI uri = URI.create(publicUrl);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null) {
        throw new IllegalArgumentException("面板地址必须是有效的 HTTP 或 HTTPS 地址");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("面板地址必须是有效的 HTTP 或 HTTPS 地址");
    }
  }

  private LocalTime parseTime(String value) {
    try {
      return LocalTime.parse(normalize(value), TIME_FORMATTER);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("每日推送时间格式必须为 HH:mm");
    }
  }

  private ZoneId parseZoneId(String value) {
    String normalizedValue = normalize(value);
    try {
      return ZoneId.of(normalizedValue);
    } catch (DateTimeException exception) {
      throw new IllegalArgumentException("时区无效：" + normalizedValue);
    }
  }

  private List<String> parseOpenIds(String value) {
    LinkedHashSet<String> openIds = new LinkedHashSet<>();
    Arrays.stream(normalize(value).split("[,\\r\\n]+"))
      .map(String::trim)
      .filter(openId -> !openId.isBlank())
      .forEach(openIds::add);
    return List.copyOf(openIds);
  }

  private String preserveBlank(String value, String currentValue) {
    String normalizedValue = normalize(value);
    return normalizedValue.isBlank() ? currentValue : normalizedValue;
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private String mask(String value) {
    String normalizedValue = normalize(value);
    if (normalizedValue.isBlank()) {
      return "";
    }
    if (normalizedValue.length() <= 8) {
      int visibleLength = Math.min(2, normalizedValue.length() / 2);
      return normalizedValue.substring(0, visibleLength)
        + "****"
        + normalizedValue.substring(normalizedValue.length() - visibleLength);
    }
    return normalizedValue.substring(0, 4)
      + "****"
      + normalizedValue.substring(normalizedValue.length() - 4);
  }
}
