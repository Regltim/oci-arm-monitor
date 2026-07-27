package org.ociarmmonitor.traffic;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrafficRepository {

  private final JdbcTemplate jdbcTemplate;

  public TrafficRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void save(TrafficDaily trafficDaily) {
    jdbcTemplate.update("""
      INSERT INTO traffic_daily(instance_id, stat_date, ingress_gb, egress_gb)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(instance_id, stat_date) DO UPDATE SET
        ingress_gb = excluded.ingress_gb,
        egress_gb = excluded.egress_gb
      """,
      trafficDaily.instanceId(),
      trafficDaily.statDate(),
      trafficDaily.ingressGb(),
      trafficDaily.egressGb()
    );
  }

  public void deleteSince(String statDate) {
    jdbcTemplate.update("DELETE FROM traffic_daily WHERE stat_date >= ?", statDate);
  }

  public List<TrafficDaily> listCurrentMonth() {
    String monthPrefix = LocalDate.now().withDayOfMonth(1).toString().substring(0, 7);
    return jdbcTemplate.query("""
      SELECT instance_id, stat_date, ingress_gb, egress_gb
      FROM traffic_daily
      WHERE stat_date LIKE ?
      ORDER BY stat_date ASC
      """, (resultSet, rowNum) -> mapTraffic(resultSet), monthPrefix + "%");
  }

  public double sumIngressForCurrentMonth() {
    return sumCurrentMonth("ingress_gb");
  }

  public double sumEgressForCurrentMonth() {
    return sumCurrentMonth("egress_gb");
  }

  public double egressByInstanceAndDate(String instanceId, LocalDate date) {
    Double value = jdbcTemplate.queryForObject("""
      SELECT COALESCE(SUM(egress_gb), 0)
      FROM traffic_daily
      WHERE instance_id = ? AND stat_date = ?
      """, Double.class, instanceId, date.toString());
    return value == null ? 0 : value;
  }

  private double sumCurrentMonth(String columnName) {
    String monthPrefix = LocalDate.now().withDayOfMonth(1).toString().substring(0, 7);
    Double value = jdbcTemplate.queryForObject("""
      SELECT COALESCE(SUM(%s), 0)
      FROM traffic_daily
      WHERE stat_date LIKE ?
      """.formatted(columnName), Double.class, monthPrefix + "%");
    return value == null ? 0 : value;
  }

  private TrafficDaily mapTraffic(ResultSet resultSet) throws SQLException {
    return new TrafficDaily(
      resultSet.getString("instance_id"),
      resultSet.getString("stat_date"),
      resultSet.getDouble("ingress_gb"),
      resultSet.getDouble("egress_gb")
    );
  }
}
