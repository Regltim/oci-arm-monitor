package org.ociarmmonitor.publicreport;

public record PublicReportSnapshot(
  String id,
  String createdAt,
  String expiresAt
) {
}
