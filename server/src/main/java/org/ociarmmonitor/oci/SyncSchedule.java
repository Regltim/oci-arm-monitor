package org.ociarmmonitor.oci;

public record SyncSchedule(
  boolean enabled,
  String cronExpression,
  String zoneId,
  boolean syncOnStartup,
  String updatedAt,
  String nextRunAt
) {
}
