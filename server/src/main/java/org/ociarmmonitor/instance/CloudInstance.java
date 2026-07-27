package org.ociarmmonitor.instance;

public record CloudInstance(
  String id,
  String displayName,
  String region,
  String compartmentId,
  String shape,
  String lifecycleState,
  double ocpus,
  double memoryGb,
  double bootVolumeGb,
  String publicIp,
  String privateIp,
  String createdAt,
  String updatedAt
) {
}
