package org.ociarmmonitor.publicreport;

import java.util.List;

public record PublicReportPayload(
  String title,
  String generatedAt,
  String reportDate,
  String zoneId,
  InstanceOverview instances,
  HostStatus host,
  List<AlertDetail> alerts,
  SyncStatus sync,
  CostDetail costs,
  TrafficDetail traffic
) {

  public record InstanceOverview(
    int totalCount,
    int runningCount,
    int stoppedCount,
    int otherCount,
    List<InstanceDetail> details
  ) {
  }

  public record InstanceDetail(
    String key,
    String displayName,
    String lifecycleState,
    Double cpuUtilization,
    Double memoryUtilization
  ) {
  }

  public record HostStatus(
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

  public record AlertDetail(
    String key,
    String metricName,
    String severity,
    String title,
    String description,
    double currentValue,
    double threshold,
    String unit
  ) {
  }

  public record SyncStatus(
    String status,
    String startedAt,
    String finishedAt,
    int instanceCount,
    int metricCount,
    int trafficCount,
    int costCount
  ) {
  }

  public record CostDetail(
    double ociCostThisMonth,
    double manualCostThisMonth,
    double totalCostThisMonth,
    double estimatedMonthEndCost,
    String currency,
    List<DailyCost> daily,
    List<ManualCostDetail> manualCosts
  ) {
  }

  public record DailyCost(
    String serviceName,
    String statDate,
    double costAmount,
    String currency
  ) {
  }

  public record ManualCostDetail(
    String key,
    String costName,
    String category,
    double amount,
    String currency,
    String occurredOn
  ) {
  }

  public record TrafficDetail(
    double ingressGbThisMonth,
    double egressGbThisMonth,
    double outboundQuotaGb,
    double outboundUsagePercent,
    List<DailyTraffic> daily
  ) {
  }

  public record DailyTraffic(
    String statDate,
    double ingressGb,
    double egressGb
  ) {
  }
}
