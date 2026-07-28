package org.ociarmmonitor.notification;

import java.util.List;
import org.ociarmmonitor.cost.CostService;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.oci.SyncResult;
import org.ociarmmonitor.oci.SyncRunRepository;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerAlertService;
import org.ociarmmonitor.serverstatus.ServerStatusRepository;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.ociarmmonitor.traffic.TrafficService;
import org.springframework.stereotype.Service;

@Service
public class DailyReportDataProvider {

  private final CloudInstanceRepository instanceRepository;
  private final MetricRepository metricRepository;
  private final ServerStatusRepository serverStatusRepository;
  private final ServerAlertService serverAlertService;
  private final SyncRunRepository syncRunRepository;
  private final CostService costService;
  private final TrafficService trafficService;

  public DailyReportDataProvider(
    CloudInstanceRepository instanceRepository,
    MetricRepository metricRepository,
    ServerStatusRepository serverStatusRepository,
    ServerAlertService serverAlertService,
    SyncRunRepository syncRunRepository,
    CostService costService,
    TrafficService trafficService
  ) {
    this.instanceRepository = instanceRepository;
    this.metricRepository = metricRepository;
    this.serverStatusRepository = serverStatusRepository;
    this.serverAlertService = serverAlertService;
    this.syncRunRepository = syncRunRepository;
    this.costService = costService;
    this.trafficService = trafficService;
  }

  public DailyReportData load(DailyReportContext context) {
    SyncResult latestSync = syncRunRepository.latest().orElse(null);
    SyncResult latestSuccessfulSync = syncRunRepository.latestSuccessful().orElse(null);
    ServerStatusSnapshot hostStatus = serverStatusRepository.latest().orElse(null);
    List<ServerAlert> alerts = hostStatus == null
      ? List.of()
      : serverAlertService.evaluate(hostStatus, java.util.Optional.ofNullable(latestSync));
    List<DailyReportData.InstanceStatus> instances = instanceRepository.findAll().stream()
      .map(instance -> new DailyReportData.InstanceStatus(
        instance.displayName(),
        instance.lifecycleState(),
        metricRepository.latestOptional(instance.id(), "cpu_utilization"),
        metricRepository.latestOptional(instance.id(), "memory_utilization")
      ))
      .toList();
    return new DailyReportData(
      context,
      instances,
      hostStatus,
      alerts,
      latestSync,
      latestSuccessfulSync,
      costService.getSummary(context.yearMonth(), context.localDate()),
      trafficService.getSummary(context.yearMonth())
    );
  }
}
