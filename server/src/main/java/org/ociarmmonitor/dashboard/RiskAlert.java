package org.ociarmmonitor.dashboard;

public record RiskAlert(
  String level,
  String title,
  String description
) {
}
