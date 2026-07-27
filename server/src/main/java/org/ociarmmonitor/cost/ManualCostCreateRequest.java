package org.ociarmmonitor.cost;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ManualCostCreateRequest(
  @NotBlank(message = "费用名称不能为空") String costName,
  @NotBlank(message = "费用分类不能为空") String category,
  @DecimalMin(value = "0.01", message = "费用金额必须大于 0") double amount,
  @NotBlank(message = "币种不能为空")
  @Pattern(regexp = "CNY", message = "第一版手工费用仅支持 CNY") String currency,
  @NotBlank(message = "发生日期不能为空") String occurredOn,
  String note
) {
}
