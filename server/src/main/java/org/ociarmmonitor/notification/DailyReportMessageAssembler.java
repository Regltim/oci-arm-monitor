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

  static final int MAX_FIELD_CODE_POINTS = 180;
  static final int MAX_INSTANCE_NAME_CODE_POINTS = 40;
  static final int MAX_INSTANCE_DETAILS = 10;
  static final int MAX_ALERT_DETAILS = 5;
  static final int DETAILS_PER_MESSAGE = 3;

  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  public List<WechatTemplateMessage> statusMessages(DailyReportData data) {
    return List.of(statusMessage(data));
  }

  public WechatTemplateMessage statusMessage(DailyReportData data) {
    return new WechatTemplateMessage(
      "OCI ARM Monitor 每日运行状态",
      limitField(instanceSummary(data.instances())),
      limitField(hostSummary(data.hostStatus())),
      limitField(alertSyncSummary(data))
    );
  }

  public WechatTemplateMessage costTrafficMessage(DailyReportData data) {
    boolean hasOciData = data.latestSuccessfulSync() != null;
    double quota = data.traffic().outboundQuotaGb();
    double egress = data.traffic().egressGbThisMonth();
    double usagePercent = quota > 0 ? egress / quota * 100 : 0;
    String currency = currencyPrefix(data.costs().currency());
    String costLine = "费用：OCI " + (hasOciData ? money(currency, data.costs().ociCostThisMonth()) : "暂无同步数据")
      + "｜手工 " + money(currency, data.costs().manualCostThisMonth())
      + "｜总计 " + (hasOciData ? money(currency, data.costs().totalCostThisMonth()) : "无法计算")
      + "｜预测 " + (hasOciData ? money(currency, data.costs().estimatedMonthEndCost()) : "无法计算");
    String trafficLine = "流量：入站 " + (hasOciData ? number(data.traffic().ingressGbThisMonth()) + " GB" : "暂无同步数据")
      + "｜出站 " + (hasOciData ? number(egress) + " GB" : "暂无同步数据")
      + "｜额度 " + (quota > 0 ? number(quota) + " GB" : "未配置");
    String quotaLine = quotaSummary(data, hasOciData, quota, egress, usagePercent);
    return new WechatTemplateMessage(
      "OCI ARM Monitor 费用与流量",
      limitField(costLine),
      limitField(trafficLine),
      limitField(quotaLine)
    );
  }

  private List<WechatTemplateMessage> instanceMessages(List<DailyReportData.InstanceStatus> instances) {
    List<String> details = new ArrayList<>();
    for (int index = 0; index < Math.min(instances.size(), MAX_INSTANCE_DETAILS); index++) {
      details.add((index + 1) + ". " + instanceLine(instances.get(index)));
    }
    if (instances.size() > MAX_INSTANCE_DETAILS) {
      details.add("另有 " + (instances.size() - MAX_INSTANCE_DETAILS) + " 台实例未展示");
    }
    return detailMessages("实例明细", details, Math.min(instances.size(), MAX_INSTANCE_DETAILS));
  }

  private List<WechatTemplateMessage> alertMessages(List<ServerAlert> alerts) {
    List<String> details = new ArrayList<>();
    for (int index = 0; index < Math.min(alerts.size(), MAX_ALERT_DETAILS); index++) {
      ServerAlert alert = alerts.get(index);
      details.add((index + 1) + ". [" + severityLabel(alert.severity()) + "] "
        + sanitize(alert.title()) + "｜" + truncate(sanitize(alert.description()), 120));
    }
    if (alerts.size() > MAX_ALERT_DETAILS) {
      details.add("另有 " + (alerts.size() - MAX_ALERT_DETAILS) + " 项告警未展示");
    }
    return detailMessages("告警明细", details, Math.min(alerts.size(), MAX_ALERT_DETAILS));
  }

  private List<WechatTemplateMessage> detailMessages(String title, List<String> details, int detailCount) {
    if (detailCount == 0) {
      return List.of();
    }
    int messageCount = (detailCount + DETAILS_PER_MESSAGE - 1) / DETAILS_PER_MESSAGE;
    List<WechatTemplateMessage> messages = new ArrayList<>();
    int detailIndex = 0;
    for (int messageIndex = 0; messageIndex < messageCount; messageIndex++) {
      List<String> fields = new ArrayList<>();
      for (int fieldIndex = 0; fieldIndex < DETAILS_PER_MESSAGE && detailIndex < details.size(); fieldIndex++) {
        fields.add(limitField(details.get(detailIndex++)));
      }
      while (fields.size() < DETAILS_PER_MESSAGE) {
        fields.add("");
      }
      messages.add(new WechatTemplateMessage(
        title + " " + (messageIndex + 1) + "/" + messageCount,
        fields.get(0),
        fields.get(1),
        fields.get(2)
      ));
    }
    return messages;
  }

  private String instanceSummary(List<DailyReportData.InstanceStatus> instances) {
    if (instances.isEmpty()) {
      return "实例：暂无 OCI 实例数据";
    }
    long running = instances.stream().filter(instance -> "RUNNING".equalsIgnoreCase(instance.lifecycleState())).count();
    long stopped = instances.stream().filter(instance -> "STOPPED".equalsIgnoreCase(instance.lifecycleState())).count();
    return "实例：共 " + instances.size() + " 台｜运行 " + running + "｜停止 " + stopped
      + "｜其他 " + (instances.size() - running - stopped);
  }

  private String hostSummary(ServerStatusSnapshot snapshot) {
    if (snapshot == null) {
      return "主机：暂无采样数据";
    }
    return "主机：CPU " + number(snapshot.cpuUsagePercent()) + "%"
      + "｜内存 " + number(snapshot.memoryUsagePercent()) + "%"
      + "｜磁盘 " + number(snapshot.diskUsagePercent()) + "%";
  }

  private String alertSyncSummary(DailyReportData data) {
    String alert = data.alerts().isEmpty() ? "告警：无" : "告警：" + data.alerts().size() + " 项";
    if (data.latestSync() == null) {
      return alert + "｜同步：暂无记录";
    }
    SyncResult latest = data.latestSync();
    String sync = "同步：" + syncStatus(latest.status()) + " " + syncTime(data.context(), latest);
    String message = truncate(sanitize(latest.message()), 60);
    if (!message.isBlank()) {
      sync += "（" + message + "）";
    }
    if (!"SUCCESS".equalsIgnoreCase(latest.status()) && data.latestSuccessfulSync() != null) {
      sync += "｜最近成功 " + syncTime(data.context(), data.latestSuccessfulSync());
    }
    return alert + "｜" + sync;
  }

  private String quotaSummary(
    DailyReportData data,
    boolean hasOciData,
    double quota,
    double egress,
    double usagePercent
  ) {
    String sync = data.latestSuccessfulSync() == null
      ? "同步 暂无成功记录"
      : "同步 " + syncTime(data.context(), data.latestSuccessfulSync());
    if (!hasOciData) {
      return "额度：无法计算｜" + sync;
    }
    if (quota <= 0) {
      return "额度：未配置｜" + sync;
    }
    double quotaDifference = quota - egress;
    String difference = quotaDifference >= 0 ? "剩余 " : "超出 ";
    return "额度：已用 " + number(usagePercent) + "%｜" + difference
      + number(Math.abs(quotaDifference)) + " GB｜" + sync;
  }

  private String severityLabel(String severity) {
    return "danger".equalsIgnoreCase(severity) ? "严重" : "警告";
  }

  private String limitField(String value) {
    return truncate(sanitize(value), MAX_FIELD_CODE_POINTS);
  }

  private String instanceLine(DailyReportData.InstanceStatus instance) {
    String name = truncate(sanitize(instance.displayName()), MAX_INSTANCE_NAME_CODE_POINTS);
    String state = lifecycleLabel(instance.lifecycleState());
    if (!"RUNNING".equalsIgnoreCase(instance.lifecycleState())) {
      return name + "：" + state;
    }
    return name + "：" + state
      + "｜CPU " + percentage(instance.cpuUtilization())
      + "｜内存 " + percentage(instance.memoryUtilization());
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
