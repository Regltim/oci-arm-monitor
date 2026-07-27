package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.instance.CloudInstanceService;
import org.ociarmmonitor.instance.InstanceOverview;
import org.ociarmmonitor.instance.MetricPoint;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/instances")
public class InstanceController {

  private final CloudInstanceService cloudInstanceService;

  public InstanceController(CloudInstanceService cloudInstanceService) {
    this.cloudInstanceService = cloudInstanceService;
  }

  @GetMapping
  public ApiResponse<List<InstanceOverview>> listInstances() {
    return ApiResponse.ok(cloudInstanceService.listInstances());
  }

  @GetMapping("/{instanceId}/metrics")
  public ApiResponse<List<MetricPoint>> listMetrics(
    @PathVariable String instanceId,
    @RequestParam String metricName
  ) {
    return ApiResponse.ok(cloudInstanceService.listMetricSeries(instanceId, metricName));
  }
}
