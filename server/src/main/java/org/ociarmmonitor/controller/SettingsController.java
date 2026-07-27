package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.config.FreeQuota;
import org.ociarmmonitor.config.FreeQuotaRepository;
import org.ociarmmonitor.config.OciDiagnosticsResult;
import org.ociarmmonitor.config.OciDiagnosticsService;
import org.ociarmmonitor.config.OciSettingsProvider;
import org.ociarmmonitor.config.OciSettingsStatus;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settings")
public class SettingsController {

  private final FreeQuotaRepository freeQuotaRepository;
  private final OciDiagnosticsService ociDiagnosticsService;
  private final OciSettingsProvider ociSettingsProvider;

  public SettingsController(
    FreeQuotaRepository freeQuotaRepository,
    OciDiagnosticsService ociDiagnosticsService,
    OciSettingsProvider ociSettingsProvider
  ) {
    this.freeQuotaRepository = freeQuotaRepository;
    this.ociDiagnosticsService = ociDiagnosticsService;
    this.ociSettingsProvider = ociSettingsProvider;
  }

  @GetMapping("/quota")
  public ApiResponse<FreeQuota> getQuota() {
    return ApiResponse.ok(freeQuotaRepository.getQuota());
  }

  @PutMapping("/quota")
  public ApiResponse<FreeQuota> updateQuota(@RequestBody FreeQuota quota) {
    FreeQuota nextQuota = new FreeQuota(
      quota.ampereOcpuHours(),
      quota.ampereMemoryGbHours(),
      quota.blockVolumeGb(),
      quota.outboundDataTransferGb(),
      quota.monitoringIngestionPoints(),
      quota.monitoringRetrievalPoints(),
      Instant.now().toString()
    );
    freeQuotaRepository.save(nextQuota);
    return ApiResponse.ok(nextQuota, "免费额度配置已保存");
  }

  @GetMapping("/oci")
  public ApiResponse<OciSettingsStatus> getOciSettings() {
    return ApiResponse.ok(ociSettingsProvider.getStatus());
  }

  @GetMapping("/oci/diagnostics")
  public ApiResponse<OciDiagnosticsResult> diagnoseOciSettings() {
    return ApiResponse.ok(ociDiagnosticsService.diagnose());
  }
}
