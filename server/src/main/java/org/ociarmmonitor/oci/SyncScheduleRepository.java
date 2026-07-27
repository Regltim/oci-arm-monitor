package org.ociarmmonitor.oci;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SyncScheduleRepository {

  private static final String DEFAULT_ID = "default";
  private static final String DEFAULT_CRON = "0 0 0 * * *";
  private static final String DEFAULT_ZONE = "Asia/Shanghai";

  private final JdbcTemplate jdbcTemplate;

  public SyncScheduleRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public SyncSchedule get() {
    ensureDefault();
    List<SyncSchedule> schedules = jdbcTemplate.query("""
      SELECT enabled, cron_expression, zone_id, sync_on_startup, updated_at
      FROM sync_schedule
      WHERE id = ?
      """, (resultSet, rowNum) -> mapSchedule(resultSet), DEFAULT_ID);
    return schedules.isEmpty() ? defaultSchedule() : schedules.get(0);
  }

  public SyncSchedule update(SyncScheduleUpdateRequest request) {
    ensureDefault();
    String updatedAt = Instant.now().toString();
    jdbcTemplate.update("""
      UPDATE sync_schedule
      SET enabled = ?, cron_expression = ?, zone_id = ?, sync_on_startup = ?, updated_at = ?
      WHERE id = ?
      """,
      request.enabled() ? 1 : 0,
      request.cronExpression(),
      request.zoneId(),
      request.syncOnStartup() ? 1 : 0,
      updatedAt,
      DEFAULT_ID
    );
    return get();
  }

  private void ensureDefault() {
    jdbcTemplate.update("""
      INSERT OR IGNORE INTO sync_schedule(id, enabled, cron_expression, zone_id, sync_on_startup, updated_at)
      VALUES (?, 1, ?, ?, 0, ?)
      """, DEFAULT_ID, DEFAULT_CRON, DEFAULT_ZONE, Instant.now().toString());
  }

  private SyncSchedule defaultSchedule() {
    return new SyncSchedule(true, DEFAULT_CRON, DEFAULT_ZONE, false, Instant.now().toString(), "");
  }

  private SyncSchedule mapSchedule(ResultSet resultSet) throws SQLException {
    return new SyncSchedule(
      resultSet.getInt("enabled") == 1,
      resultSet.getString("cron_expression"),
      resultSet.getString("zone_id"),
      resultSet.getInt("sync_on_startup") == 1,
      resultSet.getString("updated_at"),
      ""
    );
  }
}
