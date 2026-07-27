package org.ociarmmonitor.serverstatus;

public record ServerAlert(
  String metricName,
  String severity,
  String title,
  String description,
  double currentValue,
  double threshold,
  String unit
) {
}
