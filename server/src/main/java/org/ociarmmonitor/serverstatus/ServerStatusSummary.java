package org.ociarmmonitor.serverstatus;

import java.util.List;

public record ServerStatusSummary(
  ServerStatusSnapshot current,
  List<ServerMetricPoint> history,
  List<ServerAlert> alerts,
  ServerSystemInfo systemInfo
) {
}
