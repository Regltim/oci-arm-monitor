package org.ociarmmonitor.config;

public record FreeQuota(
  double ampereOcpuHours,
  double ampereMemoryGbHours,
  double blockVolumeGb,
  double outboundDataTransferGb,
  double monitoringIngestionPoints,
  double monitoringRetrievalPoints,
  String updatedAt
) {
}
