package org.ociarmmonitor.traffic;

import java.util.List;

public record TrafficSummary(
  double ingressGbThisMonth,
  double egressGbThisMonth,
  double outboundQuotaGb,
  double outboundUsagePercent,
  List<TrafficDaily> daily
) {
}
