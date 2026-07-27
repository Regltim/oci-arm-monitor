package org.ociarmmonitor.dashboard;

public record QuotaUsage(
  String name,
  double used,
  double quota,
  String unit,
  double percent
) {
}
