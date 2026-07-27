package org.ociarmmonitor.instance;

public record InstanceOverview(
  CloudInstance instance,
  double cpuUtilization,
  double memoryUtilization,
  double egressGbToday,
  double costAmountThisMonth
) {
}
