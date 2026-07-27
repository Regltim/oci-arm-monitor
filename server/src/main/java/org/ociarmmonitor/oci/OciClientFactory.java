package org.ociarmmonitor.oci;

import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimplePrivateKeySupplier;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.usageapi.UsageapiClient;
import org.ociarmmonitor.config.OciAuthMode;
import org.ociarmmonitor.config.OciSettings;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OciClientFactory {

  private final ClientConfiguration clientConfiguration;

  public OciClientFactory(
    @Value("${monitor.oci.connect-timeout-millis:10000}") int connectTimeoutMillis,
    @Value("${monitor.oci.read-timeout-millis:60000}") int readTimeoutMillis
  ) {
    this.clientConfiguration = ClientConfiguration.builder()
      .connectionTimeoutMillis(connectTimeoutMillis)
      .readTimeoutMillis(readTimeoutMillis)
      .build();
  }

  public BasicAuthenticationDetailsProvider createProvider(OciSettings settings) {
    try {
      if (settings.authMode() == OciAuthMode.INSTANCE_PRINCIPAL) {
        return InstancePrincipalsAuthenticationDetailsProvider.builder().build();
      }
      if (settings.configFilePath() != null && !settings.configFilePath().isBlank()) {
        return new ConfigFileAuthenticationDetailsProvider(
          expandHome(settings.configFilePath()),
          blankToDefault(settings.profile(), "DEFAULT")
        );
      }
      if (settings.tenancyOcid().isBlank()
        || settings.userOcid().isBlank()
        || settings.fingerprint().isBlank()
        || settings.privateKeyPath().isBlank()
        || settings.region().isBlank()) {
        throw new IllegalArgumentException("OCI 配置不完整，请检查后端环境变量和 OCI config。");
      }
      return SimpleAuthenticationDetailsProvider.builder()
        .tenantId(settings.tenancyOcid())
        .userId(settings.userOcid())
        .fingerprint(settings.fingerprint())
        .privateKeySupplier(new SimplePrivateKeySupplier(expandHome(settings.privateKeyPath())))
        .region(Region.fromRegionId(settings.region()))
        .build();
    } catch (IOException exception) {
      throw new IllegalArgumentException("读取 OCI config 文件失败：" + exception.getMessage(), exception);
    }
  }

  public ComputeClient computeClient(BasicAuthenticationDetailsProvider provider, String region) {
    ComputeClient client = ComputeClient.builder().configuration(clientConfiguration).build(provider);
    client.setRegion(region);
    return client;
  }

  public VirtualNetworkClient virtualNetworkClient(BasicAuthenticationDetailsProvider provider, String region) {
    VirtualNetworkClient client = VirtualNetworkClient.builder().configuration(clientConfiguration).build(provider);
    client.setRegion(region);
    return client;
  }

  public MonitoringClient monitoringClient(BasicAuthenticationDetailsProvider provider, String region) {
    MonitoringClient client = MonitoringClient.builder().configuration(clientConfiguration).build(provider);
    client.setRegion(region);
    return client;
  }

  public UsageapiClient usageapiClient(BasicAuthenticationDetailsProvider provider, String region) {
    UsageapiClient client = UsageapiClient.builder().configuration(clientConfiguration).build(provider);
    client.setRegion(region);
    return client;
  }

  private String expandHome(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    if (value.equals("~")) {
      return Path.of(System.getProperty("user.home")).toString();
    }
    if (value.startsWith("~/")) {
      return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
    }
    return value;
  }

  private String blankToDefault(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
