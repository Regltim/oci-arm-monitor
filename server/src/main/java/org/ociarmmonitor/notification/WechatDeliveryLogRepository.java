package org.ociarmmonitor.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WechatDeliveryLogRepository {

  private static final int MAX_HISTORY = 100;

  private final JdbcTemplate jdbcTemplate;

  public WechatDeliveryLogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void save(WechatDeliveryResult result) {
    jdbcTemplate.update("""
      INSERT INTO wechat_delivery_log(
        notification_type, metric_name, success_count, failure_count, message, created_at
      )
      VALUES (?, ?, ?, ?, ?, ?)
      """,
      result.notificationType(),
      result.metricName(),
      result.successCount(),
      result.failureCount(),
      result.message(),
      result.createdAt()
    );
    jdbcTemplate.update("""
      DELETE FROM wechat_delivery_log
      WHERE id NOT IN (
        SELECT id FROM wechat_delivery_log ORDER BY id DESC LIMIT ?
      )
      """, MAX_HISTORY);
  }

  public List<WechatDeliveryResult> listRecent(int limit) {
    return jdbcTemplate.query("""
      SELECT notification_type, metric_name, success_count, failure_count, message, created_at
      FROM wechat_delivery_log
      ORDER BY id DESC
      LIMIT ?
      """, (resultSet, rowNum) -> mapResult(resultSet), Math.min(Math.max(limit, 1), MAX_HISTORY));
  }

  private WechatDeliveryResult mapResult(ResultSet resultSet) throws SQLException {
    return new WechatDeliveryResult(
      resultSet.getString("notification_type"),
      resultSet.getString("metric_name"),
      resultSet.getInt("success_count"),
      resultSet.getInt("failure_count"),
      resultSet.getString("message"),
      resultSet.getString("created_at")
    );
  }
}
