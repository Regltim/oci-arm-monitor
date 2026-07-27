package org.ociarmmonitor.notification;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

public record WechatNotificationSettings(
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

  public boolean configured() {
    return !appId.isBlank()
      && !appSecret.isBlank()
      && !templateId.isBlank()
      && !openIds.isEmpty()
      && !publicUrl.isBlank();
  }
}
