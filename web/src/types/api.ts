export interface ApiResponse<TData> {
  success: boolean;
  data: TData;
  message: string;
}

export interface AuthSession {
  username: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export interface QuotaUsage {
  name: string;
  used: number;
  quota: number;
  unit: string;
  percent: number;
}

export interface RiskAlert {
  level: 'success' | 'warning' | 'danger';
  title: string;
  description: string;
}

export interface DashboardSummary {
  ociCostThisMonth: number;
  manualCostThisMonth: number;
  estimatedMonthEndCost: number;
  averageCpuUtilization: number;
  averageMemoryUtilization: number;
  egressGbThisMonth: number;
  instanceCount: number;
  quotaUsages: QuotaUsage[];
  riskAlerts: RiskAlert[];
}

export interface CloudInstance {
  id: string;
  displayName: string;
  region: string;
  compartmentId: string;
  shape: string;
  lifecycleState: string;
  ocpus: number;
  memoryGb: number;
  bootVolumeGb: number;
  publicIp: string;
  privateIp: string;
  createdAt: string;
  updatedAt: string;
}

export interface InstanceOverview {
  instance: CloudInstance;
  cpuUtilization: number;
  memoryUtilization: number;
  egressGbToday: number;
  costAmountThisMonth: number;
}

export interface MetricPoint {
  instanceId: string;
  metricName: string;
  value: number;
  unit: string;
  sampledAt: string;
}

export interface TrafficDaily {
  instanceId: string;
  statDate: string;
  ingressGb: number;
  egressGb: number;
}

export interface TrafficSummary {
  ingressGbThisMonth: number;
  egressGbThisMonth: number;
  outboundQuotaGb: number;
  outboundUsagePercent: number;
  daily: TrafficDaily[];
}

export interface CostDaily {
  serviceName: string;
  resourceId: string;
  statDate: string;
  usageAmount: number;
  usageUnit: string;
  costAmount: number;
  currency: string;
}

export interface ManualCost {
  id: string;
  costName: string;
  category: string;
  amount: number;
  currency: string;
  occurredOn: string;
  note?: string;
  createdAt: string;
}

export interface ManualCostCreateRequest {
  costName: string;
  category: string;
  amount: number;
  currency: string;
  occurredOn: string;
  note?: string;
}

export interface CostSummary {
  ociCostThisMonth: number;
  manualCostThisMonth: number;
  totalCostThisMonth: number;
  estimatedMonthEndCost: number;
  currency: string;
  daily: CostDaily[];
  manualCosts: ManualCost[];
}

export interface FreeQuota {
  ampereOcpuHours: number;
  ampereMemoryGbHours: number;
  blockVolumeGb: number;
  outboundDataTransferGb: number;
  monitoringIngestionPoints: number;
  monitoringRetrievalPoints: number;
  updatedAt: string;
}

export interface OciSettingsStatus {
  configured: boolean;
  authMode: string;
  authModeLabel: string;
  configFileRequired: boolean;
  configFileConfigured: boolean;
  profileConfigured: boolean;
  regionConfigured: boolean;
  compartmentConfigured: boolean;
  tenancyConfigured: boolean;
  source: string;
  updatedAt: string;
}

export type OciDiagnosticStatus = 'SUCCESS' | 'WARNING' | 'FAILED' | 'SKIPPED';

export interface OciDiagnosticStep {
  key: string;
  name: string;
  status: OciDiagnosticStatus;
  message: string;
  suggestion: string;
  durationMs: number;
}

export interface OciDiagnosticsResult {
  configured: boolean;
  authMode: string;
  authModeLabel: string;
  overallStatus: OciDiagnosticStatus;
  summary: string;
  checkedAt: string;
  durationMs: number;
  steps: OciDiagnosticStep[];
  nextActions: string[];
}

export interface WechatNotificationSettingsStatus {
  enabled: boolean;
  configured: boolean;
  source: 'DATABASE' | 'ENVIRONMENT' | 'NONE';
  appIdMasked: string;
  appSecretConfigured: boolean;
  templateIdMasked: string;
  costTemplateIdMasked: string;
  recipientCount: number;
  dailySummaryConfigured: boolean;
  dailySummaryMissingReason: string;
  immediatePushEnabled: boolean;
  dailySummaryEnabled: boolean;
  dailySummaryTime: string;
  zoneId: string;
  encryptionReady: boolean;
  updatedAt: string;
  detailPageEnabled: boolean;
  detailPageReady: boolean;
  detailPageMissingReason: string;
  detailPageTokenTtlDays: number;
}

export interface WechatNotificationSettingsUpdateRequest {
  enabled: boolean;
  appId?: string;
  appSecret?: string;
  templateId?: string;
  costTemplateId?: string;
  openIds?: string;
  immediatePushEnabled: boolean;
  dailySummaryEnabled: boolean;
  dailySummaryTime: string;
  zoneId: string;
  detailPageEnabled: boolean;
  detailPageTokenTtlDays: number;
}

export interface PublicReportInstanceDetail {
  key: string;
  displayName: string;
  lifecycleState: string;
  cpuUtilization: number | null;
  memoryUtilization: number | null;
}

export interface PublicReportHostStatus {
  sampledAt: string;
  cpuUsagePercent: number;
  loadOne: number;
  loadFive: number;
  loadFifteen: number;
  memoryTotalBytes: number;
  memoryAvailableBytes: number;
  memoryUsagePercent: number;
  swapTotalBytes: number;
  swapFreeBytes: number;
  swapUsagePercent: number;
  diskTotalBytes: number;
  diskUsableBytes: number;
  diskUsagePercent: number;
  networkRxBytes: number;
  networkTxBytes: number;
  networkRxBytesPerSecond: number;
  networkTxBytesPerSecond: number;
  uptimeSeconds: number;
  processUptimeSeconds: number;
  jvmMemoryUsedBytes: number;
  jvmMemoryMaxBytes: number;
  jvmThreadCount: number;
  databaseSizeBytes: number;
}

export interface PublicReportAlert {
  key: string;
  metricName: string;
  severity: string;
  title: string;
  description: string;
  currentValue: number;
  threshold: number;
  unit: string;
}

export interface PublicReportSyncStatus {
  status: string;
  startedAt: string;
  finishedAt: string;
  instanceCount: number;
  metricCount: number;
  trafficCount: number;
  costCount: number;
}

export interface PublicReportPayload {
  title: string;
  generatedAt: string;
  reportDate: string;
  zoneId: string;
  instances: {
    totalCount: number;
    runningCount: number;
    stoppedCount: number;
    otherCount: number;
    details: PublicReportInstanceDetail[];
  };
  host: PublicReportHostStatus | null;
  alerts: PublicReportAlert[];
  sync: PublicReportSyncStatus | null;
  costs: {
    ociCostThisMonth: number;
    manualCostThisMonth: number;
    totalCostThisMonth: number;
    estimatedMonthEndCost: number;
    currency: string;
    daily: Array<{
      serviceName: string;
      statDate: string;
      costAmount: number;
      currency: string;
    }>;
    manualCosts: Array<{
      key: string;
      costName: string;
      category: string;
      amount: number;
      currency: string;
      occurredOn: string;
    }>;
  };
  traffic: {
    ingressGbThisMonth: number;
    egressGbThisMonth: number;
    outboundQuotaGb: number;
    outboundUsagePercent: number;
    daily: Array<{
      statDate: string;
      ingressGb: number;
      egressGb: number;
    }>;
  };
}

export interface PublicReportView {
  id: string;
  createdAt: string;
  expiresAt: string;
  report: PublicReportPayload;
}

export interface WechatDeliveryResult {
  notificationType:
    | 'TEST'
    | 'TEST_STATUS'
    | 'TEST_COST_TRAFFIC'
    | 'ALERT'
    | 'RECOVERY'
    | 'DAILY_SUMMARY'
    | 'DAILY_STATUS'
    | 'DAILY_COST_TRAFFIC'
    | 'ALERT_TRANSITION';
  metricName: string;
  successCount: number;
  failureCount: number;
  message: string;
  createdAt: string;
}

export interface WechatTestDeliveryResult {
  status: WechatDeliveryResult;
  costTraffic: WechatDeliveryResult;
  successCount: number;
  failureCount: number;
  message: string;
}

export interface SyncResult {
  status: string;
  message: string;
  startedAt: string;
  finishedAt: string;
  instanceCount: number;
  metricCount: number;
  trafficCount: number;
  costCount: number;
}

export interface SyncStatus {
  configured: boolean;
  hasData: boolean;
  lastStatus: string;
  lastMessage: string;
  lastStartedAt: string;
  lastFinishedAt: string;
  instanceCount: number;
  metricCount: number;
  trafficCount: number;
  costCount: number;
}

export interface SyncRunRecord extends SyncResult {
  id: string;
  syncType: string;
}

export interface SyncSchedule {
  enabled: boolean;
  cronExpression: string;
  zoneId: string;
  syncOnStartup: boolean;
  updatedAt: string;
  nextRunAt: string;
}

export interface SyncScheduleUpdateRequest {
  enabled: boolean;
  cronExpression: string;
  zoneId: string;
  syncOnStartup: boolean;
}

export interface ServerMetricPoint {
  sampledAt: string;
  cpuUsagePercent: number;
  memoryUsagePercent: number;
  diskUsagePercent: number;
  networkRxBytesPerSecond: number;
  networkTxBytesPerSecond: number;
}

export interface ServerStatusSnapshot {
  sampledAt: string;
  cpuUsagePercent: number;
  loadOne: number;
  loadFive: number;
  loadFifteen: number;
  memoryTotalBytes: number;
  memoryAvailableBytes: number;
  memoryUsagePercent: number;
  swapTotalBytes: number;
  swapFreeBytes: number;
  swapUsagePercent: number;
  diskTotalBytes: number;
  diskUsableBytes: number;
  diskUsagePercent: number;
  networkRxBytes: number;
  networkTxBytes: number;
  networkRxBytesPerSecond: number;
  networkTxBytesPerSecond: number;
  uptimeSeconds: number;
  processUptimeSeconds: number;
  jvmMemoryUsedBytes: number;
  jvmMemoryMaxBytes: number;
  jvmThreadCount: number;
  databaseSizeBytes: number;
}

export interface ServerSystemInfo {
  hostName: string;
  osName: string;
  osVersion: string;
  osArch: string;
  availableProcessors: number;
  cpuModelName: string;
  javaVersion: string;
  javaVendor: string;
  jvmName: string;
  runtimeName: string;
}

export interface ServerAlert {
  metricName: string;
  severity: 'warning' | 'danger';
  title: string;
  description: string;
  currentValue: number;
  threshold: number;
  unit: string;
}

export interface ServerStatusSummary {
  current: ServerStatusSnapshot;
  history: ServerMetricPoint[];
  alerts: ServerAlert[];
  systemInfo: ServerSystemInfo;
}

export interface AlertRule {
  id: string;
  metricName: string;
  operator: 'GT' | 'GTE' | 'LT' | 'LTE';
  threshold: number;
  severity: 'warning' | 'danger';
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AlertRuleUpdateRequest {
  operator: AlertRule['operator'];
  threshold: number;
  severity: AlertRule['severity'];
  enabled: boolean;
}
