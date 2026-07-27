package org.ociarmmonitor.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlertNotificationStateRepository {

  private final JdbcTemplate jdbcTemplate;

  public AlertNotificationStateRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<AlertNotificationState> find(String metricName) {
    List<AlertNotificationState> states = jdbcTemplate.query("""
      SELECT metric_name, active, severity, title, description, current_value,
        threshold, unit, changed_at, last_notified_at
      FROM alert_notification_state
      WHERE metric_name = ?
      """, (resultSet, rowNum) -> mapState(resultSet), metricName);
    return states.stream().findFirst();
  }

  public List<AlertNotificationState> findActive() {
    return jdbcTemplate.query("""
      SELECT metric_name, active, severity, title, description, current_value,
        threshold, unit, changed_at, last_notified_at
      FROM alert_notification_state
      WHERE active = 1
      ORDER BY metric_name
      """, (resultSet, rowNum) -> mapState(resultSet));
  }

  public void activate(ServerAlert alert, String changedAt) {
    jdbcTemplate.update("""
      INSERT INTO alert_notification_state(
        metric_name, active, severity, title, description, current_value,
        threshold, unit, changed_at, last_notified_at
      )
      VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, NULL)
      ON CONFLICT(metric_name) DO UPDATE SET
        active = 1,
        severity = excluded.severity,
        title = excluded.title,
        description = excluded.description,
        current_value = excluded.current_value,
        threshold = excluded.threshold,
        unit = excluded.unit,
        changed_at = excluded.changed_at,
        last_notified_at = NULL
      """,
      alert.metricName(),
      alert.severity(),
      alert.title(),
      alert.description(),
      alert.currentValue(),
      alert.threshold(),
      alert.unit(),
      changedAt
    );
  }

  public void recover(String metricName, String changedAt) {
    jdbcTemplate.update("""
      UPDATE alert_notification_state
      SET active = 0, changed_at = ?, last_notified_at = NULL
      WHERE metric_name = ?
      """, changedAt, metricName);
  }

  public void markNotified(String metricName, String notifiedAt) {
    jdbcTemplate.update("""
      UPDATE alert_notification_state
      SET last_notified_at = ?
      WHERE metric_name = ?
      """, notifiedAt, metricName);
  }

  private AlertNotificationState mapState(ResultSet resultSet) throws SQLException {
    return new AlertNotificationState(
      resultSet.getString("metric_name"),
      resultSet.getInt("active") == 1,
      resultSet.getString("severity"),
      resultSet.getString("title"),
      resultSet.getString("description"),
      resultSet.getDouble("current_value"),
      resultSet.getDouble("threshold"),
      resultSet.getString("unit"),
      resultSet.getString("changed_at"),
      resultSet.getString("last_notified_at")
    );
  }
}
