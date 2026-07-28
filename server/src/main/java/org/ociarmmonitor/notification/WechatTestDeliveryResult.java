package org.ociarmmonitor.notification;

public record WechatTestDeliveryResult(
  WechatDeliveryResult status,
  WechatDeliveryResult costTraffic,
  int successCount,
  int failureCount,
  String message
) {
}
