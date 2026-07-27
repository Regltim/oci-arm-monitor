package org.ociarmmonitor.notification;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WechatDailySummaryStateRepository {

  private static final String DEFAULT_ID = "default";

  private final JdbcTemplate jdbcTemplate;

  public WechatDailySummaryStateRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<String> lastAttemptedDate() {
    List<String> dates = jdbcTemplate.query(
      "SELECT last_attempted_date FROM wechat_daily_summary_state WHERE id = ?",
      (resultSet, rowNum) -> resultSet.getString("last_attempted_date"),
      DEFAULT_ID
    );
    return dates.stream().findFirst();
  }

  public void markAttempted(LocalDate localDate, String updatedAt) {
    jdbcTemplate.update("""
      INSERT INTO wechat_daily_summary_state(id, last_attempted_date, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        last_attempted_date = excluded.last_attempted_date,
        updated_at = excluded.updated_at
      """, DEFAULT_ID, localDate.toString(), updatedAt);
  }
}
