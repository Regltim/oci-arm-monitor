package org.ociarmmonitor.config;

public record OciSettings(
  OciAuthMode authMode,
  String tenancyOcid,
  String userOcid,
  String fingerprint,
  String region,
  String compartmentOcid,
  String privateKeyPath,
  String configFilePath,
  String profile,
  String updatedAt
) {
}
