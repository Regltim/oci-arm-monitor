package org.ociarmmonitor.serverstatus;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ServerStatusCollector {

  private final Path procPath;
  private final Path fallbackProcPath;
  private final Path diskPath;
  private final String datasourceUrl;
  private CpuSample previousCpuSample;
  private NetworkSample previousNetworkSample;

  public ServerStatusCollector(
    @Value("${monitor.server.proc-path:/host/proc}") String procPath,
    @Value("${monitor.server.disk-path:/data}") String diskPath,
    @Value("${spring.datasource.url}") String datasourceUrl
  ) {
    this.procPath = Path.of(procPath);
    this.fallbackProcPath = Path.of("/proc");
    this.diskPath = Path.of(diskPath);
    this.datasourceUrl = datasourceUrl;
  }

  public synchronized ServerStatusSnapshot sample() {
    Instant now = Instant.now();
    CpuReading cpuReading = readCpuReading();
    double cpuUsagePercent = calculateCpuUsage(cpuReading, now);
    NetworkReading networkReading = readNetworkReading();
    NetworkRate networkRate = calculateNetworkRate(networkReading, now);
    MemoryReading memoryReading = readMemoryReading();
    LoadReading loadReading = readLoadReading();
    DiskReading diskReading = readDiskReading();
    Runtime runtime = Runtime.getRuntime();
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    return new ServerStatusSnapshot(
      now.toString(),
      cpuUsagePercent,
      loadReading.loadOne(),
      loadReading.loadFive(),
      loadReading.loadFifteen(),
      memoryReading.memoryTotalBytes(),
      memoryReading.memoryAvailableBytes(),
      percent(memoryReading.memoryTotalBytes() - memoryReading.memoryAvailableBytes(), memoryReading.memoryTotalBytes()),
      memoryReading.swapTotalBytes(),
      memoryReading.swapFreeBytes(),
      percent(memoryReading.swapTotalBytes() - memoryReading.swapFreeBytes(), memoryReading.swapTotalBytes()),
      diskReading.totalBytes(),
      diskReading.usableBytes(),
      percent(diskReading.totalBytes() - diskReading.usableBytes(), diskReading.totalBytes()),
      networkReading.rxBytes(),
      networkReading.txBytes(),
      networkRate.rxBytesPerSecond(),
      networkRate.txBytesPerSecond(),
      readUptimeSeconds(),
      ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
      runtime.totalMemory() - runtime.freeMemory(),
      runtime.maxMemory(),
      threadMXBean.getThreadCount(),
      readDatabaseSizeBytes()
    );
  }

  public ServerSystemInfo collectSystemInfo() {
    Runtime runtime = Runtime.getRuntime();
    return new ServerSystemInfo(
      readHostName(),
      System.getProperty("os.name", ""),
      System.getProperty("os.version", ""),
      System.getProperty("os.arch", ""),
      runtime.availableProcessors(),
      readCpuModelName(),
      System.getProperty("java.version", ""),
      System.getProperty("java.vendor", ""),
      System.getProperty("java.vm.name", ""),
      ManagementFactory.getRuntimeMXBean().getName()
    );
  }

  private double calculateCpuUsage(CpuReading current, Instant now) {
    if (current.totalTicks() <= 0) {
      return 0;
    }
    CpuSample previous = previousCpuSample;
    previousCpuSample = new CpuSample(current, now);
    if (previous == null) {
      return 0;
    }
    long totalDelta = current.totalTicks() - previous.reading().totalTicks();
    long idleDelta = current.idleTicks() - previous.reading().idleTicks();
    if (totalDelta <= 0) {
      return 0;
    }
    return clampPercent((double) (totalDelta - idleDelta) * 100 / totalDelta);
  }

  private NetworkRate calculateNetworkRate(NetworkReading current, Instant now) {
    NetworkSample previous = previousNetworkSample;
    previousNetworkSample = new NetworkSample(current, now);
    if (previous == null) {
      return new NetworkRate(0, 0);
    }
    double seconds = Math.max(Duration.between(previous.sampledAt(), now).toMillis() / 1000.0, 0.001);
    return new NetworkRate(
      Math.max((current.rxBytes() - previous.reading().rxBytes()) / seconds, 0),
      Math.max((current.txBytes() - previous.reading().txBytes()) / seconds, 0)
    );
  }

  private CpuReading readCpuReading() {
    List<String> lines = readAllLines(resolveProcFile("stat"));
    if (lines.isEmpty() || !lines.get(0).startsWith("cpu ")) {
      return new CpuReading(0, 0);
    }
    String[] parts = lines.get(0).trim().split("\\s+");
    long total = 0;
    for (int index = 1; index < parts.length; index += 1) {
      total += parseLong(parts[index]);
    }
    long idle = parseLong(parts.length > 4 ? parts[4] : "0") + parseLong(parts.length > 5 ? parts[5] : "0");
    return new CpuReading(total, idle);
  }

  private MemoryReading readMemoryReading() {
    Map<String, Long> values = new HashMap<>();
    for (String line : readAllLines(resolveProcFile("meminfo"))) {
      String[] parts = line.split(":");
      if (parts.length < 2) {
        continue;
      }
      values.put(parts[0], parseLong(parts[1].replace("kB", "").trim()) * 1024);
    }
    long total = values.getOrDefault("MemTotal", 0L);
    long available = values.getOrDefault("MemAvailable", values.getOrDefault("MemFree", 0L));
    long swapTotal = values.getOrDefault("SwapTotal", 0L);
    long swapFree = values.getOrDefault("SwapFree", 0L);
    return new MemoryReading(total, available, swapTotal, swapFree);
  }

  private LoadReading readLoadReading() {
    List<String> lines = readAllLines(resolveProcFile("loadavg"));
    if (lines.isEmpty()) {
      return new LoadReading(0, 0, 0);
    }
    String[] parts = lines.get(0).trim().split("\\s+");
    return new LoadReading(
      parseDouble(parts.length > 0 ? parts[0] : "0"),
      parseDouble(parts.length > 1 ? parts[1] : "0"),
      parseDouble(parts.length > 2 ? parts[2] : "0")
    );
  }

  private NetworkReading readNetworkReading() {
    long rxBytes = 0;
    long txBytes = 0;
    for (String line : readAllLines(resolveProcFile("net/dev"))) {
      if (!line.contains(":")) {
        continue;
      }
      String[] nameAndValues = line.trim().split(":");
      if (nameAndValues.length < 2 || "lo".equals(nameAndValues[0].trim())) {
        continue;
      }
      String[] values = nameAndValues[1].trim().split("\\s+");
      if (values.length < 16) {
        continue;
      }
      rxBytes += parseLong(values[0]);
      txBytes += parseLong(values[8]);
    }
    return new NetworkReading(rxBytes, txBytes);
  }

  private DiskReading readDiskReading() {
    File disk = Files.exists(diskPath) ? diskPath.toFile() : Path.of("/").toFile();
    return new DiskReading(disk.getTotalSpace(), disk.getUsableSpace());
  }

  private long readUptimeSeconds() {
    List<String> lines = readAllLines(resolveProcFile("uptime"));
    if (lines.isEmpty()) {
      return 0;
    }
    String[] parts = lines.get(0).trim().split("\\s+");
    return (long) parseDouble(parts.length > 0 ? parts[0] : "0");
  }

  private long readDatabaseSizeBytes() {
    String prefix = "jdbc:sqlite:";
    if (!datasourceUrl.startsWith(prefix)) {
      return 0;
    }
    Path databasePath = Path.of(datasourceUrl.substring(prefix.length()));
    if (!Files.exists(databasePath)) {
      return 0;
    }
    try {
      return Files.size(databasePath);
    } catch (IOException exception) {
      return 0;
    }
  }

  private String readHostName() {
    try {
      String hostName = InetAddress.getLocalHost().getHostName();
      return hostName == null ? "" : hostName;
    } catch (UnknownHostException exception) {
      return "";
    }
  }

  private String readCpuModelName() {
    for (String line : readAllLines(resolveProcFile("cpuinfo"))) {
      String[] parts = line.split(":", 2);
      if (parts.length < 2) {
        continue;
      }
      String key = parts[0].trim().toLowerCase();
      if ("model name".equals(key) || "hardware".equals(key) || "processor".equals(key)) {
        String value = parts[1].trim();
        if (!value.matches("\\d+")) {
          return value;
        }
      }
    }
    return System.getProperty("os.arch", "");
  }

  private Path resolveProcFile(String name) {
    Path hostFile = procPath.resolve(name);
    if (Files.exists(hostFile)) {
      return hostFile;
    }
    return fallbackProcPath.resolve(name);
  }

  private List<String> readAllLines(Path path) {
    try {
      return Files.readAllLines(path);
    } catch (IOException exception) {
      return List.of();
    }
  }

  private long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  private double parseDouble(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  private double percent(long used, long total) {
    if (total <= 0) {
      return 0;
    }
    return clampPercent((double) used * 100 / total);
  }

  private double clampPercent(double value) {
    return Math.max(0, Math.min(100, value));
  }

  private record CpuReading(long totalTicks, long idleTicks) {
  }

  private record CpuSample(CpuReading reading, Instant sampledAt) {
  }

  private record MemoryReading(long memoryTotalBytes, long memoryAvailableBytes, long swapTotalBytes, long swapFreeBytes) {
  }

  private record LoadReading(double loadOne, double loadFive, double loadFifteen) {
  }

  private record NetworkReading(long rxBytes, long txBytes) {
  }

  private record NetworkSample(NetworkReading reading, Instant sampledAt) {
  }

  private record NetworkRate(double rxBytesPerSecond, double txBytesPerSecond) {
  }

  private record DiskReading(long totalBytes, long usableBytes) {
  }
}
