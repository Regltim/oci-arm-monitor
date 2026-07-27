package org.ociarmmonitor.dashboard;

import java.util.List;

public record DashboardSummary(
  double ociCostThisMonth,
  double manualCostThisMonth,
  double estimatedMonthEndCost,
  double averageCpuUtilization,
  double averageMemoryUtilization,
  double egressGbThisMonth,
  int instanceCount,
  List<QuotaUsage> quotaUsages,
  List<RiskAlert> riskAlerts
) {
}
