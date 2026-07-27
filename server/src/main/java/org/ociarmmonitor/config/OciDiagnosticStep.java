package org.ociarmmonitor.config;

public record OciDiagnosticStep(
  String key,
  String name,
  String status,
  String message,
  String suggestion,
  long durationMs
) {
}
