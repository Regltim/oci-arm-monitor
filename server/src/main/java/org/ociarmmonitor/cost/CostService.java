package org.ociarmmonitor.cost;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CostService {

  private final CostRepository costRepository;
  private final ManualCostRepository manualCostRepository;

  public CostService(CostRepository costRepository, ManualCostRepository manualCostRepository) {
    this.costRepository = costRepository;
    this.manualCostRepository = manualCostRepository;
  }

  public CostSummary getSummary() {
    double ociCost = costRepository.costForCurrentMonth();
    double manualCost = manualCostRepository.costForCurrentMonth();
    double totalCost = ociCost + manualCost;
    return new CostSummary(
      ociCost,
      manualCost,
      totalCost,
      estimateMonthEnd(totalCost),
      "CNY",
      costRepository.listCurrentMonth(),
      manualCostRepository.listCurrentMonth()
    );
  }

  public ManualCost createManualCost(ManualCostCreateRequest request) {
    return manualCostRepository.create(request);
  }

  public void deleteManualCost(String id) {
    manualCostRepository.delete(id);
  }

  private double estimateMonthEnd(double currentCost) {
    LocalDate today = LocalDate.now();
    int daysInMonth = YearMonth.from(today).lengthOfMonth();
    return currentCost / today.getDayOfMonth() * daysInMonth;
  }
}
