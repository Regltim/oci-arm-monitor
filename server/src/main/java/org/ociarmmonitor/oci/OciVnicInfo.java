package org.ociarmmonitor.oci;

public record OciVnicInfo(
  String instanceId,
  String vnicId,
  String publicIp,
  String privateIp
) {
}
