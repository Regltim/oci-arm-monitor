package org.ociarmmonitor.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ociarmmonitor.config.FreeQuota;
import org.ociarmmonitor.config.FreeQuotaRepository;
import org.ociarmmonitor.cost.CostDaily;
import org.ociarmmonitor.cost.CostRepository;
import org.ociarmmonitor.cost.CostService;
import org.ociarmmonitor.cost.ManualCostCreateRequest;
import org.ociarmmonitor.cost.ManualCostRepository;
import org.ociarmmonitor.instance.CloudInstance;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricPoint;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.oci.SyncRunRepository;
import org.ociarmmonitor.serverstatus.AlertRuleRepository;
import org.ociarmmonitor.serverstatus.ServerAlertService;
import org.ociarmmonitor.serverstatus.ServerStatusRepository;
import org.ociarmmonitor.traffic.TrafficDaily;
import org.ociarmmonitor.traffic.TrafficRepository;
import org.ociarmmonitor.traffic.TrafficService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class DailyReportDataProviderTest {

  private JdbcTemplate jdbcTemplate;
  private CloudInstanceRepository instanceRepository;
  private MetricRepository metricRepository;
  private CostRepository costRepository;
  private ManualCostRepository manualCostRepository;
  private TrafficRepository trafficRepository;
  private FreeQuotaRepository freeQuotaRepository;
  private DailyReportDataProvider provider;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update("UPDATE alert_rule SET enabled = 0");
    instanceRepository = new CloudInstanceRepository(jdbcTemplate);
    metricRepository = new MetricRepository(jdbcTemplate);
    costRepository = new CostRepository(jdbcTemplate);
    manualCostRepository = new ManualCostRepository(jdbcTemplate);
    trafficRepository = new TrafficRepository(jdbcTemplate);
    freeQuotaRepository = new FreeQuotaRepository(jdbcTemplate);
    SyncRunRepository syncRunRepository = new SyncRunRepository(jdbcTemplate);
    provider = new DailyReportDataProvider(
      instanceRepository,
      metricRepository,
      new ServerStatusRepository(jdbcTemplate, 72),
      new ServerAlertService(new AlertRuleRepository(jdbcTemplate)),
      syncRunRepository,
      new CostService(costRepository, manualCostRepository),
      new TrafficService(trafficRepository, freeQuotaRepository)
    );
  }

  @Test
  void loadsOneTimezoneAwareSnapshotAndDistinguishesZeroFromMissingMetrics() {
    instanceRepository.save(new CloudInstance(
      "instance-1",
      "arm-app-01",
      "region-example-1",
      "compartment-placeholder",
      "VM.Standard.A1.Flex",
      "RUNNING",
      1,
      6,
      50,
      "",
      "",
      "2026-01-01T00:00:00Z",
      "2026-07-27T00:00:00Z"
    ));
    metricRepository.insert(new MetricPoint(
      "instance-1",
      "cpu_utilization",
      0,
      "%",
      "2026-07-27T00:30:00Z"
    ));
    costRepository.save(new CostDaily("Compute", "instance-1", "2026-07-10", 1, "unit", 12, "CNY"));
    costRepository.save(new CostDaily("Compute", "instance-1", "2026-08-01", 1, "unit", 99, "CNY"));
    manualCostRepository.create(new ManualCostCreateRequest("域名", "其他", 8, "CNY", "2026-07-05", ""));
    trafficRepository.save(new TrafficDaily("instance-1", "2026-07-10", 123, 67));
    trafficRepository.save(new TrafficDaily("instance-1", "2026-08-01", 999, 999));
    freeQuotaRepository.save(new FreeQuota(1500, 9000, 200, 10_000, 500_000_000, 1_000_000_000, "2026-07-01T00:00:00Z"));
    insertSync("sync-success", "SUCCESS", "同步完成", "2026-07-27T00:02:16Z");
    insertSync("sync-failed", "FAILED", "临时失败", "2026-07-27T00:41:00Z");

    DailyReportData data = provider.load(new DailyReportContext(
      Instant.parse("2026-07-27T01:00:00Z"),
      ZoneId.of("Asia/Shanghai"),
      LocalDate.of(2026, 7, 27),
      YearMonth.of(2026, 7)
    ));

    assertThat(data.instances()).hasSize(1);
    assertThat(data.instances().get(0).cpuUtilization()).hasValue(0);
    assertThat(data.instances().get(0).memoryUtilization()).isEmpty();
    assertThat(data.hostStatus()).isNull();
    assertThat(data.latestSync().status()).isEqualTo("FAILED");
    assertThat(data.latestSuccessfulSync().status()).isEqualTo("SUCCESS");
    assertThat(data.costs().ociCostThisMonth()).isEqualTo(12);
    assertThat(data.costs().manualCostThisMonth()).isEqualTo(8);
    assertThat(data.costs().totalCostThisMonth()).isEqualTo(20);
    assertThat(data.traffic().ingressGbThisMonth()).isEqualTo(123);
    assertThat(data.traffic().egressGbThisMonth()).isEqualTo(67);
    assertThat(data.traffic().outboundQuotaGb()).isEqualTo(10_000);
  }

  @Test
  void derivesReportMonthFromConfiguredZoneInsteadOfSystemZone() {
    DailyReportContext context = DailyReportContext.from(
      Clock.fixed(Instant.parse("2026-07-31T16:30:00Z"), ZoneOffset.UTC),
      ZoneId.of("Asia/Shanghai")
    );

    assertThat(context.localDate()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(context.yearMonth()).isEqualTo(YearMonth.of(2026, 8));
  }

  private void insertSync(String id, String status, String message, String finishedAt) {
    jdbcTemplate.update("""
      INSERT INTO sync_run(
        id, sync_type, status, message, started_at, finished_at,
        instance_count, metric_count, traffic_count, cost_count
      ) VALUES (?, 'FULL', ?, ?, ?, ?, 1, 1, 1, 1)
      """, id, status, message, finishedAt, finishedAt);
  }
}
