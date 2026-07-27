package org.ociarmmonitor.cost;

import java.util.List;

public record CostSummary(
  double ociCostThisMonth,
  double manualCostThisMonth,
  double totalCostThisMonth,
  double estimatedMonthEndCost,
  String currency,
  List<CostDaily> daily,
  List<ManualCost> manualCosts
) {
}
