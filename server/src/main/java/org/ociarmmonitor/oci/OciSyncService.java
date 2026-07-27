package org.ociarmmonitor.oci;

import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InstanceShapeConfig;
import com.oracle.bmc.core.model.Vnic;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import com.oracle.bmc.core.responses.ListVnicAttachmentsResponse;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.monitoring.model.AggregatedDatapoint;
import com.oracle.bmc.monitoring.model.MetricData;
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;
import com.oracle.bmc.usageapi.UsageapiClient;
import com.oracle.bmc.usageapi.model.RequestSummarizedUsagesDetails;
import com.oracle.bmc.usageapi.model.UsageAggregation;
import com.oracle.bmc.usageapi.model.UsageSummary;
import com.oracle.bmc.usageapi.requests.RequestSummarizedUsagesRequest;
import com.oracle.bmc.usageapi.responses.RequestSummarizedUsagesResponse;
import org.ociarmmonitor.cost.CostDaily;
import org.ociarmmonitor.cost.CostRepository;
import org.ociarmmonitor.config.OciSettings;
import org.ociarmmonitor.config.OciSettingsProvider;
import org.ociarmmonitor.instance.CloudInstance;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricPoint;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.traffic.TrafficDaily;
import org.ociarmmonitor.traffic.TrafficRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class OciSyncService {

  private static final Logger LOGGER = LoggerFactory.getLogger(OciSyncService.class);

  private final OciSettingsProvider ociSettingsProvider;
  private final OciClientFactory ociClientFactory;
  private final CloudInstanceRepository cloudInstanceRepository;
  private final MetricRepository metricRepository;
  private final TrafficRepository trafficRepository;
  private final CostRepository costRepository;
  private final SyncRunRepository syncRunRepository;
  private final TaskExecutor syncTaskExecutor;
  private final AtomicBoolean syncRunning = new AtomicBoolean(false);

  public OciSyncService(
    OciSettingsProvider ociSettingsProvider,
    OciClientFactory ociClientFactory,
    CloudInstanceRepository cloudInstanceRepository,
    MetricRepository metricRepository,
    TrafficRepository trafficRepository,
    CostRepository costRepository,
    SyncRunRepository syncRunRepository,
    @Qualifier("syncTaskExecutor") TaskExecutor syncTaskExecutor
  ) {
    this.ociSettingsProvider = ociSettingsProvider;
    this.ociClientFactory = ociClientFactory;
    this.cloudInstanceRepository = cloudInstanceRepository;
    this.metricRepository = metricRepository;
    this.trafficRepository = trafficRepository;
    this.costRepository = costRepository;
    this.syncRunRepository = syncRunRepository;
    this.syncTaskExecutor = syncTaskExecutor;
  }

  public SyncResult syncResources() {
    return syncResources("FULL");
  }

  public SyncResult syncScheduledResources() {
    return syncResources("SCHEDULED");
  }

  public SyncResult syncResources(String syncType) {
    if (!syncRunning.compareAndSet(false, true)) {
      return latestRunningResult();
    }

    String syncRunId = "";
    try {
      syncRunId = syncRunRepository.start(syncType);
      SyncResult startedResult = syncRunRepository.findById(syncRunId)
        .orElseGet(() -> runningResult("同步任务已开始，正在后台拉取 OCI 数据。"));
      String createdSyncRunId = syncRunId;
      syncTaskExecutor.execute(() -> runSync(createdSyncRunId));
      return startedResult;
    } catch (RuntimeException exception) {
      syncRunning.set(false);
      if (syncRunId.isBlank()) {
        String now = Instant.now().toString();
        return new SyncResult("FAILED", "后台同步任务启动失败：" + cleanErrorMessage(exception), now, now, 0, 0, 0, 0);
      }
      return syncRunRepository.finish(syncRunId, "FAILED", "后台同步任务启动失败：" + cleanErrorMessage(exception), 0, 0, 0, 0);
    }
  }

  public SyncStatus getStatus() {
    OciSettings settings = ociSettingsProvider.getSettings();
    SyncResult latest = syncRunRepository.latest().orElse(null);
    boolean hasData = !cloudInstanceRepository.findAll().isEmpty();
    if (latest == null) {
      return new SyncStatus(ociSettingsProvider.isConfigured(settings), hasData, "NEVER_SYNCED", "尚未执行同步。", "", "", 0, 0, 0, 0);
    }
    return new SyncStatus(
      ociSettingsProvider.isConfigured(settings),
      hasData,
      latest.status(),
      latest.message(),
      latest.startedAt(),
      latest.finishedAt(),
      latest.instanceCount(),
      latest.metricCount(),
      latest.trafficCount(),
      latest.costCount()
    );
  }

  private void runSync(String syncRunId) {
    try {
      updateProgress(syncRunId, "正在检查后端 OCI 配置。", 0, 0, 0, 0);
      OciSettings settings = ociSettingsProvider.getSettings();
      if (!ociSettingsProvider.isConfigured(settings)) {
        syncRunRepository.finish(syncRunId, "NOT_CONFIGURED", "后端 OCI 环境变量未配置完整，未同步任何业务数据。", 0, 0, 0, 0);
        return;
      }

      updateProgress(syncRunId, "正在创建 OCI SDK 认证 Provider。", 0, 0, 0, 0);
      BasicAuthenticationDetailsProvider provider = ociClientFactory.createProvider(settings);
      SyncCounts counts = syncAll(syncRunId, settings, provider);
      syncRunRepository.finish(
        syncRunId,
        "SUCCESS",
        "OCI 真实数据同步完成。",
        counts.instanceCount(),
        counts.metricCount(),
        counts.trafficCount(),
        counts.costCount()
      );
      LOGGER.info("OCI sync run {} finished successfully", syncRunId);
    } catch (Throwable exception) {
      LOGGER.error("OCI sync run {} failed", syncRunId, exception);
      syncRunRepository.finish(syncRunId, "FAILED", cleanErrorMessage(exception), 0, 0, 0, 0);
      if (isFatalJvmError(exception)) {
        throw exception;
      }
    } finally {
      syncRunning.set(false);
    }
  }

  private SyncCounts syncAll(String syncRunId, OciSettings settings, BasicAuthenticationDetailsProvider provider) {
    updateProgress(syncRunId, "正在创建 OCI Compute、VNIC、Monitoring、Usage API 客户端。", 0, 0, 0, 0);
    ComputeClient computeClient = ociClientFactory.computeClient(provider, settings.region());
    VirtualNetworkClient virtualNetworkClient = ociClientFactory.virtualNetworkClient(provider, settings.region());
    MonitoringClient monitoringClient = ociClientFactory.monitoringClient(provider, settings.region());
    UsageapiClient usageapiClient = ociClientFactory.usageapiClient(provider, settings.region());

    updateProgress(syncRunId, "正在读取 Compute 实例列表。", 0, 0, 0, 0);
    List<Instance> instances = listInstances(computeClient, settings.compartmentOcid());
    updateProgress(syncRunId, "已读取 %d 台实例，正在读取主网卡和公网 IP。".formatted(instances.size()), instances.size(), 0, 0, 0);

    Map<String, OciVnicInfo> vnicByInstanceId = listPrimaryVnics(computeClient, virtualNetworkClient, settings.compartmentOcid(), instances);
    updateProgress(syncRunId, "已读取 %d 个主网卡，正在写入实例快照。".formatted(vnicByInstanceId.size()), instances.size(), 0, 0, 0);

    List<String> instanceIds = new ArrayList<>();
    for (Instance instance : instances) {
      OciVnicInfo vnicInfo = vnicByInstanceId.get(instance.getId());
      cloudInstanceRepository.save(toCloudInstance(instance, settings.region(), vnicInfo));
      instanceIds.add(instance.getId());
    }
    cloudInstanceRepository.deleteByIds(instanceIds);

    updateProgress(syncRunId, "实例快照已写入，正在同步近 48 小时 CPU 和内存指标。", instances.size(), 0, 0, 0);
    Instant metricStart = Instant.now().minus(48, ChronoUnit.HOURS);
    metricRepository.deleteSince(metricStart.toString());
    int metricCount = syncInstanceMetrics(monitoringClient, settings.compartmentOcid(), instances, metricStart);
    updateProgress(syncRunId, "CPU 和内存指标已同步，正在同步本月流量。", instances.size(), metricCount, 0, 0);

    LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
    LocalDate monthStart = todayUtc.withDayOfMonth(1);
    trafficRepository.deleteSince(monthStart.toString());
    int trafficCount = syncTrafficMetrics(monitoringClient, settings.compartmentOcid(), vnicByInstanceId, monthStart);
    updateProgress(syncRunId, "本月流量已同步，正在同步 Usage API 费用。", instances.size(), metricCount, trafficCount, 0);

    int costCount = syncCostUsage(usageapiClient, settings, resolveTenantId(settings, provider), monthStart, todayUtc);
    updateProgress(syncRunId, "费用数据已同步，正在收尾。", instances.size(), metricCount, trafficCount, costCount);

    return new SyncCounts(instances.size(), metricCount, trafficCount, costCount);
  }

  private SyncResult latestRunningResult() {
    Optional<SyncResult> latest = syncRunRepository.latest();
    return latest
      .filter(result -> "RUNNING".equals(result.status()))
      .orElseGet(() -> runningResult("已有同步任务正在后台执行，请稍后查看同步状态。"));
  }

  private SyncResult runningResult(String message) {
    return new SyncResult("RUNNING", message, Instant.now().toString(), null, 0, 0, 0, 0);
  }

  private void updateProgress(String syncRunId, String message, int instanceCount, int metricCount, int trafficCount, int costCount) {
    LOGGER.info("OCI sync run {} progress: {}", syncRunId, message);
    syncRunRepository.updateProgress(syncRunId, message, instanceCount, metricCount, trafficCount, costCount);
  }

  private List<Instance> listInstances(ComputeClient computeClient, String compartmentOcid) {
    List<Instance> instances = new ArrayList<>();
    String nextPage = null;
    do {
      ListInstancesResponse response = computeClient.listInstances(ListInstancesRequest.builder()
        .compartmentId(compartmentOcid)
        .limit(100)
        .page(nextPage)
        .build());
      instances.addAll(response.getItems());
      nextPage = response.getOpcNextPage();
    } while (nextPage != null && !nextPage.isBlank());
    return instances;
  }

  private Map<String, OciVnicInfo> listPrimaryVnics(
    ComputeClient computeClient,
    VirtualNetworkClient virtualNetworkClient,
    String compartmentOcid,
    List<Instance> instances
  ) {
    Map<String, OciVnicInfo> result = new HashMap<>();
    for (Instance instance : instances) {
      String nextPage = null;
      do {
        ListVnicAttachmentsResponse response = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
          .compartmentId(compartmentOcid)
          .instanceId(instance.getId())
          .limit(50)
          .page(nextPage)
          .build());
        for (VnicAttachment attachment : response.getItems()) {
          if (attachment.getVnicId() == null || attachment.getVnicId().isBlank()) {
            continue;
          }
          Vnic vnic = virtualNetworkClient.getVnic(GetVnicRequest.builder().vnicId(attachment.getVnicId()).build()).getVnic();
          if (Boolean.TRUE.equals(vnic.getIsPrimary()) || !result.containsKey(instance.getId())) {
            result.put(instance.getId(), new OciVnicInfo(instance.getId(), vnic.getId(), vnic.getPublicIp(), vnic.getPrivateIp()));
          }
        }
        nextPage = response.getOpcNextPage();
      } while (nextPage != null && !nextPage.isBlank());
    }
    return result;
  }

  private CloudInstance toCloudInstance(Instance instance, String region, OciVnicInfo vnicInfo) {
    InstanceShapeConfig shapeConfig = instance.getShapeConfig();
    double ocpus = shapeConfig == null || shapeConfig.getOcpus() == null ? 0 : shapeConfig.getOcpus();
    double memoryGb = shapeConfig == null || shapeConfig.getMemoryInGBs() == null ? 0 : shapeConfig.getMemoryInGBs();
    return new CloudInstance(
      instance.getId(),
      blankToDefault(instance.getDisplayName(), instance.getId()),
      blankToDefault(instance.getRegion(), region),
      instance.getCompartmentId(),
      blankToDefault(instance.getShape(), "-"),
      instance.getLifecycleState() == null ? "UNKNOWN" : instance.getLifecycleState().getValue(),
      ocpus,
      memoryGb,
      0,
      vnicInfo == null ? "" : blankToDefault(vnicInfo.publicIp(), ""),
      vnicInfo == null ? "" : blankToDefault(vnicInfo.privateIp(), ""),
      toInstantText(instance.getTimeCreated()),
      Instant.now().toString()
    );
  }

  private int syncInstanceMetrics(MonitoringClient monitoringClient, String compartmentOcid, List<Instance> instances, Instant startTime) {
    int count = 0;
    Instant endTime = Instant.now();
    for (Instance instance : instances) {
      count += syncMetricSeries(monitoringClient, compartmentOcid, "oci_computeagent", "CpuUtilization", "cpu_utilization", "%", instance.getId(), startTime, endTime, "mean");
      count += syncMetricSeries(monitoringClient, compartmentOcid, "oci_computeagent", "MemoryUtilization", "memory_utilization", "%", instance.getId(), startTime, endTime, "mean");
    }
    return count;
  }

  private int syncTrafficMetrics(
    MonitoringClient monitoringClient,
    String compartmentOcid,
    Map<String, OciVnicInfo> vnicByInstanceId,
    LocalDate monthStart
  ) {
    Map<String, TrafficAccumulator> trafficByInstanceAndDate = new HashMap<>();
    Instant start = monthStart.atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant end = Instant.now();
    for (OciVnicInfo vnicInfo : vnicByInstanceId.values()) {
      collectTrafficMetric(monitoringClient, compartmentOcid, vnicInfo, "VnicFromNetworkBytes", true, start, end, trafficByInstanceAndDate);
      collectTrafficMetric(monitoringClient, compartmentOcid, vnicInfo, "VnicToNetworkBytes", false, start, end, trafficByInstanceAndDate);
    }
    for (TrafficAccumulator accumulator : trafficByInstanceAndDate.values()) {
      trafficRepository.save(new TrafficDaily(accumulator.instanceId(), accumulator.statDate(), accumulator.ingressGb(), accumulator.egressGb()));
    }
    return trafficByInstanceAndDate.size();
  }

  private int syncCostUsage(
    UsageapiClient usageapiClient,
    OciSettings settings,
    String tenantId,
    LocalDate monthStart,
    LocalDate usageEndExclusive
  ) {
    if (!usageEndExclusive.isAfter(monthStart)) {
      LOGGER.info("Skip Usage API cost sync because no completed UTC day is available in current month.");
      return 0;
    }
    String resolvedTenantId = blankToDefault(settings.tenancyOcid(), tenantId);
    if (resolvedTenantId.isBlank()) {
      LOGGER.info("Skip Usage API cost sync because tenant OCID is not configured.");
      return 0;
    }
    RequestSummarizedUsagesDetails details = RequestSummarizedUsagesDetails.builder()
      .tenantId(resolvedTenantId)
      .timeUsageStarted(toUtcStartOfDay(monthStart))
      .timeUsageEnded(toUtcStartOfDay(usageEndExclusive))
      .granularity(RequestSummarizedUsagesDetails.Granularity.Daily)
      .queryType(RequestSummarizedUsagesDetails.QueryType.Cost)
      .isAggregateByTime(false)
      .groupBy(List.of("service", "resourceId"))
      .compartmentDepth(BigDecimal.valueOf(6))
      .build();

    int count = 0;
    String nextPage = null;
    do {
      RequestSummarizedUsagesResponse response = usageapiClient.requestSummarizedUsages(RequestSummarizedUsagesRequest.builder()
        .requestSummarizedUsagesDetails(details)
        .limit(1000)
        .page(nextPage)
        .build());
      UsageAggregation usageAggregation = response.getUsageAggregation();
      if (usageAggregation != null && usageAggregation.getItems() != null) {
        for (UsageSummary usageSummary : usageAggregation.getItems()) {
          costRepository.save(toCostDaily(usageSummary));
          count += 1;
        }
      }
      nextPage = response.getOpcNextPage();
    } while (nextPage != null && !nextPage.isBlank());
    return count;
  }

  private String resolveTenantId(OciSettings settings, BasicAuthenticationDetailsProvider provider) {
    if (!settings.tenancyOcid().isBlank()) {
      return settings.tenancyOcid();
    }
    if (provider instanceof AuthenticationDetailsProvider authenticationDetailsProvider) {
      return authenticationDetailsProvider.getTenantId();
    }
    return "";
  }

  private Date toUtcStartOfDay(LocalDate date) {
    return Date.from(date.atStartOfDay().toInstant(ZoneOffset.UTC));
  }

  private int syncMetricSeries(
    MonitoringClient monitoringClient,
    String compartmentOcid,
    String namespace,
    String ociMetricName,
    String localMetricName,
    String unit,
    String resourceId,
    Instant startTime,
    Instant endTime,
    String aggregate
  ) {
    String query = "%s[1h]{resourceId = \"%s\"}.%s()".formatted(ociMetricName, resourceId, aggregate);
    List<MetricData> metricData = summarizeMetricData(monitoringClient, compartmentOcid, namespace, query, startTime, endTime);
    int count = 0;
    for (MetricData data : metricData) {
      if (data.getAggregatedDatapoints() == null) {
        continue;
      }
      for (AggregatedDatapoint datapoint : data.getAggregatedDatapoints()) {
        if (datapoint.getValue() == null || datapoint.getTimestamp() == null) {
          continue;
        }
        metricRepository.insert(new MetricPoint(resourceId, localMetricName, datapoint.getValue(), unit, toInstantText(datapoint.getTimestamp())));
        count += 1;
      }
    }
    return count;
  }

  private void collectTrafficMetric(
    MonitoringClient monitoringClient,
    String compartmentOcid,
    OciVnicInfo vnicInfo,
    String metricName,
    boolean ingress,
    Instant startTime,
    Instant endTime,
    Map<String, TrafficAccumulator> trafficByInstanceAndDate
  ) {
    String query = "%s[1d]{resourceId = \"%s\"}.sum()".formatted(metricName, vnicInfo.vnicId());
    List<MetricData> metricData = summarizeMetricData(monitoringClient, compartmentOcid, "oci_vcn", query, startTime, endTime);
    for (MetricData data : metricData) {
      if (data.getAggregatedDatapoints() == null) {
        continue;
      }
      for (AggregatedDatapoint datapoint : data.getAggregatedDatapoints()) {
        if (datapoint.getValue() == null || datapoint.getTimestamp() == null) {
          continue;
        }
        String statDate = datapoint.getTimestamp().toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        String key = vnicInfo.instanceId() + ":" + statDate;
        TrafficAccumulator accumulator = trafficByInstanceAndDate.computeIfAbsent(key, ignored -> new TrafficAccumulator(vnicInfo.instanceId(), statDate));
        double gigabytes = datapoint.getValue() / 1024 / 1024 / 1024;
        if (ingress) {
          accumulator.addIngress(gigabytes);
        } else {
          accumulator.addEgress(gigabytes);
        }
      }
    }
  }

  private List<MetricData> summarizeMetricData(
    MonitoringClient monitoringClient,
    String compartmentOcid,
    String namespace,
    String query,
    Instant startTime,
    Instant endTime
  ) {
    return monitoringClient.summarizeMetricsData(SummarizeMetricsDataRequest.builder()
      .compartmentId(compartmentOcid)
      .summarizeMetricsDataDetails(SummarizeMetricsDataDetails.builder()
        .namespace(namespace)
        .query(query)
        .startTime(Date.from(startTime))
        .endTime(Date.from(endTime))
        .resolution("1h")
        .build())
      .build())
      .getItems();
  }

  private CostDaily toCostDaily(UsageSummary usageSummary) {
    String resourceId = blankToDefault(blankToDefault(usageSummary.getResourceId(), usageSummary.getResourceName()), "-");
    return new CostDaily(
      blankToDefault(usageSummary.getService(), "-"),
      resourceId,
      toDateText(usageSummary.getTimeUsageStarted()),
      bigDecimalToDouble(usageSummary.getComputedQuantity()),
      blankToDefault(usageSummary.getUnit(), "-"),
      parseAmount(usageSummary.getAttributedCost()),
      blankToDefault(usageSummary.getCurrency(), "CNY")
    );
  }

  private String cleanErrorMessage(Throwable exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? "OCI 同步失败，请检查配置和 IAM 权限。" : message;
  }

  private boolean isFatalJvmError(Throwable exception) {
    return exception instanceof VirtualMachineError || exception instanceof ThreadDeath;
  }

  private String toInstantText(Date date) {
    return date == null ? "" : date.toInstant().toString();
  }

  private String toDateText(Date date) {
    return date == null ? LocalDate.now().toString() : date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  private double bigDecimalToDouble(BigDecimal value) {
    return value == null ? 0 : value.doubleValue();
  }

  private double parseAmount(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  private String blankToDefault(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private record SyncCounts(int instanceCount, int metricCount, int trafficCount, int costCount) {
  }

  private static class TrafficAccumulator {
    private final String instanceId;
    private final String statDate;
    private double ingressGb;
    private double egressGb;

    TrafficAccumulator(String instanceId, String statDate) {
      this.instanceId = instanceId;
      this.statDate = statDate;
    }

    String instanceId() {
      return instanceId;
    }

    String statDate() {
      return statDate;
    }

    double ingressGb() {
      return ingressGb;
    }

    double egressGb() {
      return egressGb;
    }

    void addIngress(double value) {
      ingressGb += value;
    }

    void addEgress(double value) {
      egressGb += value;
    }
  }
}
