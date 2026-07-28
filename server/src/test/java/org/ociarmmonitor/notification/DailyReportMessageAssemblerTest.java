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
  void formatsStatusAsOneSummaryCardAndKeepsDetailsForTheH5Snapshot() {
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

    List<WechatTemplateMessage> statusMessages = assembler.statusMessages(data);
    WechatTemplateMessage costTraffic = assembler.costTrafficMessage(data);

    assertThat(statusMessages).hasSize(1);
    WechatTemplateMessage summary = statusMessages.get(0);
    assertThat(summary.first()).isEqualTo("OCI ARM Monitor 每日运行状态");
    assertThat(summary.item1()).isEqualTo("实例：共 2 台｜运行 1｜停止 1｜其他 0");
    assertThat(summary.item2()).isEqualTo("主机：CPU 18.20%｜内存 62.50%｜磁盘 71.30%");
    assertThat(summary.item3())
      .contains("告警：1 项")
      .contains("同步：失败 2026-07-27 08:41:00")
      .contains("最近成功 2026-07-27 08:02:16");

    assertThat(statusMessages).allSatisfy(message -> visibleValues(message).forEach(value ->
      assertThat(value).doesNotContain("点击").doesNotContain("http").doesNotContain("\n")
    ));

    assertThat(costTraffic.first()).isEqualTo("OCI ARM Monitor 费用与流量");
    assertThat(costTraffic.item1())
      .isEqualTo("费用：OCI ¥12.34｜手工 ¥8.00｜总计 ¥20.34｜预测 ¥28.62");
    assertThat(costTraffic.item2())
      .isEqualTo("流量：入站 123.45 GB｜出站 67.89 GB｜额度 10,000.00 GB");
    assertThat(costTraffic.item3())
      .isEqualTo("额度：已用 0.68%｜剩余 9,932.11 GB｜同步 2026-07-27 08:02:16");
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

    List<WechatTemplateMessage> statusMessages = assembler.statusMessages(data);
    WechatTemplateMessage costTraffic = assembler.costTrafficMessage(data);

    assertThat(statusMessages).hasSize(1);
    assertThat(statusMessages.get(0).item1()).isEqualTo("实例：暂无 OCI 实例数据");
    assertThat(statusMessages.get(0).item2()).isEqualTo("主机：暂无采样数据");
    assertThat(statusMessages.get(0).item3()).isEqualTo("告警：无｜同步：暂无记录");
    assertThat(costTraffic.item1())
      .isEqualTo("费用：OCI 暂无同步数据｜手工 ¥8.00｜总计 无法计算｜预测 无法计算");
    assertThat(costTraffic.item2())
      .isEqualTo("流量：入站 暂无同步数据｜出站 暂无同步数据｜额度 未配置");
    assertThat(costTraffic.item3()).isEqualTo("额度：无法计算｜同步 暂无成功记录");
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

    List<WechatTemplateMessage> messages = assembler.statusMessages(data);
    List<String> visibleValues = messages.stream().flatMap(message -> visibleValues(message).stream()).toList();

    assertThat(messages).hasSize(1);
    assertThat(visibleValues).allSatisfy(value -> {
      assertThat(value.codePointCount(0, value.length())).isLessThanOrEqualTo(180);
      assertThat(value).doesNotContain("\n");
    });
    assertThat(String.join("|", visibleValues)).doesNotContain("instance-0");
  }

  @Test
  void keepsAlertCountInSummaryWithoutSendingSupplementCards() {
    List<ServerAlert> alerts = new ArrayList<>();
    for (int index = 1; index <= 7; index++) {
      alerts.add(new ServerAlert(
        "metric_" + index,
        index == 1 ? "danger" : "warning",
        "告警 " + index,
        "第 " + index + " 项\n告警详情",
        index,
        1,
        "%"
      ));
    }
    DailyReportData data = new DailyReportData(
      context(),
      List.of(),
      null,
      alerts,
      null,
      null,
      new CostSummary(0, 0, 0, 0, "CNY", List.of(), List.of()),
      new TrafficSummary(0, 0, 0, 0, List.of())
    );

    List<WechatTemplateMessage> messages = assembler.statusMessages(data);

    assertThat(messages).hasSize(1);
    assertThat(messages.get(0).item3()).startsWith("告警：7 项");
  }

  private List<String> visibleValues(WechatTemplateMessage message) {
    return List.of(message.first(), message.item1(), message.item2(), message.item3());
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
