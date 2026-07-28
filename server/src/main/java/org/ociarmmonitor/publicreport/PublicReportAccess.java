package org.ociarmmonitor.publicreport;

public record PublicReportAccess(
  String snapshotId,
  String token,
  String url,
  String expiresAt
) {
}
