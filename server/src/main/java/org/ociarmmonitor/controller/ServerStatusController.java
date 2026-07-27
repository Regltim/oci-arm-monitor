package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.serverstatus.AlertRule;
import org.ociarmmonitor.serverstatus.AlertRuleUpdateRequest;
import org.ociarmmonitor.serverstatus.ServerAlertService;
import org.ociarmmonitor.serverstatus.ServerStatusService;
import org.ociarmmonitor.serverstatus.ServerStatusSummary;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/server")
public class ServerStatusController {

  private final ServerStatusService serverStatusService;
  private final ServerAlertService serverAlertService;

  public ServerStatusController(ServerStatusService serverStatusService, ServerAlertService serverAlertService) {
    this.serverStatusService = serverStatusService;
    this.serverAlertService = serverAlertService;
  }

  @GetMapping("/status")
  public ApiResponse<ServerStatusSummary> getStatus() {
    return ApiResponse.ok(serverStatusService.getSummary());
  }

  @GetMapping("/alert-rules")
  public ApiResponse<List<AlertRule>> listAlertRules() {
    return ApiResponse.ok(serverAlertService.listRules());
  }

  @PutMapping("/alert-rules/{id}")
  public ApiResponse<AlertRule> updateAlertRule(
    @PathVariable String id,
    @Valid @RequestBody AlertRuleUpdateRequest request
  ) {
    return ApiResponse.ok(serverAlertService.updateRule(id, request), "告警规则已保存");
  }
}
