package org.ociarmmonitor.notification;

public record WechatNotificationSettingsUpdateRequest(
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
  Boolean detailPageEnabled,
  Integer detailPageTokenTtlDays
) {

  public WechatNotificationSettingsUpdateRequest(
    boolean enabled,
    String appId,
    String appSecret,
    String templateId,
    String costTemplateId,
    String openIds,
    boolean immediatePushEnabled,
    boolean dailySummaryEnabled,
    String dailySummaryTime,
    String zoneId
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
      false,
      1
    );
  }
}
