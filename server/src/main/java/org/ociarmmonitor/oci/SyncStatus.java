package org.ociarmmonitor.oci;

public record SyncStatus(
  boolean configured,
  boolean hasData,
  String lastStatus,
  String lastMessage,
  String lastStartedAt,
  String lastFinishedAt,
  int instanceCount,
  int metricCount,
  int trafficCount,
  int costCount
) {
}
