package org.ociarmmonitor.notification;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WechatDailySummaryStateRepository {

  private final JdbcTemplate jdbcTemplate;

  public WechatDailySummaryStateRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean tryClaim(String reportType, LocalDate localDate, String updatedAt) {
    int affectedRows = jdbcTemplate.update("""
      INSERT INTO wechat_daily_summary_state(id, last_attempted_date, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        last_attempted_date = excluded.last_attempted_date,
        updated_at = excluded.updated_at
      WHERE wechat_daily_summary_state.last_attempted_date <> excluded.last_attempted_date
      """, reportType, localDate.toString(), updatedAt);
    return affectedRows == 1;
  }
}
