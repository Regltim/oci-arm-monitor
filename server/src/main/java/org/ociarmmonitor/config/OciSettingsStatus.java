package org.ociarmmonitor.config;

public record OciSettingsStatus(
  boolean configured,
  String authMode,
  String authModeLabel,
  boolean configFileRequired,
  boolean configFileConfigured,
  boolean profileConfigured,
  boolean regionConfigured,
  boolean compartmentConfigured,
  boolean tenancyConfigured,
  String source,
  String updatedAt
) {
}
