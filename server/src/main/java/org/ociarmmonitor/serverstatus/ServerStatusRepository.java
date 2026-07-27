package org.ociarmmonitor.serverstatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ServerStatusRepository {

  private final JdbcTemplate jdbcTemplate;
  private final int retentionHours;

  public ServerStatusRepository(
    JdbcTemplate jdbcTemplate,
    @Value("${monitor.server.history-retention-hours:72}") int retentionHours
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.retentionHours = retentionHours;
  }

  public void save(ServerStatusSnapshot snapshot) {
    jdbcTemplate.update("""
      INSERT INTO server_status_snapshot(
        sampled_at, cpu_usage_percent, load_one, load_five, load_fifteen,
        memory_total_bytes, memory_available_bytes, memory_usage_percent,
        swap_total_bytes, swap_free_bytes, swap_usage_percent,
        disk_total_bytes, disk_usable_bytes, disk_usage_percent,
        network_rx_bytes, network_tx_bytes, network_rx_bytes_per_second, network_tx_bytes_per_second,
        uptime_seconds, process_uptime_seconds, jvm_memory_used_bytes, jvm_memory_max_bytes,
        jvm_thread_count, database_size_bytes
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(sampled_at) DO UPDATE SET
        cpu_usage_percent = excluded.cpu_usage_percent,
        load_one = excluded.load_one,
        load_five = excluded.load_five,
        load_fifteen = excluded.load_fifteen,
        memory_total_bytes = excluded.memory_total_bytes,
        memory_available_bytes = excluded.memory_available_bytes,
        memory_usage_percent = excluded.memory_usage_percent,
        swap_total_bytes = excluded.swap_total_bytes,
        swap_free_bytes = excluded.swap_free_bytes,
        swap_usage_percent = excluded.swap_usage_percent,
        disk_total_bytes = excluded.disk_total_bytes,
        disk_usable_bytes = excluded.disk_usable_bytes,
        disk_usage_percent = excluded.disk_usage_percent,
        network_rx_bytes = excluded.network_rx_bytes,
        network_tx_bytes = excluded.network_tx_bytes,
        network_rx_bytes_per_second = excluded.network_rx_bytes_per_second,
        network_tx_bytes_per_second = excluded.network_tx_bytes_per_second,
        uptime_seconds = excluded.uptime_seconds,
        process_uptime_seconds = excluded.process_uptime_seconds,
        jvm_memory_used_bytes = excluded.jvm_memory_used_bytes,
        jvm_memory_max_bytes = excluded.jvm_memory_max_bytes,
        jvm_thread_count = excluded.jvm_thread_count,
        database_size_bytes = excluded.database_size_bytes
      """,
      snapshot.sampledAt(),
      snapshot.cpuUsagePercent(),
      snapshot.loadOne(),
      snapshot.loadFive(),
      snapshot.loadFifteen(),
      snapshot.memoryTotalBytes(),
      snapshot.memoryAvailableBytes(),
      snapshot.memoryUsagePercent(),
      snapshot.swapTotalBytes(),
      snapshot.swapFreeBytes(),
      snapshot.swapUsagePercent(),
      snapshot.diskTotalBytes(),
      snapshot.diskUsableBytes(),
      snapshot.diskUsagePercent(),
      snapshot.networkRxBytes(),
      snapshot.networkTxBytes(),
      snapshot.networkRxBytesPerSecond(),
      snapshot.networkTxBytesPerSecond(),
      snapshot.uptimeSeconds(),
      snapshot.processUptimeSeconds(),
      snapshot.jvmMemoryUsedBytes(),
      snapshot.jvmMemoryMaxBytes(),
      snapshot.jvmThreadCount(),
      snapshot.databaseSizeBytes()
    );
  }

  public List<ServerMetricPoint> listHistory(int hours, int limit) {
    String since = Instant.now().minus(Math.max(hours, 1), ChronoUnit.HOURS).toString();
    List<ServerMetricPoint> points = jdbcTemplate.query("""
      SELECT sampled_at, cpu_usage_percent, memory_usage_percent, disk_usage_percent,
        network_rx_bytes_per_second, network_tx_bytes_per_second
      FROM server_status_snapshot
      WHERE sampled_at >= ?
      ORDER BY sampled_at DESC
      LIMIT ?
      """, (resultSet, rowNum) -> mapMetricPoint(resultSet), since, Math.max(limit, 1));
    Collections.reverse(points);
    return points;
  }

  public Optional<ServerStatusSnapshot> latest() {
    List<ServerStatusSnapshot> snapshots = jdbcTemplate.query("""
      SELECT sampled_at, cpu_usage_percent, load_one, load_five, load_fifteen,
        memory_total_bytes, memory_available_bytes, memory_usage_percent,
        swap_total_bytes, swap_free_bytes, swap_usage_percent,
        disk_total_bytes, disk_usable_bytes, disk_usage_percent,
        network_rx_bytes, network_tx_bytes, network_rx_bytes_per_second, network_tx_bytes_per_second,
        uptime_seconds, process_uptime_seconds, jvm_memory_used_bytes, jvm_memory_max_bytes,
        jvm_thread_count, database_size_bytes
      FROM server_status_snapshot
      ORDER BY sampled_at DESC
      LIMIT 1
      """, (resultSet, rowNum) -> mapSnapshot(resultSet));
    return snapshots.stream().findFirst();
  }

  public void deleteExpired() {
    String before = Instant.now().minus(Math.max(retentionHours, 1), ChronoUnit.HOURS).toString();
    jdbcTemplate.update("DELETE FROM server_status_snapshot WHERE sampled_at < ?", before);
  }

  private ServerMetricPoint mapMetricPoint(ResultSet resultSet) throws SQLException {
    return new ServerMetricPoint(
      resultSet.getString("sampled_at"),
      resultSet.getDouble("cpu_usage_percent"),
      resultSet.getDouble("memory_usage_percent"),
      resultSet.getDouble("disk_usage_percent"),
      resultSet.getDouble("network_rx_bytes_per_second"),
      resultSet.getDouble("network_tx_bytes_per_second")
    );
  }

  private ServerStatusSnapshot mapSnapshot(ResultSet resultSet) throws SQLException {
    return new ServerStatusSnapshot(
      resultSet.getString("sampled_at"),
      resultSet.getDouble("cpu_usage_percent"),
      resultSet.getDouble("load_one"),
      resultSet.getDouble("load_five"),
      resultSet.getDouble("load_fifteen"),
      resultSet.getLong("memory_total_bytes"),
      resultSet.getLong("memory_available_bytes"),
      resultSet.getDouble("memory_usage_percent"),
      resultSet.getLong("swap_total_bytes"),
      resultSet.getLong("swap_free_bytes"),
      resultSet.getDouble("swap_usage_percent"),
      resultSet.getLong("disk_total_bytes"),
      resultSet.getLong("disk_usable_bytes"),
      resultSet.getDouble("disk_usage_percent"),
      resultSet.getLong("network_rx_bytes"),
      resultSet.getLong("network_tx_bytes"),
      resultSet.getDouble("network_rx_bytes_per_second"),
      resultSet.getDouble("network_tx_bytes_per_second"),
      resultSet.getLong("uptime_seconds"),
      resultSet.getLong("process_uptime_seconds"),
      resultSet.getLong("jvm_memory_used_bytes"),
      resultSet.getLong("jvm_memory_max_bytes"),
      resultSet.getInt("jvm_thread_count"),
      resultSet.getLong("database_size_bytes")
    );
  }
}
