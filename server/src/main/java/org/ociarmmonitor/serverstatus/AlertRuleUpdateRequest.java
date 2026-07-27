package org.ociarmmonitor.serverstatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record AlertRuleUpdateRequest(
  @NotBlank String operator,
  @PositiveOrZero double threshold,
  @NotBlank String severity,
  boolean enabled
) {
}
