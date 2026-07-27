package org.ociarmmonitor.notification;

public record WechatNotificationSettingsUpdateRequest(
  boolean enabled,
  String appId,
  String appSecret,
  String templateId,
  String openIds,
  String publicUrl,
  boolean immediatePushEnabled,
  boolean dailySummaryEnabled,
  String dailySummaryTime,
  String zoneId
) {
}
