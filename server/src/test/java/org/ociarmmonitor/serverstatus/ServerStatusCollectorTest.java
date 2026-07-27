package org.ociarmmonitor.serverstatus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerStatusCollectorTest {

  @TempDir
  private Path tempDir;

  @Test
  void collectSystemInfoReadsCpuModelAndRuntimeDetails() throws Exception {
    Files.writeString(tempDir.resolve("cpuinfo"), """
      processor   : 0
      model name  : Ampere Altra Processor
      processor   : 1
      model name  : Ampere Altra Processor
      """);

    ServerStatusCollector collector = new ServerStatusCollector(
      tempDir.toString(),
      tempDir.toString(),
      "jdbc:sqlite:" + tempDir.resolve("monitor.db")
    );

    ServerSystemInfo systemInfo = collector.collectSystemInfo();

    assertThat(systemInfo.cpuModelName()).isEqualTo("Ampere Altra Processor");
    assertThat(systemInfo.availableProcessors()).isGreaterThan(0);
    assertThat(systemInfo.osArch()).isEqualTo(System.getProperty("os.arch", ""));
    assertThat(systemInfo.javaVersion()).isEqualTo(System.getProperty("java.version", ""));
    assertThat(systemInfo.jvmName()).isEqualTo(System.getProperty("java.vm.name", ""));
  }
}
