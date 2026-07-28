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
  String zoneId
) {
}
