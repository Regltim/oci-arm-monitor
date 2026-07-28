package org.ociarmmonitor.notification;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.ociarmmonitor.publicreport.PublicReportService;

@Repository
public class WechatNotificationSettingsRepository {

  private static final String DEFAULT_ID = "default";
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

  private final JdbcTemplate jdbcTemplate;
  private final WechatNotificationProperties properties;
  private final WechatSecretCipher secretCipher;
  private final boolean defaultDetailPageEnabled;
  private final int defaultDetailPageTokenTtlDays;

  @Autowired
  public WechatNotificationSettingsRepository(
    JdbcTemplate jdbcTemplate,
    WechatNotificationProperties properties,
    WechatSecretCipher secretCipher,
    @Value("${monitor.wechat.detail-page-enabled:false}") boolean defaultDetailPageEnabled,
    @Value("${monitor.wechat.detail-page-token-ttl-days:1}") int defaultDetailPageTokenTtlDays
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
    this.secretCipher = secretCipher;
    this.defaultDetailPageEnabled = defaultDetailPageEnabled;
    PublicReportService.validateTtlDays(defaultDetailPageTokenTtlDays);
    this.defaultDetailPageTokenTtlDays = defaultDetailPageTokenTtlDays;
  }

  public WechatNotificationSettingsRepository(
    JdbcTemplate jdbcTemplate,
    WechatNotificationProperties properties,
    WechatSecretCipher secretCipher
  ) {
    this(jdbcTemplate, properties, secretCipher, false, 1);
  }

  public WechatNotificationSettings resolve() {
    String costTemplateId = resolveCostTemplateId();
    DetailPageSetting detailPageSetting = resolveDetailPageSetting();
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
        costTemplateId,
        parseOpenIds(secretCipher.decrypt(resultSet.getString("encrypted_open_ids"))),
        resolvePublicUrl(resultSet.getString("public_url")),
        resultSet.getInt("immediate_push_enabled") == 1,
        resultSet.getInt("daily_summary_enabled") == 1,
        parseTime(resultSet.getString("daily_summary_time")),
        parseZoneId(resultSet.getString("zone_id")),
        "DATABASE",
        resultSet.getString("updated_at"),
        detailPageSetting.enabled(),
        detailPageSetting.tokenTtlDays()
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
      mask(settings.costTemplateId()),
      settings.openIds().size(),
      settings.dailySummaryConfigured(),
      dailySummaryMissingReason(settings),
      settings.immediatePushEnabled(),
      settings.dailySummaryEnabled(),
      settings.dailySummaryTime().format(TIME_FORMATTER),
      settings.zoneId().getId(),
      secretCipher.isReady(),
      settings.updatedAt(),
      settings.detailPageEnabled(),
      detailPageReady(settings),
      detailPageMissingReason(settings),
      settings.detailPageTokenTtlDays()
    );
  }

  @Transactional
  public WechatNotificationSettings update(WechatNotificationSettingsUpdateRequest request) {
    if (!secretCipher.isReady()) {
      throw new IllegalArgumentException("未配置 MONITOR_SETTINGS_ENCRYPTION_KEY，无法保存公众号配置");
    }
    WechatNotificationSettings current = resolve();
    String appId = preserveBlank(request.appId(), current.appId());
    String appSecret = preserveBlank(request.appSecret(), current.appSecret());
    String templateId = preserveBlank(request.templateId(), current.templateId());
    String costTemplateId = preserveBlank(request.costTemplateId(), current.costTemplateId());
    List<String> openIds = normalize(request.openIds()).isBlank()
      ? current.openIds()
      : parseOpenIds(request.openIds());
    LocalTime dailySummaryTime = parseTime(request.dailySummaryTime());
    ZoneId zoneId = parseZoneId(request.zoneId());
    boolean detailPageEnabled = request.detailPageEnabled() == null
      ? current.detailPageEnabled()
      : request.detailPageEnabled();
    int detailPageTokenTtlDays = request.detailPageTokenTtlDays() == null
      ? current.detailPageTokenTtlDays()
      : request.detailPageTokenTtlDays();

    validate(
      request.enabled(),
      appId,
      appSecret,
      templateId,
      costTemplateId,
      openIds,
      request.dailySummaryEnabled(),
      detailPageEnabled,
      detailPageTokenTtlDays,
      current.publicUrl()
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
      current.publicUrl(),
      request.immediatePushEnabled() ? 1 : 0,
      request.dailySummaryEnabled() ? 1 : 0,
      dailySummaryTime.format(TIME_FORMATTER),
      zoneId.getId(),
      updatedAt
    );
    jdbcTemplate.update("""
      INSERT INTO wechat_cost_template_setting(id, encrypted_template_id, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        encrypted_template_id = excluded.encrypted_template_id,
        updated_at = excluded.updated_at
      """,
      DEFAULT_ID,
      secretCipher.encrypt(costTemplateId),
      updatedAt
    );
    jdbcTemplate.update("""
      INSERT INTO wechat_detail_page_setting(id, enabled, token_ttl_days, updated_at)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        enabled = excluded.enabled,
        token_ttl_days = excluded.token_ttl_days,
        updated_at = excluded.updated_at
      """,
      DEFAULT_ID,
      detailPageEnabled ? 1 : 0,
      detailPageTokenTtlDays,
      updatedAt
    );
    return resolve();
  }

  private WechatNotificationSettings environmentSettings() {
    String appId = normalize(properties.appId());
    String appSecret = normalize(properties.appSecret());
    String templateId = normalize(properties.templateId());
    String costTemplateId = normalize(properties.costTemplateId());
    List<String> openIds = parseOpenIds(properties.openIds());
    String publicUrl = normalize(properties.publicUrl());
    String source = properties.enabled()
      || !appId.isBlank()
      || !appSecret.isBlank()
      || !templateId.isBlank()
      || !costTemplateId.isBlank()
      || !openIds.isEmpty()
      ? "ENVIRONMENT"
      : "NONE";
    return new WechatNotificationSettings(
      properties.enabled(),
      appId,
      appSecret,
      templateId,
      costTemplateId,
      openIds,
      publicUrl,
      properties.immediatePushEnabled(),
      properties.dailySummaryEnabled(),
      parseTime(properties.dailySummaryTime()),
      parseZoneId(properties.zoneId()),
      source,
      "",
      defaultDetailPageEnabled,
      defaultDetailPageTokenTtlDays
    );
  }

  private void validate(
    boolean enabled,
    String appId,
    String appSecret,
    String templateId,
    String costTemplateId,
    List<String> openIds,
    boolean dailySummaryEnabled,
    boolean detailPageEnabled,
    int detailPageTokenTtlDays,
    String publicUrl
  ) {
    if (enabled && (
      appId.isBlank()
        || appSecret.isBlank()
        || templateId.isBlank()
        || openIds.isEmpty()
    )) {
      throw new IllegalArgumentException("启用微信公众号通知前，请完整填写 AppID、AppSecret、运行状态 Template ID 和接收人 OpenID");
    }
    if (enabled && dailySummaryEnabled && costTemplateId.isBlank()) {
      throw new IllegalArgumentException("启用每日摘要前，请配置费用与流量 Template ID");
    }
    if (detailPageEnabled && !dailySummaryEnabled) {
      throw new IllegalArgumentException("启用免登录明细前，请先启用每日摘要");
    }
    PublicReportService.validateTtlDays(detailPageTokenTtlDays);
    if (detailPageEnabled && !isHttpsOrigin(publicUrl)) {
      throw new IllegalArgumentException("启用免登录明细前，请将 MONITOR_PUBLIC_URL 配置为 HTTPS 地址");
    }
  }

  private DetailPageSetting resolveDetailPageSetting() {
    List<DetailPageSetting> settings = jdbcTemplate.query(
      "SELECT enabled, token_ttl_days FROM wechat_detail_page_setting WHERE id = ?",
      (resultSet, rowNum) -> new DetailPageSetting(
        resultSet.getInt("enabled") == 1,
        resultSet.getInt("token_ttl_days")
      ),
      DEFAULT_ID
    );
    return settings.isEmpty()
      ? new DetailPageSetting(defaultDetailPageEnabled, defaultDetailPageTokenTtlDays)
      : settings.get(0);
  }

  private String resolvePublicUrl(String databaseValue) {
    String environmentValue = normalize(properties.publicUrl());
    if (!environmentValue.isBlank()) {
      return environmentValue;
    }
    String normalizedDatabaseValue = normalize(databaseValue);
    return normalizedDatabaseValue;
  }

  private String resolveCostTemplateId() {
    List<String> encryptedValues = jdbcTemplate.query(
      "SELECT encrypted_template_id FROM wechat_cost_template_setting WHERE id = ?",
      (resultSet, rowNum) -> resultSet.getString("encrypted_template_id"),
      DEFAULT_ID
    );
    if (!encryptedValues.isEmpty()) {
      String databaseValue = normalize(secretCipher.decrypt(encryptedValues.get(0)));
      if (!databaseValue.isBlank()) {
        return databaseValue;
      }
    }
    return normalize(properties.costTemplateId());
  }

  private String dailySummaryMissingReason(WechatNotificationSettings settings) {
    if (!settings.configured()) {
      return "公众号基础配置不完整";
    }
    if (settings.costTemplateId().isBlank()) {
      return "费用与流量模板未配置";
    }
    return "";
  }

  private boolean detailPageReady(WechatNotificationSettings settings) {
    return isHttpsOrigin(settings.publicUrl());
  }

  private String detailPageMissingReason(WechatNotificationSettings settings) {
    return detailPageReady(settings) ? "" : "MONITOR_PUBLIC_URL 未配置为 HTTPS 地址";
  }

  private boolean isHttpsOrigin(String value) {
    try {
      URI uri = new URI(normalize(value));
      String path = uri.getRawPath();
      return "https".equalsIgnoreCase(uri.getScheme())
        && uri.getHost() != null
        && !uri.getHost().isBlank()
        && uri.getUserInfo() == null
        && (path == null || path.isBlank() || "/".equals(path))
        && uri.getRawQuery() == null
        && uri.getRawFragment() == null;
    } catch (URISyntaxException exception) {
      return false;
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

  private record DetailPageSetting(boolean enabled, int tokenTtlDays) {
  }
}
