package org.ociarmmonitor.oci;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SyncRunRepository {

  private final JdbcTemplate jdbcTemplate;

  public SyncRunRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public String start(String syncType) {
    String id = UUID.randomUUID().toString();
    jdbcTemplate.update("""
      INSERT INTO sync_run(id, sync_type, status, message, started_at, instance_count, metric_count, traffic_count, cost_count)
      VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0)
      """, id, syncType, "RUNNING", "同步任务已开始，正在后台拉取 OCI 数据。", Instant.now().toString());
    return id;
  }

  public void updateProgress(String id, String message, int instanceCount, int metricCount, int trafficCount, int costCount) {
    jdbcTemplate.update("""
      UPDATE sync_run
      SET status = ?, message = ?, instance_count = ?, metric_count = ?, traffic_count = ?, cost_count = ?
      WHERE id = ?
      """, "RUNNING", message, instanceCount, metricCount, trafficCount, costCount, id);
  }

  public SyncResult finish(String id, String status, String message, int instanceCount, int metricCount, int trafficCount, int costCount) {
    String finishedAt = Instant.now().toString();
    jdbcTemplate.update("""
      UPDATE sync_run
      SET status = ?, message = ?, finished_at = ?, instance_count = ?, metric_count = ?, traffic_count = ?, cost_count = ?
      WHERE id = ?
      """, status, message, finishedAt, instanceCount, metricCount, trafficCount, costCount, id);
    return findById(id).orElse(new SyncResult(status, message, "", finishedAt, instanceCount, metricCount, trafficCount, costCount));
  }

  public int markRunningAsInterrupted(String message) {
    return jdbcTemplate.update("""
      UPDATE sync_run
      SET status = ?, message = ?, finished_at = ?
      WHERE status = ? AND finished_at IS NULL
      """, "FAILED", message, Instant.now().toString(), "RUNNING");
  }

  public Optional<SyncResult> latest() {
    List<SyncResult> results = jdbcTemplate.query("""
      SELECT status, message, started_at, finished_at, instance_count, metric_count, traffic_count, cost_count
      FROM sync_run
      ORDER BY started_at DESC
      LIMIT 1
      """, (resultSet, rowNum) -> mapResult(resultSet));
    return results.stream().findFirst();
  }

  public List<SyncRunRecord> listRecent(int limit) {
    return jdbcTemplate.query("""
      SELECT id, sync_type, status, message, started_at, finished_at, instance_count, metric_count, traffic_count, cost_count
      FROM sync_run
      ORDER BY started_at DESC
      LIMIT ?
      """, (resultSet, rowNum) -> mapRecord(resultSet), Math.max(limit, 1));
  }

  public Optional<SyncResult> findById(String id) {
    List<SyncResult> results = jdbcTemplate.query("""
      SELECT status, message, started_at, finished_at, instance_count, metric_count, traffic_count, cost_count
      FROM sync_run
      WHERE id = ?
      """, (resultSet, rowNum) -> mapResult(resultSet), id);
    return results.stream().findFirst();
  }

  private SyncResult mapResult(ResultSet resultSet) throws SQLException {
    return new SyncResult(
      resultSet.getString("status"),
      resultSet.getString("message"),
      resultSet.getString("started_at"),
      resultSet.getString("finished_at"),
      resultSet.getInt("instance_count"),
      resultSet.getInt("metric_count"),
      resultSet.getInt("traffic_count"),
      resultSet.getInt("cost_count")
    );
  }

  private SyncRunRecord mapRecord(ResultSet resultSet) throws SQLException {
    return new SyncRunRecord(
      resultSet.getString("id"),
      resultSet.getString("sync_type"),
      resultSet.getString("status"),
      resultSet.getString("message"),
      resultSet.getString("started_at"),
      resultSet.getString("finished_at"),
      resultSet.getInt("instance_count"),
      resultSet.getInt("metric_count"),
      resultSet.getInt("traffic_count"),
      resultSet.getInt("cost_count")
    );
  }
}
