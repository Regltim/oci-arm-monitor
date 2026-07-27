package org.ociarmmonitor.oci;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SyncRunRepositoryTest {

  private SyncRunRepository syncRunRepository;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    syncRunRepository = new SyncRunRepository(new JdbcTemplate(dataSource));
  }

  @Test
  void updateProgressKeepsRunOpenAndStoresLatestMessageAndCounts() {
    String syncRunId = syncRunRepository.start("FULL");

    syncRunRepository.updateProgress(syncRunId, "正在同步实例指标 1/2", 2, 12, 0, 0);

    SyncResult result = syncRunRepository.findById(syncRunId).orElseThrow();
    assertThat(result.status()).isEqualTo("RUNNING");
    assertThat(result.message()).isEqualTo("正在同步实例指标 1/2");
    assertThat(result.finishedAt()).isNull();
    assertThat(result.instanceCount()).isEqualTo(2);
    assertThat(result.metricCount()).isEqualTo(12);
    assertThat(result.trafficCount()).isZero();
    assertThat(result.costCount()).isZero();
  }

  @Test
  void markRunningAsInterruptedClosesUnfinishedRunsAfterRestart() {
    String syncRunId = syncRunRepository.start("FULL");

    int updatedRows = syncRunRepository.markRunningAsInterrupted("服务重启后中断了未完成的同步任务，请重新点击同步。");

    SyncResult result = syncRunRepository.findById(syncRunId).orElseThrow();
    assertThat(updatedRows).isEqualTo(1);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.message()).isEqualTo("服务重启后中断了未完成的同步任务，请重新点击同步。");
    assertThat(result.finishedAt()).isNotBlank();
  }
}
