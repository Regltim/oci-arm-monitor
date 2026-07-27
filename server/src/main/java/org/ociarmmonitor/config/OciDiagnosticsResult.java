package org.ociarmmonitor.config;

import java.util.List;

public record OciDiagnosticsResult(
  boolean configured,
  String authMode,
  String authModeLabel,
  String overallStatus,
  String summary,
  String checkedAt,
  long durationMs,
  List<OciDiagnosticStep> steps,
  List<String> nextActions
) {
}
