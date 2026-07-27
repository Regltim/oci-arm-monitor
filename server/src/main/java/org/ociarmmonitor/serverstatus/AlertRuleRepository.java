package org.ociarmmonitor.serverstatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRuleRepository {

  private final JdbcTemplate jdbcTemplate;

  public AlertRuleRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<AlertRule> findAll() {
    return jdbcTemplate.query("""
      SELECT id, metric_name, operator, threshold, severity, enabled, created_at, updated_at
      FROM alert_rule
      ORDER BY id ASC
      """, (resultSet, rowNum) -> mapRule(resultSet));
  }

  public Optional<AlertRule> findById(String id) {
    List<AlertRule> rules = jdbcTemplate.query("""
      SELECT id, metric_name, operator, threshold, severity, enabled, created_at, updated_at
      FROM alert_rule
      WHERE id = ?
      """, (resultSet, rowNum) -> mapRule(resultSet), id);
    return rules.stream().findFirst();
  }

  public AlertRule update(String id, AlertRuleUpdateRequest request) {
    String updatedAt = Instant.now().toString();
    jdbcTemplate.update("""
      UPDATE alert_rule
      SET operator = ?, threshold = ?, severity = ?, enabled = ?, updated_at = ?
      WHERE id = ?
      """,
      request.operator(),
      request.threshold(),
      request.severity(),
      request.enabled() ? 1 : 0,
      updatedAt,
      id
    );
    return findById(id).orElseThrow(() -> new IllegalArgumentException("告警规则不存在：" + id));
  }

  private AlertRule mapRule(ResultSet resultSet) throws SQLException {
    return new AlertRule(
      resultSet.getString("id"),
      resultSet.getString("metric_name"),
      resultSet.getString("operator"),
      resultSet.getDouble("threshold"),
      resultSet.getString("severity"),
      resultSet.getInt("enabled") == 1,
      resultSet.getString("created_at"),
      resultSet.getString("updated_at")
    );
  }
}
