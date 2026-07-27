package org.ociarmmonitor.dashboard;

import org.ociarmmonitor.config.FreeQuota;
import org.ociarmmonitor.config.FreeQuotaRepository;
import org.ociarmmonitor.cost.CostRepository;
import org.ociarmmonitor.cost.CostService;
import org.ociarmmonitor.cost.CostSummary;
import org.ociarmmonitor.cost.ManualCostRepository;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.traffic.TrafficRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {

  private final CloudInstanceRepository cloudInstanceRepository;
  private final MetricRepository metricRepository;
  private final TrafficRepository trafficRepository;
  private final FreeQuotaRepository freeQuotaRepository;
  private final CostRepository costRepository;
  private final ManualCostRepository manualCostRepository;
  private final CostService costService;

  public DashboardService(
    CloudInstanceRepository cloudInstanceRepository,
    MetricRepository metricRepository,
    TrafficRepository trafficRepository,
    FreeQuotaRepository freeQuotaRepository,
    CostRepository costRepository,
    ManualCostRepository manualCostRepository,
    CostService costService
  ) {
    this.cloudInstanceRepository = cloudInstanceRepository;
    this.metricRepository = metricRepository;
    this.trafficRepository = trafficRepository;
    this.freeQuotaRepository = freeQuotaRepository;
    this.costRepository = costRepository;
    this.manualCostRepository = manualCostRepository;
    this.costService = costService;
  }

  public DashboardSummary getSummary() {
    FreeQuota freeQuota = freeQuotaRepository.getQuota();
    CostSummary costSummary = costService.getSummary();
    double ocpuHours = cloudInstanceRepository.sumOcpus() * LocalDate.now().getDayOfMonth() * 24;
    double memoryGbHours = cloudInstanceRepository.sumMemoryGb() * LocalDate.now().getDayOfMonth() * 24;
    double blockVolumeGb = cloudInstanceRepository.sumBootVolumeGb();
    double egressGb = trafficRepository.sumEgressForCurrentMonth();

    List<QuotaUsage> quotaUsages = List.of(
      buildQuotaUsage("ARM OCPU 小时", ocpuHours, freeQuota.ampereOcpuHours(), "小时"),
      buildQuotaUsage("ARM 内存 GB 小时", memoryGbHours, freeQuota.ampereMemoryGbHours(), "GB 小时"),
      buildQuotaUsage("Block Volume", blockVolumeGb, freeQuota.blockVolumeGb(), "GB"),
      buildQuotaUsage("出站流量", egressGb, freeQuota.outboundDataTransferGb(), "GB")
    );

    return new DashboardSummary(
      costRepository.costForCurrentMonth(),
      manualCostRepository.costForCurrentMonth(),
      costSummary.estimatedMonthEndCost(),
      metricRepository.average("cpu_utilization"),
      metricRepository.average("memory_utilization"),
      egressGb,
      cloudInstanceRepository.findAll().size(),
      quotaUsages,
      buildRiskAlerts(quotaUsages)
    );
  }

  private QuotaUsage buildQuotaUsage(String name, double used, double quota, String unit) {
    double percent = quota == 0 ? 0 : used / quota * 100;
    return new QuotaUsage(name, used, quota, unit, percent);
  }

  private List<RiskAlert> buildRiskAlerts(List<QuotaUsage> quotaUsages) {
    List<RiskAlert> alerts = new ArrayList<>();
    for (QuotaUsage quotaUsage : quotaUsages) {
      if (quotaUsage.percent() >= 95) {
        alerts.add(new RiskAlert("danger", quotaUsage.name() + "接近上限", "当前已使用 %.1f%%，建议立即检查资源配置。".formatted(quotaUsage.percent())));
      } else if (quotaUsage.percent() >= 80) {
        alerts.add(new RiskAlert("warning", quotaUsage.name() + "使用偏高", "当前已使用 %.1f%%，建议关注后续增长趋势。".formatted(quotaUsage.percent())));
      }
    }
    if (alerts.isEmpty()) {
      alerts.add(new RiskAlert("success", "暂无免费额度风险", "当前已同步数据未触发超额预警，费用以 OCI Usage API 返回为准。"));
    }
    return alerts;
  }
}
