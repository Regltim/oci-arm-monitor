package org.ociarmmonitor.notification;

import java.util.List;
import java.util.OptionalDouble;
import org.ociarmmonitor.cost.CostSummary;
import org.ociarmmonitor.oci.SyncResult;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.ociarmmonitor.traffic.TrafficSummary;

public record DailyReportData(
  DailyReportContext context,
  List<InstanceStatus> instances,
  ServerStatusSnapshot hostStatus,
  List<ServerAlert> alerts,
  SyncResult latestSync,
  SyncResult latestSuccessfulSync,
  CostSummary costs,
  TrafficSummary traffic
) {

  public record InstanceStatus(
    String displayName,
    String lifecycleState,
    OptionalDouble cpuUtilization,
    OptionalDouble memoryUtilization
  ) {
  }
}
