package org.ociarmmonitor.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.ociarmmonitor.cost.CostSummary;
import org.ociarmmonitor.oci.SyncResult;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;
import org.ociarmmonitor.traffic.TrafficSummary;

class DailyReportMessageAssemblerTest {

  private final DailyReportMessageAssembler assembler = new DailyReportMessageAssembler();

  @Test
  void formatsDetailedStatusAndCostTrafficMessagesWithoutWebsiteHints() {
    DailyReportContext context = context();
    DailyReportData data = new DailyReportData(
      context,
      List.of(
        new DailyReportData.InstanceStatus("arm-app-01", "RUNNING", OptionalDouble.of(12.3), OptionalDouble.of(41.2)),
        new DailyReportData.InstanceStatus("arm-backup", "STOPPED", OptionalDouble.empty(), OptionalDouble.empty())
      ),
      snapshot(),
      List.of(new ServerAlert(
        "disk_usage_percent",
        "warning",
        "磁盘使用率",
        "磁盘使用率当前 91.00%，阈值 80.00%。",
        91,
        80,
        "%"
      )),
      new SyncResult("FAILED", "临时网络失败", "2026-07-27T00:40:00Z", "2026-07-27T00:41:00Z", 0, 0, 0, 0),
      new SyncResult("SUCCESS", "同步完成", "2026-07-26T23:59:00Z", "2026-07-27T00:02:16Z", 2, 10, 2, 3),
      new CostSummary(12.34, 8, 20.34, 28.62, "CNY", List.of(), List.of()),
      new TrafficSummary(123.45, 67.89, 10_000, 0.6789, List.of())
    );

    WechatTemplateMessage status = assembler.statusMessage(data);
    WechatTemplateMessage costTraffic = assembler.costTrafficMessage(data);

    assertThat(status.first()).isEqualTo("OCI ARM Monitor 每日运行状态");
    assertThat(status.level()).isEqualTo("警告");
    assertThat(status.status()).isEqualTo("2 台实例，1 项活动告警");
    assertThat(status.content())
      .contains("实例汇总：运行 1 台，停止 1 台，其他 0 台")
      .contains("arm-app-01：运行中，CPU 12.30%，内存 41.20%")
      .contains("监控主机：CPU 18.20%，内存 62.50%，磁盘 71.30%")
      .contains("活动告警：磁盘使用率")
      .contains("OCI 同步：失败，2026-07-27 08:41:00")
      .contains("最近成功：2026-07-27 08:02:16");
    assertThat(status.remark()).doesNotContain("点击").doesNotContain("http");

    assertThat(costTraffic.first()).isEqualTo("OCI ARM Monitor 费用与流量日报");
    assertThat(costTraffic.content())
      .contains("OCI 费用：¥12.34")
      .contains("手工费用：¥8.00")
      .contains("本月总费用：¥20.34")
      .contains("月底费用预测：¥28.62")
      .contains("入站流量：123.45 GB")
      .contains("出站流量：67.89 GB")
      .contains("出站免费额度：10,000.00 GB")
      .contains("额度使用率：0.68%")
      .contains("剩余额度：9,932.11 GB")
      .contains("数据同步：2026-07-27 08:02:16");
    assertThat(costTraffic.remark()).doesNotContain("点击").doesNotContain("http");
  }

  @Test
  void showsUnknownOciValuesAndUnconfiguredQuotaInsteadOfZeroUsage() {
    DailyReportData data = new DailyReportData(
      context(),
      List.of(),
      null,
      List.of(),
      null,
      null,
      new CostSummary(0, 8, 8, 12, "CNY", List.of(), List.of()),
      new TrafficSummary(0, 0, 0, 0, List.of())
    );

    WechatTemplateMessage status = assembler.statusMessage(data);
    WechatTemplateMessage costTraffic = assembler.costTrafficMessage(data);

    assertThat(status.content())
      .contains("暂无 OCI 实例数据")
      .contains("暂无主机采样数据")
      .contains("活动告警：无")
      .contains("OCI 同步：暂无记录");
    assertThat(costTraffic.content())
      .contains("OCI 费用：暂无同步数据")
      .contains("手工费用：¥8.00")
      .contains("本月总费用：无法计算")
      .contains("入站流量：暂无同步数据")
      .contains("出站免费额度：未配置")
      .doesNotContain("额度使用率：0.00%");
  }

  @Test
  void sanitizesExternalTextAndLimitsInstanceAndContentLength() {
    List<DailyReportData.InstanceStatus> instances = new ArrayList<>();
    for (int index = 0; index < 30; index++) {
      instances.add(new DailyReportData.InstanceStatus(
        "instance-" + index + "\n恶意换行-" + "很长的名称".repeat(20),
        "RUNNING",
        OptionalDouble.of(index),
        OptionalDouble.of(index)
      ));
    }
    DailyReportData data = new DailyReportData(
      context(),
      instances,
      snapshot(),
      List.of(),
      new SyncResult("FAILED", "错误\n详情" + "x".repeat(500), "", "2026-07-27T00:41:00Z", 0, 0, 0, 0),
      null,
      new CostSummary(0, 0, 0, 0, "CNY", List.of(), List.of()),
      new TrafficSummary(0, 0, 10_000, 0, List.of())
    );

    String content = assembler.statusMessage(data).content();

    assertThat(content.codePointCount(0, content.length())).isLessThanOrEqualTo(1800);
    assertThat(content).contains("另有 20 台实例未展开");
    assertThat(content).doesNotContain("instance-0\n恶意换行");
  }

  private DailyReportContext context() {
    return new DailyReportContext(
      Instant.parse("2026-07-27T01:00:00Z"),
      ZoneId.of("Asia/Shanghai"),
      LocalDate.of(2026, 7, 27),
      YearMonth.of(2026, 7)
    );
  }

  private ServerStatusSnapshot snapshot() {
    return new ServerStatusSnapshot(
      "2026-07-27T00:59:00Z",
      18.2,
      0.5,
      0.4,
      0.3,
      16_000,
      6_192,
      62.5,
      4_000,
      4_000,
      0,
      100_000,
      28_700,
      71.3,
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
