package org.ociarmmonitor.notification;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

public record WechatNotificationSettings(
  boolean enabled,
  String appId,
  String appSecret,
  String templateId,
  String costTemplateId,
  List<String> openIds,
  String publicUrl,
  boolean immediatePushEnabled,
  boolean dailySummaryEnabled,
  LocalTime dailySummaryTime,
  ZoneId zoneId,
  String source,
  String updatedAt,
  boolean detailPageEnabled,
  int detailPageTokenTtlDays
) {

  public boolean configured() {
    return !appId.isBlank()
      && !appSecret.isBlank()
      && !templateId.isBlank()
      && !openIds.isEmpty();
  }

  public boolean dailySummaryConfigured() {
    return configured() && !costTemplateId.isBlank();
  }

  public WechatNotificationSettings(
    boolean enabled,
    String appId,
    String appSecret,
    String templateId,
    List<String> openIds,
    String publicUrl,
    boolean immediatePushEnabled,
    boolean dailySummaryEnabled,
    LocalTime dailySummaryTime,
    ZoneId zoneId,
    String source,
    String updatedAt
  ) {
    this(
      enabled,
      appId,
      appSecret,
      templateId,
      "",
      openIds,
      publicUrl,
      immediatePushEnabled,
      dailySummaryEnabled,
      dailySummaryTime,
      zoneId,
      source,
      updatedAt,
      false,
      1
    );
  }

  public WechatNotificationSettings(
    boolean enabled,
    String appId,
    String appSecret,
    String templateId,
    String costTemplateId,
    List<String> openIds,
    String publicUrl,
    boolean immediatePushEnabled,
    boolean dailySummaryEnabled,
    LocalTime dailySummaryTime,
    ZoneId zoneId,
    String source,
    String updatedAt
  ) {
    this(
      enabled,
      appId,
      appSecret,
      templateId,
      costTemplateId,
      openIds,
      publicUrl,
      immediatePushEnabled,
      dailySummaryEnabled,
      dailySummaryTime,
      zoneId,
      source,
      updatedAt,
      false,
      1
    );
  }
}
