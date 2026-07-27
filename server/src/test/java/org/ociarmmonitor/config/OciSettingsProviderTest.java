package org.ociarmmonitor.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OciSettingsProviderTest {

  @Test
  void instancePrincipalModeDoesNotRequireConfigFile() {
    OciSettingsProvider provider = new OciSettingsProvider(
      "instance_principal",
      "",
      "",
      "us-sanjose-1",
      "ocid1.compartment.oc1..test",
      "ocid1.tenancy.oc1..test"
    );

    OciSettings settings = provider.getSettings();
    OciSettingsStatus status = provider.getStatus();

    assertThat(provider.isConfigured(settings)).isTrue();
    assertThat(status.authMode()).isEqualTo("instance_principal");
    assertThat(status.configFileRequired()).isFalse();
    assertThat(status.configFileConfigured()).isFalse();
    assertThat(status.tenancyConfigured()).isTrue();
  }

  @Test
  void configFileModeRequiresConfigFileAndProfile() {
    OciSettingsProvider provider = new OciSettingsProvider(
      "config_file",
      "",
      "DEFAULT",
      "us-sanjose-1",
      "ocid1.compartment.oc1..test",
      ""
    );

    OciSettingsStatus status = provider.getStatus();

    assertThat(status.configured()).isFalse();
    assertThat(status.configFileRequired()).isTrue();
    assertThat(status.profileConfigured()).isTrue();
  }

  @Test
  void instancePrincipalModeRequiresTenancyForUsageApi() {
    OciSettingsProvider provider = new OciSettingsProvider(
      "instance_principal",
      "",
      "",
      "us-sanjose-1",
      "ocid1.compartment.oc1..test",
      ""
    );

    OciSettingsStatus status = provider.getStatus();

    assertThat(status.configured()).isFalse();
    assertThat(status.tenancyConfigured()).isFalse();
  }
}
