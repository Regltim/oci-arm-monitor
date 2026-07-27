package org.ociarmmonitor.oci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.usageapi.UsageapiClient;
import com.oracle.bmc.usageapi.model.UsageAggregation;
import com.oracle.bmc.usageapi.model.RequestSummarizedUsagesDetails;
import com.oracle.bmc.usageapi.requests.RequestSummarizedUsagesRequest;
import com.oracle.bmc.usageapi.responses.RequestSummarizedUsagesResponse;
import org.ociarmmonitor.config.OciAuthMode;
import org.ociarmmonitor.config.OciSettingsProvider;
import org.ociarmmonitor.config.OciSettings;
import org.ociarmmonitor.cost.CostRepository;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.traffic.TrafficRepository;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

@ExtendWith(MockitoExtension.class)
class OciSyncServiceTest {

  @Mock
  private OciSettingsProvider ociSettingsProvider;

  @Mock
  private OciClientFactory ociClientFactory;

  @Mock
  private CloudInstanceRepository cloudInstanceRepository;

  @Mock
  private MetricRepository metricRepository;

  @Mock
  private TrafficRepository trafficRepository;

  @Mock
  private CostRepository costRepository;

  @Mock
  private SyncRunRepository syncRunRepository;

  @Mock
  private AuthenticationDetailsProvider authenticationDetailsProvider;

  @Mock
  private ComputeClient computeClient;

  @Mock
  private VirtualNetworkClient virtualNetworkClient;

  @Mock
  private MonitoringClient monitoringClient;

  @Mock
  private UsageapiClient usageapiClient;

  @Test
  void syncResourcesStartsBackgroundTaskAndReturnsRunningImmediately() {
    CapturingTaskExecutor taskExecutor = new CapturingTaskExecutor();
    SyncResult runningResult = new SyncResult("RUNNING", "同步任务已开始，正在后台拉取 OCI 数据。", "2026-07-06T08:00:00Z", null, 0, 0, 0, 0);
    given(syncRunRepository.start("FULL")).willReturn("run-1");
    given(syncRunRepository.findById("run-1")).willReturn(Optional.of(runningResult));

    OciSyncService service = buildService(taskExecutor);

    SyncResult result = service.syncResources();

    assertThat(result).isEqualTo(runningResult);
    assertThat(taskExecutor.tasks).hasSize(1);
    verifyNoInteractions(ociSettingsProvider, ociClientFactory);
  }

  @Test
  void syncResourcesDoesNotStartSecondTaskWhenOneIsAlreadyRunning() {
    CapturingTaskExecutor taskExecutor = new CapturingTaskExecutor();
    SyncResult runningResult = new SyncResult("RUNNING", "正在后台同步 OCI 数据。", "2026-07-06T08:00:00Z", null, 0, 0, 0, 0);
    given(syncRunRepository.start("FULL")).willReturn("run-1");
    given(syncRunRepository.findById("run-1")).willReturn(Optional.of(runningResult));
    given(syncRunRepository.latest()).willReturn(Optional.of(runningResult));

    OciSyncService service = buildService(taskExecutor);

    service.syncResources();
    SyncResult secondResult = service.syncResources();

    assertThat(secondResult).isEqualTo(runningResult);
    assertThat(taskExecutor.tasks).hasSize(1);
    verify(syncRunRepository, times(1)).start("FULL");
  }

  @Test
  void backgroundSyncMarksRunFailedWhenOciHttpProviderThrowsServiceConfigurationError() {
    OciSettings settings = new OciSettings(
      OciAuthMode.CONFIG_FILE,
      "ocid1.tenancy.oc1..test",
      "",
      "",
      "ap-seoul-1",
      "ocid1.compartment.oc1..test",
      "",
      "/home/monitor/.oci/config",
      "DEFAULT",
      "2026-07-06T08:00:00Z"
    );
    SyncResult runningResult = new SyncResult("RUNNING", "同步任务已开始，正在后台拉取 OCI 数据。", "2026-07-06T08:00:00Z", null, 0, 0, 0, 0);
    given(syncRunRepository.start("FULL")).willReturn("run-1");
    given(syncRunRepository.findById("run-1")).willReturn(Optional.of(runningResult));
    given(ociSettingsProvider.getSettings()).willReturn(settings);
    given(ociSettingsProvider.isConfigured(settings)).willReturn(true);
    given(ociClientFactory.createProvider(settings)).willReturn(authenticationDetailsProvider);
    given(ociClientFactory.computeClient(authenticationDetailsProvider, "ap-seoul-1"))
      .willThrow(new ServiceConfigurationError("com.oracle.bmc.http.client.HttpProvider could not be instantiated"));

    OciSyncService service = buildService(Runnable::run);

    SyncResult result = service.syncResources();

    assertThat(result.status()).isEqualTo("RUNNING");
    verify(syncRunRepository).finish(
      "run-1",
      "FAILED",
      "com.oracle.bmc.http.client.HttpProvider could not be instantiated",
      0,
      0,
      0,
      0
    );
  }

  @Test
  void usageApiRequestUsesUtcDayPrecisionForDailyCostQuery() {
    OciSettings settings = new OciSettings(
      OciAuthMode.CONFIG_FILE,
      "ocid1.tenancy.oc1..test",
      "",
      "",
      "ap-seoul-1",
      "ocid1.compartment.oc1..test",
      "",
      "/home/monitor/.oci/config",
      "DEFAULT",
      "2026-07-06T08:00:00Z"
    );
    SyncResult runningResult = new SyncResult("RUNNING", "同步任务已开始，正在后台拉取 OCI 数据。", "2026-07-06T08:00:00Z", null, 0, 0, 0, 0);
    given(syncRunRepository.start("FULL")).willReturn("run-1");
    given(syncRunRepository.findById("run-1")).willReturn(Optional.of(runningResult));
    given(ociSettingsProvider.getSettings()).willReturn(settings);
    given(ociSettingsProvider.isConfigured(settings)).willReturn(true);
    given(ociClientFactory.createProvider(settings)).willReturn(authenticationDetailsProvider);
    given(ociClientFactory.computeClient(authenticationDetailsProvider, "ap-seoul-1")).willReturn(computeClient);
    given(ociClientFactory.virtualNetworkClient(authenticationDetailsProvider, "ap-seoul-1")).willReturn(virtualNetworkClient);
    given(ociClientFactory.monitoringClient(authenticationDetailsProvider, "ap-seoul-1")).willReturn(monitoringClient);
    given(ociClientFactory.usageapiClient(authenticationDetailsProvider, "ap-seoul-1")).willReturn(usageapiClient);
    given(computeClient.listInstances(any())).willReturn(ListInstancesResponse.builder().items(List.of()).build());
    given(usageapiClient.requestSummarizedUsages(any())).willReturn(RequestSummarizedUsagesResponse.builder()
      .usageAggregation(UsageAggregation.builder().items(List.of()).build())
      .build());

    OciSyncService service = buildService(Runnable::run);

    service.syncResources();

    ArgumentCaptor<RequestSummarizedUsagesRequest> captor = ArgumentCaptor.forClass(RequestSummarizedUsagesRequest.class);
    verify(usageapiClient).requestSummarizedUsages(captor.capture());
    RequestSummarizedUsagesDetails details = captor.getValue().getRequestSummarizedUsagesDetails();
    assertThat(details.getTimeUsageStarted().toInstant())
      .isEqualTo(details.getTimeUsageStarted().toInstant().truncatedTo(ChronoUnit.DAYS));
    assertThat(details.getTimeUsageEnded().toInstant())
      .isEqualTo(details.getTimeUsageEnded().toInstant().truncatedTo(ChronoUnit.DAYS));
    assertThat(details.getTimeUsageStarted().toInstant().atZone(ZoneOffset.UTC).toLocalTime().toString()).isEqualTo("00:00");
    assertThat(details.getTimeUsageEnded().toInstant().atZone(ZoneOffset.UTC).toLocalTime().toString()).isEqualTo("00:00");
  }

  private OciSyncService buildService(TaskExecutor taskExecutor) {
    return new OciSyncService(
      ociSettingsProvider,
      ociClientFactory,
      cloudInstanceRepository,
      metricRepository,
      trafficRepository,
      costRepository,
      syncRunRepository,
      taskExecutor
    );
  }

  private static final class CapturingTaskExecutor implements TaskExecutor {
    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }
  }
}
