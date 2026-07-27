package org.ociarmmonitor.notification;

import org.ociarmmonitor.serverstatus.ServerAlert;

public record AlertNotificationState(
  String metricName,
  boolean active,
  String severity,
  String title,
  String description,
  double currentValue,
  double threshold,
  String unit,
  String changedAt,
  String lastNotifiedAt
) {

  public ServerAlert toAlert() {
    return new ServerAlert(
      metricName,
      severity,
      title,
      description,
      currentValue,
      threshold,
      unit
    );
  }
}
