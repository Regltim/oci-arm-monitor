package org.ociarmmonitor.publicreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ociarmmonitor.cost.CostDaily;
import org.ociarmmonitor.cost.CostSummary;
import org.ociarmmonitor.cost.ManualCost;
import org.ociarmmonitor.notification.DailyReportContext;
import org.ociarmmonitor.notification.DailyReportData;
import org.ociarmmonitor.oci.SyncResult;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.ociarmmonitor.traffic.TrafficDaily;
import org.ociarmmonitor.traffic.TrafficSummary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class PublicReportServiceTest {

  private static final Clock FIXED_CLOCK = Clock.fixed(
    Instant.parse("2026-07-28T01:00:00Z"),
    ZoneOffset.UTC
  );

  private JdbcTemplate jdbcTemplate;
  private ObjectMapper objectMapper;
  private PublicReportService service;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    jdbcTemplate = new JdbcTemplate(dataSource);
    objectMapper = new ObjectMapper();
    service = new PublicReportService(
      jdbcTemplate,
      objectMapper,
      new PublicReportSnapshotMapper(),
      FIXED_CLOCK,
      new SecureRandom()
    );
  }

  @Test
  void createsOneDaySnapshotAndStoresOnlyTokenHash() {
    PublicReportSnapshot snapshot = service.createSnapshot(reportData(), 1);
    PublicReportAccess firstAccess = service.issueAccess(snapshot, "https://monitor.example.com");
    PublicReportAccess secondAccess = service.issueAccess(snapshot, "https://monitor.example.com");

    assertThat(snapshot.expiresAt()).isEqualTo("2026-07-29T01:00:00Z");
    assertThat(firstAccess.token()).hasSize(43).isNotEqualTo(secondAccess.token());
    assertThat(firstAccess.url()).isEqualTo(
      "https://monitor.example.com/#/r/" + snapshot.id() + "?token=" + firstAccess.token()
    );
    assertThat(secondAccess.url()).isNotEqualTo(firstAccess.url());

    List<String> storedTokenHashes = jdbcTemplate.queryForList(
      "SELECT token_hash FROM public_report_access ORDER BY id",
      String.class
    );
    assertThat(storedTokenHashes)
      .hasSize(2)
      .allSatisfy(hash -> assertThat(hash).doesNotContain(firstAccess.token()).doesNotContain(secondAccess.token()));
    assertThat(service.find(snapshot.id(), firstAccess.token())).isPresent();
    assertThat(jdbcTemplate.queryForObject(
      "SELECT access_count FROM public_report_access WHERE snapshot_id = ? AND token_hash = ?",
      Integer.class,
      snapshot.id(),
      service.hashToken(firstAccess.token())
    )).isEqualTo(1);
  }

  @Test
  void rejectsHttpOriginBeforeIssuingAnAccessToken() {
    PublicReportSnapshot snapshot = service.createSnapshot(reportData(), 1);

    assertThatThrownBy(() -> service.issueAccess(snapshot, "http://monitor.example.com"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("免登录明细需要配置有效的 HTTPS MONITOR_PUBLIC_URL");
    assertThat(jdbcTemplate.queryForObject(
      "SELECT COUNT(*) FROM public_report_access",
      Integer.class
    )).isZero();
  }

  @Test
  void hidesWrongExpiredAndRevokedTokensWithTheSameResult() {
    PublicReportSnapshot snapshot = service.createSnapshot(reportData(), 1);
    PublicReportAccess validAccess = service.issueAccess(snapshot, "https://monitor.example.com");
    PublicReportAccess expiredAccess = service.issueAccess(snapshot, "https://monitor.example.com");
    PublicReportAccess revokedAccess = service.issueAccess(snapshot, "https://monitor.example.com");
    jdbcTemplate.update(
      "UPDATE public_report_access SET expires_at = ? WHERE token_hash = ?",
      "2026-07-28T00:59:59Z",
      service.hashToken(expiredAccess.token())
    );
    jdbcTemplate.update(
      "UPDATE public_report_access SET revoked_at = ? WHERE token_hash = ?",
      "2026-07-28T01:00:00Z",
      service.hashToken(revokedAccess.token())
    );

    assertThat(service.find(snapshot.id(), "wrong-token")).isEmpty();
    assertThat(service.find(snapshot.id(), expiredAccess.token())).isEmpty();
    assertThat(service.find(snapshot.id(), revokedAccess.token())).isEmpty();
    assertThat(service.find(snapshot.id(), validAccess.token())).isPresent();
  }

  @Test
  void snapshotPayloadOmitsCloudIdentifiersIpsRecipientsAndCredentials() throws Exception {
    PublicReportSnapshot snapshot = service.createSnapshot(reportData(), 1);
    PublicReportAccess access = service.issueAccess(snapshot, "https://monitor.example.com");
    PublicReportView report = service.find(snapshot.id(), access.token()).orElseThrow();
    String json = objectMapper.writeValueAsString(report);

    assertThat(json)
      .contains("monitor-instance")
      .contains("12.34")
      .contains("67.89")
      .doesNotContain("ocid1.instance.oc1..sensitive")
      .doesNotContain("ocid1.compartment.oc1..sensitive")
      .doesNotContain("203.0.113.20")
      .doesNotContain("2001:db8::1")
      .doesNotContain("openid_sensitive")
      .doesNotContain("wx_sensitive_secret")
      .doesNotContain("resourceId")
      .doesNotContain("instanceId")
      .doesNotContain("sensitive sync message");
  }

  private DailyReportData reportData() {
    SyncResult syncResult = new SyncResult(
      "SUCCESS",
      "sensitive sync message ocid1.compartment.oc1..sensitive 203.0.113.20",
      "2026-07-28T00:00:00Z",
      "2026-07-28T00:02:00Z",
      1,
      2,
      1,
      1
    );
    return new DailyReportData(
      new DailyReportContext(
        FIXED_CLOCK.instant(),
        ZoneId.of("Asia/Shanghai"),
        LocalDate.of(2026, 7, 28),
        YearMonth.of(2026, 7)
      ),
      List.of(new DailyReportData.InstanceStatus(
        "monitor-instance",
        "RUNNING",
        OptionalDouble.of(42.5),
        OptionalDouble.of(51.2)
      )),
      hostStatus(),
      List.of(new ServerAlert(
        "cpu_usage_percent",
        "warning",
        "CPU 使用率",
        "CPU 使用率偏高，来源 2001:db8::1",
        92,
        90,
        "%"
      )),
      syncResult,
      syncResult,
      new CostSummary(
        12.34,
        8,
        20.34,
        28.62,
        "CNY",
        List.of(new CostDaily(
          "Compute",
          "ocid1.instance.oc1..sensitive",
          "2026-07-28",
          1,
          "OCPU_HOUR",
          12.34,
          "CNY"
        )),
        List.of(new ManualCost(
          "manual-sensitive-id",
          "域名",
          "OTHER",
          8,
          "CNY",
          "2026-07-28",
          "203.0.113.20",
          "2026-07-28T00:00:00Z"
        ))
      ),
      new TrafficSummary(
        123.45,
        67.89,
        10_000,
        0.68,
        List.of(new TrafficDaily("ocid1.instance.oc1..sensitive", "2026-07-28", 123.45, 67.89))
      )
    );
  }

  private ServerStatusSnapshot hostStatus() {
    return new ServerStatusSnapshot(
      "2026-07-28T01:00:00Z",
      42.5,
      0.5,
      0.4,
      0.3,
      16_000,
      8_000,
      50,
      4_000,
      3_000,
      25,
      100_000,
      40_000,
      60,
      1_000,
      2_000,
      10,
      20,
      86_400,
      3_600,
      100,
      200,
      20,
      10_000
    );
  }
}
