package org.ociarmmonitor.serverstatus;

public record ServerStatusSnapshot(
  String sampledAt,
  double cpuUsagePercent,
  double loadOne,
  double loadFive,
  double loadFifteen,
  long memoryTotalBytes,
  long memoryAvailableBytes,
  double memoryUsagePercent,
  long swapTotalBytes,
  long swapFreeBytes,
  double swapUsagePercent,
  long diskTotalBytes,
  long diskUsableBytes,
  double diskUsagePercent,
  long networkRxBytes,
  long networkTxBytes,
  double networkRxBytesPerSecond,
  double networkTxBytesPerSecond,
  long uptimeSeconds,
  long processUptimeSeconds,
  long jvmMemoryUsedBytes,
  long jvmMemoryMaxBytes,
  int jvmThreadCount,
  long databaseSizeBytes
) {
}
