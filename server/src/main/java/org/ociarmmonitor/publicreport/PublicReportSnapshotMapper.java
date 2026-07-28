package org.ociarmmonitor.publicreport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.ociarmmonitor.cost.CostDaily;
import org.ociarmmonitor.notification.DailyReportData;
import org.ociarmmonitor.oci.SyncResult;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.ociarmmonitor.traffic.TrafficDaily;
import org.springframework.stereotype.Component;

@Component
public class PublicReportSnapshotMapper {

  private static final Pattern OCID_PATTERN = Pattern.compile(
    "(?i)ocid1\\.[a-z0-9._-]+"
  );
  private static final Pattern IPV4_PATTERN = Pattern.compile(
    "(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])"
  );
  private static final Pattern IPV6_PATTERN = Pattern.compile(
    "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])"
  );

  public PublicReportPayload map(DailyReportData data) {
    return new PublicReportPayload(
      "OCI ARM Monitor 每日明细",
      data.context().reportAt().toString(),
      data.context().localDate().toString(),
      data.context().zoneId().getId(),
      instances(data),
      host(data.hostStatus()),
      IntStream.range(0, data.alerts().size())
        .mapToObj(index -> new PublicReportPayload.AlertDetail(
          "alert-" + (index + 1),
          safe(data.alerts().get(index).metricName()),
          safe(data.alerts().get(index).severity()),
          safe(data.alerts().get(index).title()),
          safe(data.alerts().get(index).description()),
          data.alerts().get(index).currentValue(),
          data.alerts().get(index).threshold(),
          safe(data.alerts().get(index).unit())
        ))
        .toList(),
      sync(data.latestSync()),
      costs(data),
      traffic(data)
    );
  }

  private PublicReportPayload.InstanceOverview instances(DailyReportData data) {
    long runningCount = data.instances().stream()
      .filter(instance -> "RUNNING".equalsIgnoreCase(instance.lifecycleState()))
      .count();
    long stoppedCount = data.instances().stream()
      .filter(instance -> "STOPPED".equalsIgnoreCase(instance.lifecycleState()))
      .count();
    List<PublicReportPayload.InstanceDetail> details = IntStream.range(0, data.instances().size())
      .mapToObj(index -> new PublicReportPayload.InstanceDetail(
        "instance-" + (index + 1),
        safe(data.instances().get(index).displayName()),
        safe(data.instances().get(index).lifecycleState()),
        optionalValue(data.instances().get(index).cpuUtilization()),
        optionalValue(data.instances().get(index).memoryUtilization())
      ))
      .toList();
    return new PublicReportPayload.InstanceOverview(
      details.size(),
      Math.toIntExact(runningCount),
      Math.toIntExact(stoppedCount),
      details.size() - Math.toIntExact(runningCount) - Math.toIntExact(stoppedCount),
      details
    );
  }

  private PublicReportPayload.HostStatus host(ServerStatusSnapshot host) {
    if (host == null) {
      return null;
    }
    return new PublicReportPayload.HostStatus(
      host.sampledAt(),
      host.cpuUsagePercent(),
      host.loadOne(),
      host.loadFive(),
      host.loadFifteen(),
      host.memoryTotalBytes(),
      host.memoryAvailableBytes(),
      host.memoryUsagePercent(),
      host.swapTotalBytes(),
      host.swapFreeBytes(),
      host.swapUsagePercent(),
      host.diskTotalBytes(),
      host.diskUsableBytes(),
      host.diskUsagePercent(),
      host.networkRxBytes(),
      host.networkTxBytes(),
      host.networkRxBytesPerSecond(),
      host.networkTxBytesPerSecond(),
      host.uptimeSeconds(),
      host.processUptimeSeconds(),
      host.jvmMemoryUsedBytes(),
      host.jvmMemoryMaxBytes(),
      host.jvmThreadCount(),
      host.databaseSizeBytes()
    );
  }

  private PublicReportPayload.SyncStatus sync(SyncResult sync) {
    if (sync == null) {
      return null;
    }
    return new PublicReportPayload.SyncStatus(
      safe(sync.status()),
      sync.startedAt(),
      sync.finishedAt(),
      sync.instanceCount(),
      sync.metricCount(),
      sync.trafficCount(),
      sync.costCount()
    );
  }

  private PublicReportPayload.CostDetail costs(DailyReportData data) {
    Map<String, PublicReportPayload.DailyCost> totals = new LinkedHashMap<>();
    for (CostDaily daily : data.costs().daily()) {
      String serviceName = safe(daily.serviceName());
      String currency = safe(daily.currency());
      String key = daily.statDate() + "\u0000" + serviceName + "\u0000" + currency;
      PublicReportPayload.DailyCost existing = totals.get(key);
      double costAmount = daily.costAmount() + (existing == null ? 0 : existing.costAmount());
      totals.put(key, new PublicReportPayload.DailyCost(serviceName, daily.statDate(), costAmount, currency));
    }
    return new PublicReportPayload.CostDetail(
      data.costs().ociCostThisMonth(),
      data.costs().manualCostThisMonth(),
      data.costs().totalCostThisMonth(),
      data.costs().estimatedMonthEndCost(),
      safe(data.costs().currency()),
      List.copyOf(totals.values()),
      IntStream.range(0, data.costs().manualCosts().size())
        .mapToObj(index -> new PublicReportPayload.ManualCostDetail(
          "manual-cost-" + (index + 1),
          safe(data.costs().manualCosts().get(index).costName()),
          safe(data.costs().manualCosts().get(index).category()),
          data.costs().manualCosts().get(index).amount(),
          safe(data.costs().manualCosts().get(index).currency()),
          data.costs().manualCosts().get(index).occurredOn()
        ))
        .toList()
    );
  }

  private PublicReportPayload.TrafficDetail traffic(DailyReportData data) {
    Map<String, PublicReportPayload.DailyTraffic> totals = new LinkedHashMap<>();
    for (TrafficDaily daily : data.traffic().daily()) {
      PublicReportPayload.DailyTraffic existing = totals.get(daily.statDate());
      totals.put(daily.statDate(), new PublicReportPayload.DailyTraffic(
        daily.statDate(),
        daily.ingressGb() + (existing == null ? 0 : existing.ingressGb()),
        daily.egressGb() + (existing == null ? 0 : existing.egressGb())
      ));
    }
    return new PublicReportPayload.TrafficDetail(
      data.traffic().ingressGbThisMonth(),
      data.traffic().egressGbThisMonth(),
      data.traffic().outboundQuotaGb(),
      data.traffic().outboundUsagePercent(),
      List.copyOf(totals.values())
    );
  }

  private Double optionalValue(OptionalDouble value) {
    return value.isPresent() ? value.getAsDouble() : null;
  }

  private String safe(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.replaceAll("[\\p{Cntrl}\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
    normalized = OCID_PATTERN.matcher(normalized).replaceAll("[已隐藏]");
    normalized = IPV4_PATTERN.matcher(normalized).replaceAll("[已隐藏]");
    return IPV6_PATTERN.matcher(normalized).replaceAll("[已隐藏]");
  }
}
