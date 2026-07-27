package org.ociarmmonitor.oci;

public record SyncResult(
  String status,
  String message,
  String startedAt,
  String finishedAt,
  int instanceCount,
  int metricCount,
  int trafficCount,
  int costCount
) {
}
