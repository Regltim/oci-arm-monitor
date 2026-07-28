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
    LocalDate reportDate = LocalDate.now();
    return getSummary(YearMonth.from(reportDate), reportDate);
  }

  public CostSummary getSummary(YearMonth month, LocalDate reportDate) {
    double ociCost = costRepository.costForMonth(month);
    double manualCost = manualCostRepository.costForMonth(month);
    double totalCost = ociCost + manualCost;
    return new CostSummary(
      ociCost,
      manualCost,
      totalCost,
      estimateMonthEnd(totalCost, reportDate),
      "CNY",
      costRepository.listMonth(month),
      manualCostRepository.listMonth(month)
    );
  }

  public ManualCost createManualCost(ManualCostCreateRequest request) {
    return manualCostRepository.create(request);
  }

  public void deleteManualCost(String id) {
    manualCostRepository.delete(id);
  }

  private double estimateMonthEnd(double currentCost, LocalDate reportDate) {
    int daysInMonth = YearMonth.from(reportDate).lengthOfMonth();
    return currentCost / reportDate.getDayOfMonth() * daysInMonth;
  }
}
