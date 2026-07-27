package org.ociarmmonitor.controller;

import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.cost.CostService;
import org.ociarmmonitor.cost.CostSummary;
import org.ociarmmonitor.cost.ManualCost;
import org.ociarmmonitor.cost.ManualCostCreateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/costs")
public class CostController {

  private final CostService costService;

  public CostController(CostService costService) {
    this.costService = costService;
  }

  @GetMapping("/summary")
  public ApiResponse<CostSummary> getSummary() {
    return ApiResponse.ok(costService.getSummary());
  }

  @PostMapping("/manual")
  public ApiResponse<ManualCost> createManualCost(@Valid @RequestBody ManualCostCreateRequest request) {
    return ApiResponse.ok(costService.createManualCost(request), "费用记录已保存");
  }

  @DeleteMapping("/manual/{id}")
  public ApiResponse<Void> deleteManualCost(@PathVariable String id) {
    costService.deleteManualCost(id);
    return ApiResponse.ok(null, "费用记录已删除");
  }
}
