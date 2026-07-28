package org.ociarmmonitor.publicreport;

public record PublicReportView(
  String id,
  String createdAt,
  String expiresAt,
  PublicReportPayload report
) {
}
