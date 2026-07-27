package org.ociarmmonitor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import org.ociarmmonitor.oci.OciClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OciDiagnosticsServiceTest {

  @Mock
  private OciSettingsProvider ociSettingsProvider;

  @Mock
  private OciClientFactory ociClientFactory;

  @Test
  void diagnoseStopsBeforeOciSdkWhenConfigIsIncomplete() {
    OciSettings settings = instancePrincipalSettings("");
    given(ociSettingsProvider.getSettings()).willReturn(settings);
    given(ociSettingsProvider.isConfigured(settings)).willReturn(false);

    OciDiagnosticsService service = new OciDiagnosticsService(ociSettingsProvider, ociClientFactory);

    OciDiagnosticsResult result = service.diagnose();

    assertThat(result.overallStatus()).isEqualTo("FAILED");
    assertThat(result.steps()).extracting(OciDiagnosticStep::key).containsExactly("config");
    assertThat(result.nextActions()).isNotEmpty();
    verifyNoInteractions(ociClientFactory);
  }

  @Test
  void diagnoseReturnsProviderFailureWithSuggestion() {
    OciSettings settings = instancePrincipalSettings("ocid1.tenancy.oc1..test");
    given(ociSettingsProvider.getSettings()).willReturn(settings);
    given(ociSettingsProvider.isConfigured(settings)).willReturn(true);
    given(ociClientFactory.createProvider(settings)).willThrow(new IllegalArgumentException("metadata service unavailable"));

    OciDiagnosticsService service = new OciDiagnosticsService(ociSettingsProvider, ociClientFactory);

    OciDiagnosticsResult result = service.diagnose();

    assertThat(result.overallStatus()).isEqualTo("FAILED");
    assertThat(result.steps()).extracting(OciDiagnosticStep::key).containsExactly("config", "provider");
    assertThat(result.steps().get(1).message()).contains("metadata service unavailable");
    assertThat(result.nextActions()).anyMatch(action -> action.contains("Dynamic Group"));
  }

  private OciSettings instancePrincipalSettings(String tenancyOcid) {
    return new OciSettings(
      OciAuthMode.INSTANCE_PRINCIPAL,
      tenancyOcid,
      "",
      "",
      "us-sanjose-1",
      "ocid1.compartment.oc1..test",
      "",
      "",
      "DEFAULT",
      "2026-07-26T09:00:00Z"
    );
  }
}
