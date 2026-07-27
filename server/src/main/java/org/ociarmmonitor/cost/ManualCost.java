package org.ociarmmonitor.cost;

public record ManualCost(
  String id,
  String costName,
  String category,
  double amount,
  String currency,
  String occurredOn,
  String note,
  String createdAt
) {
}
