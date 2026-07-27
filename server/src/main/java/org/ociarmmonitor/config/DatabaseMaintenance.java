package org.ociarmmonitor.config;

import org.ociarmmonitor.oci.SyncRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMaintenance implements CommandLineRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseMaintenance.class);
  private static final String INTERRUPTED_SYNC_MESSAGE = "服务重启后中断了未完成的同步任务，请重新点击同步。";

  private final JdbcTemplate jdbcTemplate;
  private final SyncRunRepository syncRunRepository;

  public DatabaseMaintenance(JdbcTemplate jdbcTemplate, SyncRunRepository syncRunRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.syncRunRepository = syncRunRepository;
  }

  @Override
  public void run(String... args) {
    removeSeededDemoData();
    markInterruptedSyncRuns();
  }

  private void removeSeededDemoData() {
    jdbcTemplate.update("DELETE FROM metric_point WHERE instance_id = ?", "demo-arm-a1-01");
    jdbcTemplate.update("DELETE FROM traffic_daily WHERE instance_id = ?", "demo-arm-a1-01");
    jdbcTemplate.update("DELETE FROM cost_daily WHERE resource_id = ?", "demo-arm-a1-01");
    jdbcTemplate.update("DELETE FROM cloud_instance WHERE id = ?", "demo-arm-a1-01");
  }

  private void markInterruptedSyncRuns() {
    int updatedRows = syncRunRepository.markRunningAsInterrupted(INTERRUPTED_SYNC_MESSAGE);
    if (updatedRows > 0) {
      LOGGER.warn("Marked {} interrupted OCI sync runs after service startup", updatedRows);
    }
  }
}
