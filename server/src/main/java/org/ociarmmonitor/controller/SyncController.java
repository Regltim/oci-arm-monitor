package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.oci.OciSyncService;
import org.ociarmmonitor.oci.ScheduledSyncService;
import org.ociarmmonitor.oci.SyncResult;
import org.ociarmmonitor.oci.SyncRunRecord;
import org.ociarmmonitor.oci.SyncRunRepository;
import org.ociarmmonitor.oci.SyncSchedule;
import org.ociarmmonitor.oci.SyncScheduleUpdateRequest;
import org.ociarmmonitor.oci.SyncStatus;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sync")
public class SyncController {

  private final OciSyncService ociSyncService;
  private final SyncRunRepository syncRunRepository;
  private final ScheduledSyncService scheduledSyncService;

  public SyncController(
    OciSyncService ociSyncService,
    SyncRunRepository syncRunRepository,
    ScheduledSyncService scheduledSyncService
  ) {
    this.ociSyncService = ociSyncService;
    this.syncRunRepository = syncRunRepository;
    this.scheduledSyncService = scheduledSyncService;
  }

  @PostMapping("/resources")
  public ApiResponse<SyncResult> syncResources() {
    return ApiResponse.ok(ociSyncService.syncResources());
  }

  @PostMapping("/full")
  public ApiResponse<SyncResult> syncFull() {
    return ApiResponse.ok(ociSyncService.syncResources());
  }

  @GetMapping("/status")
  public ApiResponse<SyncStatus> getStatus() {
    return ApiResponse.ok(ociSyncService.getStatus());
  }

  @GetMapping("/history")
  public ApiResponse<List<SyncRunRecord>> listHistory(@RequestParam(defaultValue = "20") int limit) {
    return ApiResponse.ok(syncRunRepository.listRecent(Math.min(Math.max(limit, 1), 100)));
  }

  @GetMapping("/schedule")
  public ApiResponse<SyncSchedule> getSchedule() {
    return ApiResponse.ok(scheduledSyncService.getSchedule());
  }

  @PutMapping("/schedule")
  public ApiResponse<SyncSchedule> updateSchedule(@Valid @RequestBody SyncScheduleUpdateRequest request) {
    return ApiResponse.ok(scheduledSyncService.updateSchedule(request), "定时同步配置已保存");
  }
}
