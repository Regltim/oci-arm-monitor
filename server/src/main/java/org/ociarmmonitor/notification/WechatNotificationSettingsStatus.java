package org.ociarmmonitor.notification;

public record WechatNotificationSettingsStatus(
  boolean enabled,
  boolean configured,
  String source,
  String appIdMasked,
  boolean appSecretConfigured,
  String templateIdMasked,
  String costTemplateIdMasked,
  int recipientCount,
  boolean dailySummaryConfigured,
  String dailySummaryMissingReason,
  boolean immediatePushEnabled,
  boolean dailySummaryEnabled,
  String dailySummaryTime,
  String zoneId,
  boolean encryptionReady,
  String updatedAt,
  boolean detailPageEnabled,
  boolean detailPageReady,
  String detailPageMissingReason,
  int detailPageTokenTtlDays
) {
}
