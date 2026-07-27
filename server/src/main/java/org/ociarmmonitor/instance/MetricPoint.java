package org.ociarmmonitor.instance;

public record MetricPoint(
  String instanceId,
  String metricName,
  double value,
  String unit,
  String sampledAt
) {
}
