import type {
  AuthSession,
  ChangePasswordRequest,
  CostSummary,
  DashboardSummary,
  FreeQuota,
  InstanceOverview,
  LoginRequest,
  ManualCost,
  ManualCostCreateRequest,
  MetricPoint,
  OciDiagnosticsResult,
  OciSettingsStatus,
  AlertRule,
  AlertRuleUpdateRequest,
  ServerStatusSummary,
  SyncResult,
  SyncRunRecord,
  SyncSchedule,
  SyncScheduleUpdateRequest,
  SyncStatus,
  TrafficSummary,
  WechatDeliveryResult,
  WechatNotificationSettingsStatus,
  WechatNotificationSettingsUpdateRequest,
} from '@/types/api';
import { deleteApiData, getApiData, postApiData, putApiData } from './request';

const SYNC_POLL_INTERVAL_MS = 2000;
const SYNC_MAX_POLL_ATTEMPTS = 180;

export function getDashboardSummary(): Promise<DashboardSummary> {
  return getApiData<DashboardSummary>('/api/dashboard/summary');
}

export function login(data: LoginRequest): Promise<AuthSession> {
  return postApiData<AuthSession, LoginRequest>('/api/auth/login', data);
}

export function getCurrentUser(): Promise<AuthSession> {
  return getApiData<AuthSession>('/api/auth/me');
}

export function logout(): Promise<void> {
  return postApiData<void, Record<string, never>>('/api/auth/logout', {});
}

export function changePassword(data: ChangePasswordRequest): Promise<AuthSession> {
  return postApiData<AuthSession, ChangePasswordRequest>('/api/auth/password', data);
}

export function listInstances(): Promise<InstanceOverview[]> {
  return getApiData<InstanceOverview[]>('/api/instances');
}

export function listInstanceMetrics(instanceId: string, metricName: string): Promise<MetricPoint[]> {
  return getApiData<MetricPoint[]>(`/api/instances/${instanceId}/metrics?metricName=${metricName}`);
}

export function getTrafficSummary(): Promise<TrafficSummary> {
  return getApiData<TrafficSummary>('/api/traffic/summary');
}

export function getCostSummary(): Promise<CostSummary> {
  return getApiData<CostSummary>('/api/costs/summary');
}

export function createManualCost(data: ManualCostCreateRequest): Promise<ManualCost> {
  return postApiData<ManualCost, ManualCostCreateRequest>('/api/costs/manual', data);
}

export function deleteManualCost(id: string): Promise<void> {
  return deleteApiData<void>(`/api/costs/manual/${id}`);
}

export function getFreeQuota(): Promise<FreeQuota> {
  return getApiData<FreeQuota>('/api/settings/quota');
}

export function updateFreeQuota(data: FreeQuota): Promise<FreeQuota> {
  return putApiData<FreeQuota, FreeQuota>('/api/settings/quota', data);
}

export function getOciSettingsStatus(): Promise<OciSettingsStatus> {
  return getApiData<OciSettingsStatus>('/api/settings/oci');
}

export function diagnoseOciSettings(): Promise<OciDiagnosticsResult> {
  return getApiData<OciDiagnosticsResult>('/api/settings/oci/diagnostics');
}

export function getWechatNotificationSettings(): Promise<WechatNotificationSettingsStatus> {
  return getApiData<WechatNotificationSettingsStatus>('/api/settings/wechat');
}

export function updateWechatNotificationSettings(
  data: WechatNotificationSettingsUpdateRequest,
): Promise<WechatNotificationSettingsStatus> {
  return putApiData<WechatNotificationSettingsStatus, WechatNotificationSettingsUpdateRequest>(
    '/api/settings/wechat',
    data,
  );
}

export function sendWechatTestNotification(): Promise<WechatDeliveryResult> {
  return postApiData<WechatDeliveryResult, Record<string, never>>('/api/settings/wechat/test', {});
}

export function listWechatNotificationDeliveries(): Promise<WechatDeliveryResult[]> {
  return getApiData<WechatDeliveryResult[]>('/api/settings/wechat/deliveries');
}

export function syncResources(): Promise<SyncResult> {
  return postApiData<SyncResult, Record<string, never>>('/api/sync/full', {});
}

export function getSyncStatus(): Promise<SyncStatus> {
  return getApiData<SyncStatus>('/api/sync/status');
}

export function listSyncHistory(limit = 20): Promise<SyncRunRecord[]> {
  return getApiData<SyncRunRecord[]>(`/api/sync/history?limit=${limit}`);
}

export function getSyncSchedule(): Promise<SyncSchedule> {
  return getApiData<SyncSchedule>('/api/sync/schedule');
}

export function updateSyncSchedule(data: SyncScheduleUpdateRequest): Promise<SyncSchedule> {
  return putApiData<SyncSchedule, SyncScheduleUpdateRequest>('/api/sync/schedule', data);
}

export function getServerStatus(): Promise<ServerStatusSummary> {
  return getApiData<ServerStatusSummary>('/api/server/status');
}

export function listAlertRules(): Promise<AlertRule[]> {
  return getApiData<AlertRule[]>('/api/server/alert-rules');
}

export function updateAlertRule(id: string, data: AlertRuleUpdateRequest): Promise<AlertRule> {
  return putApiData<AlertRule, AlertRuleUpdateRequest>(`/api/server/alert-rules/${id}`, data);
}

export async function waitForSyncFinished(
  onProgress?: (status: SyncStatus) => void,
): Promise<SyncStatus> {
  for (let attempt = 0; attempt < SYNC_MAX_POLL_ATTEMPTS; attempt += 1) {
    const status = await getSyncStatus();
    onProgress?.(status);
    if (status.lastStatus !== 'RUNNING') {
      return status;
    }
    await delay(SYNC_POLL_INTERVAL_MS);
  }
  throw new Error('同步仍在后台进行中，请稍后刷新页面查看结果。');
}

function delay(timeout: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, timeout);
  });
}
