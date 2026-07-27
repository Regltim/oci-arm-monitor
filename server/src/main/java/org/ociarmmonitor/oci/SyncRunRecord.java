package org.ociarmmonitor.oci;

public record SyncRunRecord(
  String id,
  String syncType,
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
