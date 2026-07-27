package org.ociarmmonitor.config;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OciSettingsProvider {

  private final String configFilePath;
  private final String profile;
  private final String region;
  private final String compartmentOcid;
  private final String tenancyOcid;
  private final OciAuthMode authMode;

  public OciSettingsProvider(
    @Value("${monitor.oci.auth-mode:config_file}") String authMode,
    @Value("${monitor.oci.config-file-path:}") String configFilePath,
    @Value("${monitor.oci.profile:DEFAULT}") String profile,
    @Value("${monitor.oci.region:}") String region,
    @Value("${monitor.oci.compartment-ocid:}") String compartmentOcid,
    @Value("${monitor.oci.tenancy-ocid:}") String tenancyOcid
  ) {
    this.authMode = OciAuthMode.from(authMode);
    this.configFilePath = normalize(configFilePath);
    this.profile = blankToDefault(profile, "DEFAULT");
    this.region = normalize(region);
    this.compartmentOcid = normalize(compartmentOcid);
    this.tenancyOcid = normalize(tenancyOcid);
  }

  public OciSettings getSettings() {
    return new OciSettings(
      authMode,
      tenancyOcid,
      "",
      "",
      region,
      compartmentOcid,
      "",
      configFilePath,
      profile,
      Instant.now().toString()
    );
  }

  public OciSettingsStatus getStatus() {
    OciSettings settings = getSettings();
    return new OciSettingsStatus(
      isConfigured(settings),
      settings.authMode().value(),
      settings.authMode().label(),
      settings.authMode() == OciAuthMode.CONFIG_FILE,
      !settings.configFilePath().isBlank(),
      !settings.profile().isBlank(),
      !settings.region().isBlank(),
      !settings.compartmentOcid().isBlank(),
      !settings.tenancyOcid().isBlank(),
      settings.authMode() == OciAuthMode.INSTANCE_PRINCIPAL
        ? "SERVER_ENV_AND_INSTANCE_PRINCIPAL"
        : "SERVER_ENV_AND_OCI_CONFIG",
      settings.updatedAt()
    );
  }

  public boolean isConfigured(OciSettings settings) {
    return settings.region() != null && !settings.region().isBlank()
      && settings.compartmentOcid() != null && !settings.compartmentOcid().isBlank()
      && isAuthConfigured(settings);
  }

  private boolean isAuthConfigured(OciSettings settings) {
    if (settings.authMode() == OciAuthMode.INSTANCE_PRINCIPAL) {
      return settings.tenancyOcid() != null && !settings.tenancyOcid().isBlank();
    }
    return settings.configFilePath() != null && !settings.configFilePath().isBlank()
      && settings.profile() != null && !settings.profile().isBlank();
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private String blankToDefault(String value, String defaultValue) {
    String normalizedValue = normalize(value);
    return normalizedValue.isBlank() ? defaultValue : normalizedValue;
  }
}
