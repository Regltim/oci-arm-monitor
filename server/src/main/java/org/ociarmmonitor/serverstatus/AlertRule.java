package org.ociarmmonitor.serverstatus;

public record AlertRule(
  String id,
  String metricName,
  String operator,
  double threshold,
  String severity,
  boolean enabled,
  String createdAt,
  String updatedAt
) {
}
