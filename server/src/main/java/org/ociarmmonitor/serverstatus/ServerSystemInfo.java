package org.ociarmmonitor.serverstatus;

public record ServerSystemInfo(
  String hostName,
  String osName,
  String osVersion,
  String osArch,
  int availableProcessors,
  String cpuModelName,
  String javaVersion,
  String javaVendor,
  String jvmName,
  String runtimeName
) {
}
