package org.ociarmmonitor.oci;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.oracle.bmc.http.client.HttpProvider;
import javax.ws.rs.client.ClientBuilder;
import org.junit.jupiter.api.Test;

class OciHttpProviderCompatibilityTest {

  @Test
  void ociHttpProviderCanLoadJerseyRuntime() {
    assertThatCode(() -> HttpProvider.getDefault().newBuilder()).doesNotThrowAnyException();
  }

  @Test
  void jerseyRuntimeUsesJavaxJaxRsApiRequiredByOciSdk() {
    assertThat(ClientBuilder.class.isAssignableFrom(org.glassfish.jersey.client.JerseyClientBuilder.class))
      .isTrue();
  }
}
