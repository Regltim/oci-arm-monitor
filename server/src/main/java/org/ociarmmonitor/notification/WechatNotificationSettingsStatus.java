package org.ociarmmonitor.notification;

public record WechatNotificationSettingsStatus(
  boolean enabled,
  boolean configured,
  String source,
  String appIdMasked,
  boolean appSecretConfigured,
  String templateIdMasked,
  int recipientCount,
  String publicUrl,
  boolean immediatePushEnabled,
  boolean dailySummaryEnabled,
  String dailySummaryTime,
  String zoneId,
  boolean encryptionReady,
  String updatedAt
) {
}
