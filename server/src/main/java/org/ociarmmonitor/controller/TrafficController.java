package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.traffic.TrafficService;
import org.ociarmmonitor.traffic.TrafficSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/traffic")
public class TrafficController {

  private final TrafficService trafficService;

  public TrafficController(TrafficService trafficService) {
    this.trafficService = trafficService;
  }

  @GetMapping("/summary")
  public ApiResponse<TrafficSummary> getSummary() {
    return ApiResponse.ok(trafficService.getSummary());
  }
}
