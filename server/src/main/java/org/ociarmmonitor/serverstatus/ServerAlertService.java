package org.ociarmmonitor.serverstatus;

import org.ociarmmonitor.oci.SyncResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ServerAlertService {

  private final AlertRuleRepository alertRuleRepository;

  public ServerAlertService(AlertRuleRepository alertRuleRepository) {
    this.alertRuleRepository = alertRuleRepository;
  }

  public List<AlertRule> listRules() {
    return alertRuleRepository.findAll();
  }

  public AlertRule updateRule(String id, AlertRuleUpdateRequest request) {
    validateRequest(request);
    if (alertRuleRepository.findById(id).isEmpty()) {
      throw new IllegalArgumentException("告警规则不存在：" + id);
    }
    return alertRuleRepository.update(id, request);
  }

  public List<ServerAlert> evaluate(ServerStatusSnapshot snapshot, Optional<SyncResult> latestSyncResult) {
    List<ServerAlert> alerts = new ArrayList<>();
    for (AlertRule rule : alertRuleRepository.findAll()) {
      if (!rule.enabled()) {
        continue;
      }
      MetricValue metricValue = metricValue(rule.metricName(), snapshot, latestSyncResult);
      if (matches(rule.operator(), metricValue.value(), rule.threshold())) {
        alerts.add(new ServerAlert(
          rule.metricName(),
          rule.severity(),
          title(rule.metricName()),
          "%s 当前 %.2f%s，阈值 %.2f%s。".formatted(title(rule.metricName()), metricValue.value(), metricValue.unit(), rule.threshold(), metricValue.unit()),
          metricValue.value(),
          rule.threshold(),
          metricValue.unit()
        ));
      }
    }
    return alerts;
  }

  private void validateRequest(AlertRuleUpdateRequest request) {
    if (!List.of("GT", "GTE", "LT", "LTE").contains(request.operator())) {
      throw new IllegalArgumentException("告警操作符不支持：" + request.operator());
    }
    if (!List.of("warning", "danger").contains(request.severity())) {
      throw new IllegalArgumentException("告警级别只能是 warning 或 danger");
    }
  }

  private MetricValue metricValue(String metricName, ServerStatusSnapshot snapshot, Optional<SyncResult> latestSyncResult) {
    return switch (metricName) {
      case "cpu_usage_percent" -> new MetricValue(snapshot.cpuUsagePercent(), "%");
      case "memory_usage_percent" -> new MetricValue(snapshot.memoryUsagePercent(), "%");
      case "disk_usage_percent" -> new MetricValue(snapshot.diskUsagePercent(), "%");
      case "sync_age_hours" -> new MetricValue(syncAgeHours(latestSyncResult), "h");
      default -> new MetricValue(0, "");
    };
  }

  private double syncAgeHours(Optional<SyncResult> latestSyncResult) {
    if (latestSyncResult.isEmpty()) {
      return 999;
    }
    SyncResult result = latestSyncResult.get();
    String checkpoint = result.finishedAt() == null || result.finishedAt().isBlank() ? result.startedAt() : result.finishedAt();
    if (checkpoint == null || checkpoint.isBlank()) {
      return 999;
    }
    try {
      return Duration.between(Instant.parse(checkpoint), Instant.now()).toMinutes() / 60.0;
    } catch (RuntimeException exception) {
      return 999;
    }
  }

  private boolean matches(String operator, double value, double threshold) {
    return switch (operator) {
      case "GT" -> value > threshold;
      case "GTE" -> value >= threshold;
      case "LT" -> value < threshold;
      case "LTE" -> value <= threshold;
      default -> false;
    };
  }

  private String title(String metricName) {
    return switch (metricName) {
      case "cpu_usage_percent" -> "CPU 使用率";
      case "memory_usage_percent" -> "内存使用率";
      case "disk_usage_percent" -> "磁盘使用率";
      case "sync_age_hours" -> "同步延迟";
      default -> metricName;
    };
  }

  private record MetricValue(double value, String unit) {
  }
}
