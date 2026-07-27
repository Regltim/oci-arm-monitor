package org.ociarmmonitor.oci;

import jakarta.validation.constraints.NotBlank;

public record SyncScheduleUpdateRequest(
  boolean enabled,
  @NotBlank String cronExpression,
  @NotBlank String zoneId,
  boolean syncOnStartup
) {
}
