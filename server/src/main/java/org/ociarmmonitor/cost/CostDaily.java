package org.ociarmmonitor.cost;

public record CostDaily(
  String serviceName,
  String resourceId,
  String statDate,
  double usageAmount,
  String usageUnit,
  double costAmount,
  String currency
) {
}
