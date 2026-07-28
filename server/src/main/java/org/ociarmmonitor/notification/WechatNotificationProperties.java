package org.ociarmmonitor.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WechatNotificationProperties {

  private final boolean enabled;
  private final String appId;
  private final String appSecret;
  private final String templateId;
  private final String costTemplateId;
  private final String openIds;
  private final boolean immediatePushEnabled;
  private final boolean dailySummaryEnabled;
  private final String dailySummaryTime;
  private final String zoneId;
  private final String publicUrl;
  private final String apiBaseUrl;

  @Autowired
  public WechatNotificationProperties(
    @Value("${monitor.wechat.enabled:false}") boolean enabled,
    @Value("${monitor.wechat.app-id:}") String appId,
    @Value("${monitor.wechat.app-secret:}") String appSecret,
    @Value("${monitor.wechat.template-id:}") String templateId,
    @Value("${monitor.wechat.cost-template-id:}") String costTemplateId,
    @Value("${monitor.wechat.open-ids:}") String openIds,
    @Value("${monitor.wechat.immediate-push-enabled:true}") boolean immediatePushEnabled,
    @Value("${monitor.wechat.daily-summary-enabled:false}") boolean dailySummaryEnabled,
    @Value("${monitor.wechat.daily-summary-time:09:00}") String dailySummaryTime,
    @Value("${monitor.wechat.zone-id:Asia/Shanghai}") String zoneId,
    @Value("${monitor.wechat.public-url:}") String publicUrl,
    @Value("${monitor.wechat.api-base-url:https://api.weixin.qq.com}") String apiBaseUrl
  ) {
    this.enabled = enabled;
    this.appId = appId;
    this.appSecret = appSecret;
    this.templateId = templateId;
    this.costTemplateId = costTemplateId;
    this.openIds = openIds;
    this.immediatePushEnabled = immediatePushEnabled;
    this.dailySummaryEnabled = dailySummaryEnabled;
    this.dailySummaryTime = dailySummaryTime;
    this.zoneId = zoneId;
    this.publicUrl = publicUrl;
    this.apiBaseUrl = apiBaseUrl;
  }

  public boolean enabled() {
    return enabled;
  }

  public String appId() {
    return appId;
  }

  public String appSecret() {
    return appSecret;
  }

  public String templateId() {
    return templateId;
  }

  public String costTemplateId() {
    return costTemplateId;
  }

  public String openIds() {
    return openIds;
  }

  public boolean immediatePushEnabled() {
    return immediatePushEnabled;
  }

  public boolean dailySummaryEnabled() {
    return dailySummaryEnabled;
  }

  public String dailySummaryTime() {
    return dailySummaryTime;
  }

  public String zoneId() {
    return zoneId;
  }

  public String publicUrl() {
    return publicUrl;
  }

  public String apiBaseUrl() {
    return apiBaseUrl;
  }

  public WechatNotificationProperties(
    boolean enabled,
    String appId,
    String appSecret,
    String templateId,
    String costTemplateId,
    String openIds,
    boolean immediatePushEnabled,
    boolean dailySummaryEnabled,
    String dailySummaryTime,
    String zoneId,
    String apiBaseUrl
  ) {
    this(
      enabled,
      appId,
      appSecret,
      templateId,
      costTemplateId,
      openIds,
      immediatePushEnabled,
      dailySummaryEnabled,
      dailySummaryTime,
      zoneId,
      "",
      apiBaseUrl
    );
  }

  public WechatNotificationProperties(
    boolean enabled,
    String appId,
    String appSecret,
    String templateId,
    String openIds,
    boolean immediatePushEnabled,
    boolean dailySummaryEnabled,
    String dailySummaryTime,
    String zoneId,
    String publicUrl,
    String apiBaseUrl
  ) {
    this(
      enabled,
      appId,
      appSecret,
      templateId,
      "",
      openIds,
      immediatePushEnabled,
      dailySummaryEnabled,
      dailySummaryTime,
      zoneId,
      publicUrl,
      apiBaseUrl
    );
  }
}
