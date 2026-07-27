package org.ociarmmonitor.traffic;

public record TrafficDaily(
  String instanceId,
  String statDate,
  double ingressGb,
  double egressGb
) {
}
