package org.ociarmmonitor.notification;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import org.ociarmmonitor.oci.SyncResult;
import org.ociarmmonitor.serverstatus.ServerAlert;
import org.ociarmmonitor.serverstatus.ServerStatusSnapshot;

public class DailyReportMessageAssembler {

  static final int MAX_CONTENT_CODE_POINTS = 1800;
  static final int MAX_INSTANCE_NAME_CODE_POINTS = 40;
  static final int MAX_INSTANCE_DETAILS = 10;
  static final int MAX_ALERT_DETAILS = 5;
  static final int MAX_SYNC_MESSAGE_CODE_POINTS = 160;

  private static final String OMITTED_SUFFIX = "部分明细已省略";
  private static final String TRUNCATED_SUFFIX = "部分内容已截断";
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  public WechatTemplateMessage statusMessage(DailyReportData data) {
    int alertCount = data.alerts().size();
    List<String> lines = statusLines(data);
    return new WechatTemplateMessage(
      "OCI ARM Monitor 每日运行状态",
      statusLevel(data.alerts()),
      "实例与监控主机",
      data.instances().size() + " 台实例，" + alertCount + " 项活动告警",
      limitContent(lines),
      formatInstant(data.context(), data.context().reportAt()),
      "数据来自最近一次主机采样和 OCI 同步"
    );
  }

  public WechatTemplateMessage costTrafficMessage(DailyReportData data) {
    boolean hasOciData = data.latestSuccessfulSync() != null;
    double quota = data.traffic().outboundQuotaGb();
    double egress = data.traffic().egressGbThisMonth();
    double usagePercent = quota > 0 ? egress / quota * 100 : 0;
    List<String> lines = new ArrayList<>();
    String currency = currencyPrefix(data.costs().currency());
    lines.add("OCI 费用：" + (hasOciData ? money(currency, data.costs().ociCostThisMonth()) : "暂无同步数据"));
    lines.add("手工费用：" + money(currency, data.costs().manualCostThisMonth()));
    lines.add("本月总费用：" + (hasOciData ? money(currency, data.costs().totalCostThisMonth()) : "无法计算"));
    lines.add("月底费用预测：" + (hasOciData ? money(currency, data.costs().estimatedMonthEndCost()) : "无法计算"));
    lines.add("入站流量：" + (hasOciData ? number(data.traffic().ingressGbThisMonth()) + " GB" : "暂无同步数据"));
    lines.add("出站流量：" + (hasOciData ? number(egress) + " GB" : "暂无同步数据"));
    if (quota <= 0) {
      lines.add("出站免费额度：未配置");
    } else {
      lines.add("出站免费额度：" + number(quota) + " GB");
      if (hasOciData) {
        lines.add("额度使用率：" + number(usagePercent) + "%");
        double quotaDifference = quota - egress;
        lines.add((quotaDifference >= 0 ? "剩余额度：" : "超出额度：") + number(Math.abs(quotaDifference)) + " GB");
      }
    }
    if (data.latestSuccessfulSync() != null) {
      lines.add("数据同步：" + syncTime(data.context(), data.latestSuccessfulSync()));
    } else {
      lines.add("数据同步：暂无成功记录");
    }
    return new WechatTemplateMessage(
      "OCI ARM Monitor 费用与流量日报",
      usagePercent >= 95 ? "严重" : usagePercent >= 80 ? "警告" : "信息",
      "本月累计",
      usagePercent >= 95 ? "出站流量接近免费额度上限" : usagePercent >= 80 ? "出站流量使用偏高" : "费用与流量正常",
      limitContent(lines),
      formatInstant(data.context(), data.context().reportAt()),
      "费用以 OCI Usage API 和手工记录为准"
    );
  }

  private List<String> statusLines(DailyReportData data) {
    List<String> lines = new ArrayList<>();
    if (data.instances().isEmpty()) {
      lines.add("暂无 OCI 实例数据");
    } else {
      long running = data.instances().stream().filter(instance -> "RUNNING".equalsIgnoreCase(instance.lifecycleState())).count();
      long stopped = data.instances().stream().filter(instance -> "STOPPED".equalsIgnoreCase(instance.lifecycleState())).count();
      lines.add("实例汇总：运行 " + running + " 台，停止 " + stopped + " 台，其他 " + (data.instances().size() - running - stopped) + " 台");
      data.instances().stream().limit(MAX_INSTANCE_DETAILS).map(this::instanceLine).forEach(lines::add);
      if (data.instances().size() > MAX_INSTANCE_DETAILS) {
        lines.add("另有 " + (data.instances().size() - MAX_INSTANCE_DETAILS) + " 台实例未展开");
      }
    }
    lines.add(hostLine(data.hostStatus()));
    lines.add(alertLine(data.alerts()));
    lines.addAll(syncLines(data));
    return lines;
  }

  private String instanceLine(DailyReportData.InstanceStatus instance) {
    String name = truncate(sanitize(instance.displayName()), MAX_INSTANCE_NAME_CODE_POINTS);
    String state = lifecycleLabel(instance.lifecycleState());
    if (!"RUNNING".equalsIgnoreCase(instance.lifecycleState())) {
      return "- " + name + "：" + state;
    }
    return "- " + name + "：" + state
      + "，CPU " + percentage(instance.cpuUtilization())
      + "，内存 " + percentage(instance.memoryUtilization());
  }

  private String hostLine(ServerStatusSnapshot snapshot) {
    if (snapshot == null) {
      return "监控主机：暂无主机采样数据";
    }
    return "监控主机：CPU " + number(snapshot.cpuUsagePercent()) + "%"
      + "，内存 " + number(snapshot.memoryUsagePercent()) + "%"
      + "，磁盘 " + number(snapshot.diskUsagePercent()) + "%";
  }

  private String alertLine(List<ServerAlert> alerts) {
    if (alerts.isEmpty()) {
      return "活动告警：无";
    }
    List<String> details = alerts.stream()
      .limit(MAX_ALERT_DETAILS)
      .map(alert -> sanitize(alert.title()) + "（" + truncate(sanitize(alert.description()), 120) + "）")
      .toList();
    String suffix = alerts.size() > MAX_ALERT_DETAILS ? "；另有 " + (alerts.size() - MAX_ALERT_DETAILS) + " 项" : "";
    return "活动告警：" + String.join("；", details) + suffix;
  }

  private List<String> syncLines(DailyReportData data) {
    if (data.latestSync() == null) {
      return List.of("OCI 同步：暂无记录");
    }
    SyncResult latest = data.latestSync();
    String line = "OCI 同步：" + syncStatus(latest.status()) + "，" + syncTime(data.context(), latest);
    String message = truncate(sanitize(latest.message()), MAX_SYNC_MESSAGE_CODE_POINTS);
    if (!message.isBlank()) {
      line += "（" + message + "）";
    }
    if (!"SUCCESS".equalsIgnoreCase(latest.status()) && data.latestSuccessfulSync() != null) {
      return List.of(line, "最近成功：" + syncTime(data.context(), data.latestSuccessfulSync()));
    }
    return List.of(line);
  }

  private String statusLevel(List<ServerAlert> alerts) {
    if (alerts.stream().anyMatch(alert -> "danger".equalsIgnoreCase(alert.severity()))) {
      return "严重";
    }
    return alerts.isEmpty() ? "信息" : "警告";
  }

  private String lifecycleLabel(String lifecycleState) {
    if ("RUNNING".equalsIgnoreCase(lifecycleState)) {
      return "运行中";
    }
    if ("STOPPED".equalsIgnoreCase(lifecycleState)) {
      return "已停止";
    }
    String state = sanitize(lifecycleState);
    return state.isBlank() ? "未知状态" : state;
  }

  private String syncStatus(String status) {
    return switch (status == null ? "" : status.toUpperCase(Locale.ROOT)) {
      case "SUCCESS" -> "成功";
      case "FAILED" -> "失败";
      case "RUNNING" -> "进行中";
      default -> "未知";
    };
  }

  private String syncTime(DailyReportContext context, SyncResult syncResult) {
    String value = syncResult.finishedAt();
    if (value == null || value.isBlank()) {
      value = syncResult.startedAt();
    }
    try {
      return formatInstant(context, Instant.parse(value));
    } catch (RuntimeException exception) {
      return "时间未知";
    }
  }

  private String formatInstant(DailyReportContext context, Instant instant) {
    return instant.atZone(context.zoneId()).format(TIME_FORMATTER);
  }

  private String percentage(OptionalDouble value) {
    return value.isPresent() ? number(value.getAsDouble()) + "%" : "暂无数据";
  }

  private String currencyPrefix(String currency) {
    return "CNY".equalsIgnoreCase(currency) ? "¥" : sanitize(currency) + " ";
  }

  private String money(String prefix, double value) {
    return prefix + number(value);
  }

  private String number(double value) {
    return String.format(Locale.ROOT, "%,.2f", value);
  }

  private String limitContent(List<String> lines) {
    String joined = String.join("\n", lines);
    if (codePointLength(joined) <= MAX_CONTENT_CODE_POINTS) {
      return joined;
    }
    List<String> kept = new ArrayList<>();
    for (String line : lines) {
      String candidate = String.join("\n", kept.isEmpty() ? List.of(line, OMITTED_SUFFIX) : append(kept, line, OMITTED_SUFFIX));
      if (codePointLength(candidate) > MAX_CONTENT_CODE_POINTS) {
        break;
      }
      kept.add(line);
    }
    String limited = String.join("\n", append(kept, OMITTED_SUFFIX));
    if (codePointLength(limited) <= MAX_CONTENT_CODE_POINTS) {
      return limited;
    }
    return truncate(limited, MAX_CONTENT_CODE_POINTS - codePointLength(TRUNCATED_SUFFIX)) + TRUNCATED_SUFFIX;
  }

  private List<String> append(List<String> lines, String... values) {
    List<String> result = new ArrayList<>(lines);
    result.addAll(List.of(values));
    return result;
  }

  private String sanitize(String value) {
    if (value == null) {
      return "";
    }
    return value.replaceAll("[\\p{Cntrl}\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
  }

  private int codePointLength(String value) {
    return value.codePointCount(0, value.length());
  }

  private String truncate(String value, int maxCodePoints) {
    if (codePointLength(value) <= maxCodePoints) {
      return value;
    }
    int endIndex = value.offsetByCodePoints(0, maxCodePoints);
    return value.substring(0, endIndex);
  }
}
