package org.ociarmmonitor.config;

import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import com.oracle.bmc.core.responses.ListVnicAttachmentsResponse;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.monitoring.model.MetricData;
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;
import com.oracle.bmc.monitoring.responses.SummarizeMetricsDataResponse;
import com.oracle.bmc.usageapi.UsageapiClient;
import com.oracle.bmc.usageapi.model.RequestSummarizedUsagesDetails;
import com.oracle.bmc.usageapi.model.UsageAggregation;
import com.oracle.bmc.usageapi.requests.RequestSummarizedUsagesRequest;
import com.oracle.bmc.usageapi.responses.RequestSummarizedUsagesResponse;
import org.ociarmmonitor.oci.OciClientFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OciDiagnosticsService {

  private static final String SUCCESS = "SUCCESS";
  private static final String WARNING = "WARNING";
  private static final String FAILED = "FAILED";
  private static final String SKIPPED = "SKIPPED";

  private final OciSettingsProvider ociSettingsProvider;
  private final OciClientFactory ociClientFactory;

  public OciDiagnosticsService(OciSettingsProvider ociSettingsProvider, OciClientFactory ociClientFactory) {
    this.ociSettingsProvider = ociSettingsProvider;
    this.ociClientFactory = ociClientFactory;
  }

  public OciDiagnosticsResult diagnose() {
    long startedAt = System.nanoTime();
    OciSettings settings = ociSettingsProvider.getSettings();
    List<OciDiagnosticStep> steps = new ArrayList<>();
    List<String> nextActions = new ArrayList<>();

    OciDiagnosticStep configStep = diagnoseConfig(settings);
    steps.add(configStep);
    if (FAILED.equals(configStep.status())) {
      nextActions.add(configSuggestion(settings));
      return buildResult(settings, steps, nextActions, startedAt);
    }

    BasicAuthenticationDetailsProvider provider;
    long providerStart = System.nanoTime();
    try {
      provider = ociClientFactory.createProvider(settings);
      steps.add(success("provider", "认证 Provider", "OCI SDK 认证 Provider 创建成功。", "如果后续权限失败，请检查 Dynamic Group 或 API Key 所属用户组。", providerStart));
    } catch (Throwable exception) {
      rethrowFatal(exception);
      steps.add(failed("provider", "认证 Provider", "OCI SDK 认证 Provider 创建失败：" + cleanErrorMessage(exception), authSuggestion(settings), providerStart));
      nextActions.add(authSuggestion(settings));
      return buildResult(settings, steps, nextActions, startedAt);
    }

    ComputeClient computeClient = null;
    VirtualNetworkClient virtualNetworkClient = null;
    MonitoringClient monitoringClient = null;
    UsageapiClient usageapiClient = null;
    Instance firstInstance = null;

    long computeStart = System.nanoTime();
    try {
      computeClient = ociClientFactory.computeClient(provider, settings.region());
      ListInstancesResponse response = computeClient.listInstances(ListInstancesRequest.builder()
        .compartmentId(settings.compartmentOcid())
        .limit(1)
        .build());
      List<Instance> instances = response.getItems() == null ? List.of() : response.getItems();
      if (instances.isEmpty()) {
        steps.add(warning("compute", "Compute 实例读取", "Compute API 可访问，但目标 compartment 下未读取到实例。", "确认 OCI_COMPARTMENT_OCID 是否是 ARM 实例所在 compartment。", computeStart));
      } else {
        firstInstance = instances.get(0);
        steps.add(success("compute", "Compute 实例读取", "Compute API 可访问，已读取到实例列表。", computePolicySuggestion(settings), computeStart));
      }
    } catch (Throwable exception) {
      rethrowFatal(exception);
      steps.add(failed("compute", "Compute 实例读取", "读取 Compute 实例失败：" + cleanErrorMessage(exception), computePolicySuggestion(settings), computeStart));
      nextActions.add(computePolicySuggestion(settings));
    }

    long vnicStart = System.nanoTime();
    if (computeClient == null || firstInstance == null) {
      steps.add(skipped("vnic", "VNIC 网络读取", "未读取到可用于诊断的实例，已跳过 VNIC 检查。", "先确认 Compute 实例读取成功，再检查 virtual-network-family 权限。", vnicStart));
    } else {
      try {
        virtualNetworkClient = ociClientFactory.virtualNetworkClient(provider, settings.region());
        ListVnicAttachmentsResponse response = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
          .compartmentId(settings.compartmentOcid())
          .instanceId(firstInstance.getId())
          .limit(1)
          .build());
        List<VnicAttachment> attachments = response.getItems() == null ? List.of() : response.getItems();
        if (attachments.isEmpty() || attachments.get(0).getVnicId() == null || attachments.get(0).getVnicId().isBlank()) {
          steps.add(warning("vnic", "VNIC 网络读取", "VNIC Attachment 可访问，但没有读取到可检查的 VNIC。", "确认实例是否有主网卡，或稍后再重试同步。", vnicStart));
        } else {
          virtualNetworkClient.getVnic(GetVnicRequest.builder().vnicId(attachments.get(0).getVnicId()).build());
          steps.add(success("vnic", "VNIC 网络读取", "Virtual Network API 可访问，VNIC 详情读取成功。", vnicPolicySuggestion(settings), vnicStart));
        }
      } catch (Throwable exception) {
        rethrowFatal(exception);
        steps.add(failed("vnic", "VNIC 网络读取", "读取 VNIC 失败：" + cleanErrorMessage(exception), vnicPolicySuggestion(settings), vnicStart));
        nextActions.add(vnicPolicySuggestion(settings));
      }
    }

    long monitoringStart = System.nanoTime();
    if (firstInstance == null) {
      steps.add(skipped("monitoring", "Monitoring 指标读取", "未读取到可用于查询指标的实例，已跳过 Monitoring 检查。", "先确认 Compute 实例读取成功，再检查 metrics 权限和实例监控插件。", monitoringStart));
    } else {
      try {
        monitoringClient = ociClientFactory.monitoringClient(provider, settings.region());
        SummarizeMetricsDataResponse response = monitoringClient.summarizeMetricsData(SummarizeMetricsDataRequest.builder()
          .compartmentId(settings.compartmentOcid())
          .summarizeMetricsDataDetails(SummarizeMetricsDataDetails.builder()
            .namespace("oci_computeagent")
            .query("CpuUtilization[1h]{resourceId = \"%s\"}.mean()".formatted(firstInstance.getId()))
            .startTime(Date.from(Instant.now().minusSeconds(6 * 60 * 60L)))
            .endTime(Date.from(Instant.now()))
            .resolution("1h")
            .build())
          .build());
        List<MetricData> metricData = response.getItems() == null ? List.of() : response.getItems();
        if (metricData.isEmpty()) {
          steps.add(warning("monitoring", "Monitoring 指标读取", "Monitoring API 可访问，但最近 6 小时没有返回 CPU 指标。", "确认 Compute Instance Monitoring plugin 已启用，并等待指标产生后再同步。", monitoringStart));
        } else {
          steps.add(success("monitoring", "Monitoring 指标读取", "Monitoring API 可访问，指标查询成功。", metricsPolicySuggestion(settings), monitoringStart));
        }
      } catch (Throwable exception) {
        rethrowFatal(exception);
        steps.add(failed("monitoring", "Monitoring 指标读取", "读取 Monitoring 指标失败：" + cleanErrorMessage(exception), metricsPolicySuggestion(settings), monitoringStart));
        nextActions.add(metricsPolicySuggestion(settings));
      }
    }

    long usageStart = System.nanoTime();
    try {
      String tenantId = resolveTenantId(settings, provider);
      if (tenantId.isBlank()) {
        steps.add(failed("usage", "Usage API 费用读取", "未配置 tenancy OCID，无法检查 Usage API。", "在 .env 中配置 OCI_TENANCY_OCID。", usageStart));
        nextActions.add("在 .env 中配置 OCI_TENANCY_OCID。");
      } else {
        usageapiClient = ociClientFactory.usageapiClient(provider, settings.region());
        LocalDate usageEnd = LocalDate.now(ZoneOffset.UTC);
        LocalDate usageStartDate = usageEnd.minusDays(1);
        RequestSummarizedUsagesResponse response = usageapiClient.requestSummarizedUsages(RequestSummarizedUsagesRequest.builder()
          .requestSummarizedUsagesDetails(RequestSummarizedUsagesDetails.builder()
            .tenantId(tenantId)
            .timeUsageStarted(Date.from(usageStartDate.atStartOfDay().toInstant(ZoneOffset.UTC)))
            .timeUsageEnded(Date.from(usageEnd.atStartOfDay().toInstant(ZoneOffset.UTC)))
            .granularity(RequestSummarizedUsagesDetails.Granularity.Daily)
            .queryType(RequestSummarizedUsagesDetails.QueryType.Cost)
            .isAggregateByTime(false)
            .groupBy(List.of("service"))
            .compartmentDepth(BigDecimal.valueOf(6))
            .build())
          .limit(1)
          .build());
        UsageAggregation aggregation = response.getUsageAggregation();
        int itemCount = aggregation == null || aggregation.getItems() == null ? 0 : aggregation.getItems().size();
        if (itemCount == 0) {
          steps.add(warning("usage", "Usage API 费用读取", "Usage API 可访问，但上一完整 UTC 日没有返回费用记录。", "Usage API 数据可能延迟；如果长期为空，再检查 usage-report 权限。", usageStart));
        } else {
          steps.add(success("usage", "Usage API 费用读取", "Usage API 可访问，费用查询成功。", usagePolicySuggestion(settings), usageStart));
        }
      }
    } catch (Throwable exception) {
      rethrowFatal(exception);
      steps.add(failed("usage", "Usage API 费用读取", "读取 Usage API 失败：" + cleanErrorMessage(exception), usagePolicySuggestion(settings), usageStart));
      nextActions.add(usagePolicySuggestion(settings));
    }

    return buildResult(settings, steps, deduplicate(nextActions), startedAt);
  }

  private OciDiagnosticStep diagnoseConfig(OciSettings settings) {
    long startedAt = System.nanoTime();
    if (ociSettingsProvider.isConfigured(settings)) {
      return success("config", "基础配置", "后端 OCI 基础配置完整。", "继续运行下方连接诊断，确认 IAM 权限是否生效。", startedAt);
    }
    return failed("config", "基础配置", "后端 OCI 基础配置不完整。", configSuggestion(settings), startedAt);
  }

  private OciDiagnosticsResult buildResult(
    OciSettings settings,
    List<OciDiagnosticStep> steps,
    List<String> nextActions,
    long startedAt
  ) {
    String overallStatus = overallStatus(steps);
    return new OciDiagnosticsResult(
      ociSettingsProvider.isConfigured(settings),
      settings.authMode().value(),
      settings.authMode().label(),
      overallStatus,
      summary(overallStatus),
      Instant.now().toString(),
      elapsedMs(startedAt),
      List.copyOf(steps),
      List.copyOf(nextActions)
    );
  }

  private String overallStatus(List<OciDiagnosticStep> steps) {
    if (steps.stream().anyMatch(step -> FAILED.equals(step.status()))) {
      return FAILED;
    }
    if (steps.stream().anyMatch(step -> WARNING.equals(step.status()) || SKIPPED.equals(step.status()))) {
      return WARNING;
    }
    return SUCCESS;
  }

  private String summary(String overallStatus) {
    return switch (overallStatus) {
      case SUCCESS -> "OCI 连接、资源读取、指标和费用接口检查通过。";
      case WARNING -> "OCI 诊断完成，但存在需要关注的配置或数据空态。";
      default -> "OCI 诊断失败，请按建议检查后端配置和 IAM Policy。";
    };
  }

  private OciDiagnosticStep success(String key, String name, String message, String suggestion, long startedAt) {
    return new OciDiagnosticStep(key, name, SUCCESS, message, suggestion, elapsedMs(startedAt));
  }

  private OciDiagnosticStep warning(String key, String name, String message, String suggestion, long startedAt) {
    return new OciDiagnosticStep(key, name, WARNING, message, suggestion, elapsedMs(startedAt));
  }

  private OciDiagnosticStep failed(String key, String name, String message, String suggestion, long startedAt) {
    return new OciDiagnosticStep(key, name, FAILED, message, suggestion, elapsedMs(startedAt));
  }

  private OciDiagnosticStep skipped(String key, String name, String message, String suggestion, long startedAt) {
    return new OciDiagnosticStep(key, name, SKIPPED, message, suggestion, elapsedMs(startedAt));
  }

  private long elapsedMs(long startedAt) {
    return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
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

  private List<String> deduplicate(List<String> values) {
    return values.stream().distinct().toList();
  }

  private void rethrowFatal(Throwable exception) {
    if (exception instanceof VirtualMachineError || exception instanceof ThreadDeath) {
      throw (Error) exception;
    }
  }

  private String cleanErrorMessage(Throwable exception) {
    if (exception instanceof BmcException bmcException) {
      return "status=%d, code=%s, message=%s".formatted(
        bmcException.getStatusCode(),
        blankToDefault(bmcException.getServiceCode(), "-"),
        blankToDefault(bmcException.getUnmodifiedMessage(), bmcException.getMessage())
      );
    }
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }

  private String configSuggestion(OciSettings settings) {
    if (settings.authMode() == OciAuthMode.INSTANCE_PRINCIPAL) {
      return "Instance Principal 模式至少需要 OCI_REGION、OCI_TENANCY_OCID、OCI_COMPARTMENT_OCID。";
    }
    return "API Key 模式至少需要 OCI_REGION、OCI_COMPARTMENT_OCID、OCI_CONFIG_PROFILE，并确认 OCI_CONFIG_FILE_PATH 指向容器内 config。";
  }

  private String authSuggestion(OciSettings settings) {
    if (settings.authMode() == OciAuthMode.INSTANCE_PRINCIPAL) {
      return "确认当前实例已加入 Dynamic Group，并等待 Dynamic Group 规则生效。";
    }
    return "确认 OCI config、fingerprint、key_file 和私钥文件权限正确。";
  }

  private String computePolicySuggestion(OciSettings settings) {
    return policyPrefix(settings) + " to read instance-family in compartment id <资源CompartmentOCID>";
  }

  private String vnicPolicySuggestion(OciSettings settings) {
    return policyPrefix(settings) + " to read virtual-network-family in compartment id <资源CompartmentOCID>";
  }

  private String metricsPolicySuggestion(OciSettings settings) {
    return policyPrefix(settings) + " to read metrics in compartment id <资源CompartmentOCID>";
  }

  private String usagePolicySuggestion(OciSettings settings) {
    return policyPrefix(settings) + " to read usage-report in tenancy";
  }

  private String policyPrefix(OciSettings settings) {
    if (settings.authMode() == OciAuthMode.INSTANCE_PRINCIPAL) {
      return "Allow dynamic-group oci-arm-monitor-instances";
    }
    return "Allow group oci-monitor-readers";
  }

  private String blankToDefault(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
