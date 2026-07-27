package org.ociarmmonitor.notification;

public record WechatDeliveryResult(
  String notificationType,
  String metricName,
  int successCount,
  int failureCount,
  String message,
  String createdAt
) {

  public boolean successful() {
    return successCount > 0 && failureCount == 0;
  }
}
