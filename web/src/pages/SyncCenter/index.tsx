import MetricCard from '@/components/MetricCard';
import { useAsyncData } from '@/hooks/useAsyncData';
import {
  getSyncSchedule,
  getSyncStatus,
  listSyncHistory,
  syncResources,
  updateSyncSchedule,
  waitForSyncFinished,
} from '@/services/monitor';
import type { SyncRunRecord, SyncScheduleUpdateRequest } from '@/types/api';
import { formatDateTime, formatNumber } from '@/utils/format';
import { Button, Card, Form, Input, Space, Switch, Table, Tag, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { ReactNode } from 'react';
import { useEffect, useMemo, useState } from 'react';

const statusColorMap: Record<string, string> = {
  SUCCESS: 'green',
  FAILED: 'red',
  RUNNING: 'blue',
  NOT_CONFIGURED: 'orange',
  NEVER_SYNCED: 'default',
};

const statusTextMap: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  RUNNING: '运行中',
  NOT_CONFIGURED: '未配置',
  NEVER_SYNCED: '未同步',
};

interface SyncHistorySummary {
  completedCount: number;
  failedCount: number;
  runningCount: number;
  successRate: number;
  averageDurationMs?: number;
  latestFailure?: SyncRunRecord;
}

export default function SyncCenterPage() {
  const [form] = Form.useForm<SyncScheduleUpdateRequest>();
  const [syncing, setSyncing] = useState(false);
  const [savingSchedule, setSavingSchedule] = useState(false);
  const [syncProgressMessage, setSyncProgressMessage] = useState('');
  const syncStatus = useAsyncData(getSyncStatus);
  const scheduleRequest = useAsyncData(async () => {
    const schedule = await getSyncSchedule();
    form.setFieldsValue(schedule);
    return schedule;
  }, []);
  const historyRequest = useAsyncData(() => listSyncHistory(30));
  const historySummary = useMemo(
    () => summarizeSyncHistory(historyRequest.data ?? []),
    [historyRequest.data],
  );

  useEffect(() => {
    const timer = window.setInterval(() => {
      void syncStatus.refresh({ silent: true });
      void historyRequest.refresh({ silent: true });
    }, 5000);
    return () => window.clearInterval(timer);
  }, [historyRequest.refresh, syncStatus.refresh]);

  const handleManualSync = async (): Promise<void> => {
    setSyncing(true);
    setSyncProgressMessage('');
    try {
      const result = await syncResources();
      setSyncProgressMessage(result.message);
      message.info(result.message);
      const finalStatus = await waitForSyncFinished((status) => {
        setSyncProgressMessage(status.lastMessage);
      });
      showSyncResultMessage(finalStatus.lastStatus, finalStatus.lastMessage);
      await Promise.all([syncStatus.refresh(), historyRequest.refresh()]);
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'OCI 同步失败');
    } finally {
      setSyncing(false);
      setSyncProgressMessage('');
    }
  };

  const handleSaveSchedule = async (values: SyncScheduleUpdateRequest): Promise<void> => {
    setSavingSchedule(true);
    try {
      await updateSyncSchedule(values);
      message.success('定时同步配置已保存');
      await scheduleRequest.refresh();
    } finally {
      setSavingSchedule(false);
    }
  };

  const columns: ColumnsType<SyncRunRecord> = [
    {
      title: '类型',
      dataIndex: 'syncType',
      width: 88,
      render: (value: string) => <Tag color={value === 'SCHEDULED' ? 'purple' : 'blue'}>{value === 'SCHEDULED' ? '定时' : '手动'}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 116,
      render: (value: string) => <Tag color={statusColorMap[value] ?? 'default'}>{statusTextMap[value] ?? value}</Tag>,
    },
    {
      title: '开始时间',
      dataIndex: 'startedAt',
      width: 178,
      render: (value?: string) => <span className="sync-history-time">{formatDateTime(value)}</span>,
    },
    {
      title: '结束时间',
      dataIndex: 'finishedAt',
      width: 178,
      render: (value?: string) => <span className="sync-history-time">{formatDateTime(value)}</span>,
    },
    {
      title: '耗时',
      width: 120,
      render: (_, record) => <span className="table-number">{formatSyncDuration(record)}</span>,
    },
    {
      title: '数据量',
      width: 132,
      render: (_, record) => (
        <Space wrap>
          <Tag>实例 {record.instanceCount}</Tag>
          <Tag>指标 {record.metricCount}</Tag>
          <Tag>流量 {record.trafficCount}</Tag>
          <Tag>费用 {record.costCount}</Tag>
        </Space>
      ),
    },
    {
      title: '消息',
      dataIndex: 'message',
      width: 520,
      render: (value?: string) => renderHistoryMessage(value),
    },
  ];

  return (
    <div className="page-shell">
      <div className="page-header">
        <div>
          <h1 className="page-title">同步中心</h1>
          <p className="page-subtitle">管理 OCI 数据同步、自动同步计划和同步历史。</p>
        </div>
        <Button type="primary" loading={syncing} onClick={handleManualSync}>
          {syncing ? '同步中' : '立即同步'}
        </Button>
      </div>

      <div className="grid-4">
        <MetricCard title="最近状态" value={formatSyncStatus(syncStatus.data?.lastStatus)} loading={syncStatus.loading} description={syncProgressMessage || syncStatus.data?.lastMessage} />
        <MetricCard title="最近完成" value={formatDateTime(syncStatus.data?.lastFinishedAt)} loading={syncStatus.loading} />
        <MetricCard title="下次定时同步" value={formatDateTime(scheduleRequest.data?.nextRunAt)} loading={scheduleRequest.loading} />
        <MetricCard title="自动同步" value={scheduleRequest.data?.enabled ? '已启用' : '已停用'} loading={scheduleRequest.loading} description={scheduleRequest.data?.cronExpression} />
      </div>

      <div className="section grid-4">
        <MetricCard
          title="历史成功率"
          value={historySummary.completedCount > 0 ? formatNumber(historySummary.successRate, 1) : '-'}
          suffix={historySummary.completedCount > 0 ? '%' : undefined}
          loading={historyRequest.loading}
          description={historySummary.runningCount > 0
            ? `已结束 ${historySummary.completedCount} 次，运行中 ${historySummary.runningCount} 次`
            : `近 ${historySummary.completedCount} 次已结束同步`}
        />
        <MetricCard
          title="平均耗时"
          value={historySummary.averageDurationMs === undefined ? '-' : formatMilliseconds(historySummary.averageDurationMs)}
          loading={historyRequest.loading}
          description="仅统计已结束任务"
        />
        <MetricCard
          title="失败次数"
          value={historySummary.failedCount}
          loading={historyRequest.loading}
          description={historySummary.runningCount > 0 ? `运行中 ${historySummary.runningCount} 个` : '无运行中任务'}
        />
        <MetricCard
          title="最近失败"
          value={historySummary.latestFailure ? formatDateTime(historySummary.latestFailure.startedAt) : '-'}
          loading={historyRequest.loading}
          description={historySummary.latestFailure?.message ?? '暂无失败记录'}
        />
      </div>

      <div className="section grid-2">
        <Card title="定时同步配置" loading={scheduleRequest.loading}>
          <Form form={form} layout="vertical" onFinish={handleSaveSchedule}>
            <Form.Item label="启用自动同步" name="enabled" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item label="Cron 表达式" name="cronExpression" rules={[{ required: true, message: '请输入 Cron 表达式' }]}>
              <Input placeholder="0 0 0 * * *" />
            </Form.Item>
            <Form.Item label="时区" name="zoneId" rules={[{ required: true, message: '请输入时区' }]}>
              <Input placeholder="Asia/Shanghai" />
            </Form.Item>
            <Form.Item label="服务启动后同步一次" name="syncOnStartup" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={savingSchedule}>
              保存配置
            </Button>
          </Form>
        </Card>

        <Card title="当前同步状态" loading={syncStatus.loading}>
          <Space direction="vertical" size={14} style={{ width: '100%' }}>
            <Tag color={statusColorMap[syncStatus.data?.lastStatus ?? ''] ?? 'default'}>
              {formatSyncStatus(syncStatus.data?.lastStatus)}
            </Tag>
            <div className="muted-text">{syncProgressMessage || syncStatus.data?.lastMessage || '-'}</div>
            <Space wrap>
              <Tag>实例 {syncStatus.data?.instanceCount ?? 0}</Tag>
              <Tag>指标 {syncStatus.data?.metricCount ?? 0}</Tag>
              <Tag>流量 {syncStatus.data?.trafficCount ?? 0}</Tag>
              <Tag>费用 {syncStatus.data?.costCount ?? 0}</Tag>
            </Space>
          </Space>
        </Card>
      </div>

      <div className="section">
        <Card title="同步历史">
          <Table
            className="sync-history-table"
            rowKey="id"
            columns={columns}
            dataSource={historyRequest.data ?? []}
            loading={historyRequest.loading}
            tableLayout="fixed"
            scroll={{ x: 1376 }}
            expandable={{
              columnWidth: 44,
              expandedRowRender: (record) => (
                <div className="sync-history-expanded">
                  <div className="sync-history-expanded-title">完整消息</div>
                  <pre>{record.message || '-'}</pre>
                </div>
              ),
              rowExpandable: (record) => Boolean(record.message),
            }}
            pagination={{
              pageSize: 10,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 30],
              showTotal: (total) => `共 ${total} 条`,
            }}
          />
        </Card>
      </div>
    </div>
  );
}

function summarizeSyncHistory(records: SyncRunRecord[]): SyncHistorySummary {
  const successCount = records.filter((record) => record.status === 'SUCCESS').length;
  const failedRecords = records.filter((record) => record.status === 'FAILED' || record.status === 'NOT_CONFIGURED');
  const runningCount = records.filter((record) => record.status === 'RUNNING').length;
  const completedCount = successCount + failedRecords.length;
  const durations = records
    .map(getFinishedDurationMs)
    .filter((duration): duration is number => typeof duration === 'number' && Number.isFinite(duration) && duration >= 0);

  return {
    completedCount,
    failedCount: failedRecords.length,
    runningCount,
    successRate: completedCount > 0 ? (successCount / completedCount) * 100 : 0,
    averageDurationMs: durations.length > 0
      ? durations.reduce((sum, duration) => sum + duration, 0) / durations.length
      : undefined,
    latestFailure: failedRecords[0],
  };
}

function formatSyncStatus(status?: string): string {
  if (!status) {
    return '-';
  }
  return statusTextMap[status] ?? status;
}

function getFinishedDurationMs(record: SyncRunRecord): number | undefined {
  if (!record.startedAt || !record.finishedAt) {
    return undefined;
  }
  const startedAt = Date.parse(record.startedAt);
  const finishedAt = Date.parse(record.finishedAt);
  if (Number.isNaN(startedAt) || Number.isNaN(finishedAt) || finishedAt < startedAt) {
    return undefined;
  }
  return finishedAt - startedAt;
}

function formatSyncDuration(record: SyncRunRecord): string {
  if (record.status === 'RUNNING' && record.startedAt) {
    const startedAt = Date.parse(record.startedAt);
    if (!Number.isNaN(startedAt)) {
      const elapsedMs = Date.now() - startedAt;
      return elapsedMs >= 0 ? `${formatMilliseconds(elapsedMs)} / 运行中` : '运行中';
    }
  }
  const duration = getFinishedDurationMs(record);
  return typeof duration === 'number' ? formatMilliseconds(duration) : '-';
}

function formatMilliseconds(durationMs: number): string {
  if (!Number.isFinite(durationMs) || durationMs < 0) {
    return '-';
  }
  if (durationMs < 1000) {
    return `${Math.round(durationMs)} ms`;
  }
  const seconds = durationMs / 1000;
  if (seconds < 59.5) {
    return `${formatNumber(seconds, seconds < 10 ? 1 : 0)} 秒`;
  }
  const roundedSeconds = Math.round(seconds);
  const minutes = Math.floor(roundedSeconds / 60);
  const remainingSeconds = roundedSeconds % 60;
  if (minutes < 60) {
    return remainingSeconds > 0 ? `${minutes} 分 ${remainingSeconds} 秒` : `${minutes} 分钟`;
  }
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  return remainingMinutes > 0 ? `${hours} 小时 ${remainingMinutes} 分` : `${hours} 小时`;
}

function renderHistoryMessage(value?: string): ReactNode {
  if (!value?.trim()) {
    return <span className="muted-text">-</span>;
  }
  return <span className="sync-history-message-preview">{value}</span>;
}

function showSyncResultMessage(status: string, fallbackMessage: string): void {
  if (status === 'SUCCESS') {
    message.success(fallbackMessage || 'OCI 数据同步完成');
    return;
  }
  if (status === 'NOT_CONFIGURED') {
    message.warning(fallbackMessage || '后端 OCI 配置不完整');
    return;
  }
  if (status === 'FAILED') {
    message.error(fallbackMessage || 'OCI 同步失败');
    return;
  }
  message.info(fallbackMessage || '同步状态已更新');
}
