package org.ociarmmonitor.serverstatus;

public record ServerMetricPoint(
  String sampledAt,
  double cpuUsagePercent,
  double memoryUsagePercent,
  double diskUsagePercent,
  double networkRxBytesPerSecond,
  double networkTxBytesPerSecond
) {
}
