import MetricCard from '@/components/MetricCard';
import { useAsyncData } from '@/hooks/useAsyncData';
import { getServerStatus, listAlertRules, updateAlertRule } from '@/services/monitor';
import type { AlertRule, AlertRuleUpdateRequest, ServerAlert } from '@/types/api';
import { formatBytes, formatBytesPerSecond, formatDateTime, formatDuration, formatNumber, formatPercent } from '@/utils/format';
import { Alert, Button, Card, Descriptions, Empty, Form, InputNumber, Modal, Progress, Select, Space, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { EChartsOption } from 'echarts';
import ReactECharts from 'echarts-for-react';
import { useEffect, useMemo, useState } from 'react';

const metricNameMap: Record<string, string> = {
  cpu_usage_percent: 'CPU 使用率',
  memory_usage_percent: '内存使用率',
  disk_usage_percent: '磁盘使用率',
  sync_age_hours: '同步延迟',
};

const operatorOptions = [
  { label: '>', value: 'GT' },
  { label: '>=', value: 'GTE' },
  { label: '<', value: 'LT' },
  { label: '<=', value: 'LTE' },
];

const severityOptions = [
  { label: '警告', value: 'warning' },
  { label: '危险', value: 'danger' },
];

const alertTypeMap: Record<ServerAlert['severity'], 'warning' | 'error'> = {
  warning: 'warning',
  danger: 'error',
};

export default function ServerStatusPage() {
  const [editingRule, setEditingRule] = useState<AlertRule>();
  const [savingRule, setSavingRule] = useState(false);
  const [form] = Form.useForm<AlertRuleUpdateRequest>();
  const statusRequest = useAsyncData(getServerStatus);
  const rulesRequest = useAsyncData(listAlertRules);
  const current = statusRequest.data?.current;
  const systemInfo = statusRequest.data?.systemInfo;
  const history = statusRequest.data?.history ?? [];

  useEffect(() => {
    const timer = window.setInterval(() => {
      void statusRequest.refresh({ silent: true });
    }, 3000);
    return () => window.clearInterval(timer);
  }, [statusRequest.refresh]);

  useEffect(() => {
    if (editingRule) {
      form.setFieldsValue({
        operator: editingRule.operator,
        threshold: editingRule.threshold,
        severity: editingRule.severity,
        enabled: editingRule.enabled,
      });
    }
  }, [editingRule, form]);

  const usageChart: EChartsOption = useMemo(() => ({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 42, right: 18, top: 24, bottom: 52 },
    xAxis: {
      type: 'category',
      data: history.map((item) => formatDateTime(item.sampledAt)),
    },
    yAxis: { type: 'value', max: 100, name: '%' },
    series: [
      {
        name: 'CPU',
        type: 'line',
        smooth: true,
        data: history.map((item) => Number(item.cpuUsagePercent.toFixed(2))),
        lineStyle: { color: '#17786b' },
      },
      {
        name: '内存',
        type: 'line',
        smooth: true,
        data: history.map((item) => Number(item.memoryUsagePercent.toFixed(2))),
        lineStyle: { color: '#b7791f' },
      },
      {
        name: '磁盘',
        type: 'line',
        smooth: true,
        data: history.map((item) => Number(item.diskUsagePercent.toFixed(2))),
        lineStyle: { color: '#2f6fbe' },
      },
    ],
  }), [history]);

  const networkChart: EChartsOption = useMemo(() => ({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 48, right: 18, top: 24, bottom: 52 },
    xAxis: {
      type: 'category',
      data: history.map((item) => formatDateTime(item.sampledAt)),
    },
    yAxis: { type: 'value', name: 'B/s' },
    series: [
      {
        name: '入站',
        type: 'line',
        smooth: true,
        data: history.map((item) => Number(item.networkRxBytesPerSecond.toFixed(2))),
        lineStyle: { color: '#2f6fbe' },
      },
      {
        name: '出站',
        type: 'line',
        smooth: true,
        data: history.map((item) => Number(item.networkTxBytesPerSecond.toFixed(2))),
        lineStyle: { color: '#b7791f' },
      },
    ],
  }), [history]);

  const columns: ColumnsType<AlertRule> = [
    {
      title: '指标',
      dataIndex: 'metricName',
      width: 168,
      render: (value: string) => <span className="table-text-preview">{metricNameMap[value] ?? value}</span>,
    },
    {
      title: '条件',
      width: 120,
      render: (_, record) => (
        <span className="table-number">
          {operatorOptions.find((item) => item.value === record.operator)?.label ?? record.operator} {formatNumber(record.threshold)}
        </span>
      ),
    },
    {
      title: '级别',
      dataIndex: 'severity',
      width: 96,
      render: (value: AlertRule['severity']) => <Tag color={value === 'danger' ? 'red' : 'orange'}>{value === 'danger' ? '危险' : '警告'}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 96,
      render: (value: boolean) => <Tag color={value ? 'green' : 'default'}>{value ? '启用' : '停用'}</Tag>,
    },
    {
      title: '操作',
      width: 96,
      render: (_, record) => (
        <Button type="link" onClick={() => setEditingRule(record)}>
          编辑
        </Button>
      ),
    },
  ];

  const handleSaveRule = async (): Promise<void> => {
    if (!editingRule) {
      return;
    }
    const values = await form.validateFields();
    setSavingRule(true);
    try {
      await updateAlertRule(editingRule.id, values);
      setEditingRule(undefined);
      await Promise.all([rulesRequest.refresh(), statusRequest.refresh()]);
    } finally {
      setSavingRule(false);
    }
  };

  return (
    <div className="page-shell">
      <div className="page-header">
        <div>
          <h1 className="page-title">服务器状态</h1>
          <p className="page-subtitle">查看当前部署机器的系统资源、网络速率、运行时间和告警状态。</p>
        </div>
      </div>

      <div className="grid-4">
        <MetricCard
          title="CPU 使用率"
          value={current ? formatPercent(current.cpuUsagePercent) : '-'}
          loading={statusRequest.loading}
          percent={current?.cpuUsagePercent}
          description={current ? `Load ${formatNumber(current.loadOne)} / ${formatNumber(current.loadFive)} / ${formatNumber(current.loadFifteen)}` : undefined}
        />
        <MetricCard
          title="内存使用率"
          value={current ? formatPercent(current.memoryUsagePercent) : '-'}
          loading={statusRequest.loading}
          percent={current?.memoryUsagePercent}
          description={current ? `${formatBytes(current.memoryTotalBytes - current.memoryAvailableBytes)} / ${formatBytes(current.memoryTotalBytes)}` : undefined}
        />
        <MetricCard
          title="磁盘使用率"
          value={current ? formatPercent(current.diskUsagePercent) : '-'}
          loading={statusRequest.loading}
          percent={current?.diskUsagePercent}
          description={current ? `剩余 ${formatBytes(current.diskUsableBytes)}` : undefined}
        />
        <MetricCard
          title="网络速率"
          value={current ? formatBytesPerSecond(current.networkTxBytesPerSecond) : '-'}
          loading={statusRequest.loading}
          description={current ? `入站 ${formatBytesPerSecond(current.networkRxBytesPerSecond)}` : undefined}
        />
      </div>

      <div className="section">
        <Card title="当前告警" loading={statusRequest.loading}>
          {(statusRequest.data?.alerts.length ?? 0) > 0 ? (
            <Space direction="vertical" style={{ width: '100%' }}>
              {statusRequest.data?.alerts.map((item) => (
                <Alert
                  key={`${item.metricName}-${item.threshold}`}
                  type={alertTypeMap[item.severity]}
                  showIcon
                  message={item.title}
                  description={item.description}
                />
              ))}
            </Space>
          ) : (
            <Empty description="暂无告警" />
          )}
        </Card>
      </div>

      <div className="section grid-2">
        <Card title="资源使用趋势" className="chart-card" loading={statusRequest.loading}>
          {history.length > 0 ? <ReactECharts option={usageChart} /> : <Empty description="等待服务器状态采样" />}
        </Card>
        <Card title="网络速率趋势" className="chart-card" loading={statusRequest.loading}>
          {history.length > 0 ? <ReactECharts option={networkChart} /> : <Empty description="等待网络速率采样" />}
        </Card>
      </div>

      <div className="section grid-2">
        <Card title="主机与运行信息" loading={statusRequest.loading}>
          {current && systemInfo ? (
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="主机名">{systemInfo.hostName || '-'}</Descriptions.Item>
              <Descriptions.Item label="操作系统">{systemInfo.osName} {systemInfo.osVersion}</Descriptions.Item>
              <Descriptions.Item label="系统架构">{systemInfo.osArch}</Descriptions.Item>
              <Descriptions.Item label="CPU 型号">{systemInfo.cpuModelName || '-'}</Descriptions.Item>
              <Descriptions.Item label="CPU 核心">{systemInfo.availableProcessors}</Descriptions.Item>
              <Descriptions.Item label="系统运行时间">{formatDuration(current.uptimeSeconds)}</Descriptions.Item>
              <Descriptions.Item label="应用运行时间">{formatDuration(current.processUptimeSeconds)}</Descriptions.Item>
              <Descriptions.Item label="Java 版本">{systemInfo.javaVersion} / {systemInfo.javaVendor}</Descriptions.Item>
              <Descriptions.Item label="JVM">{systemInfo.jvmName}</Descriptions.Item>
              <Descriptions.Item label="Swap 使用率">
                <Progress percent={Number(current.swapUsagePercent.toFixed(1))} size="small" />
              </Descriptions.Item>
              <Descriptions.Item label="内存容量">{formatBytes(current.memoryTotalBytes)}</Descriptions.Item>
              <Descriptions.Item label="Swap 容量">{formatBytes(current.swapTotalBytes)}</Descriptions.Item>
              <Descriptions.Item label="磁盘容量">{formatBytes(current.diskTotalBytes)}</Descriptions.Item>
              <Descriptions.Item label="网络累计">入站 {formatBytes(current.networkRxBytes)} / 出站 {formatBytes(current.networkTxBytes)}</Descriptions.Item>
              <Descriptions.Item label="JVM 内存">{formatBytes(current.jvmMemoryUsedBytes)} / {formatBytes(current.jvmMemoryMaxBytes)}</Descriptions.Item>
              <Descriptions.Item label="JVM 线程数">{current.jvmThreadCount}</Descriptions.Item>
              <Descriptions.Item label="数据库大小">{formatBytes(current.databaseSizeBytes)}</Descriptions.Item>
              <Descriptions.Item label="采样时间">{formatDateTime(current.sampledAt)}</Descriptions.Item>
            </Descriptions>
          ) : (
            <Empty description="暂无系统信息" />
          )}
        </Card>
        <Card title="告警规则">
          <Table
            className="responsive-data-table"
            rowKey="id"
            columns={columns}
            dataSource={rulesRequest.data ?? []}
            loading={rulesRequest.loading}
            tableLayout="fixed"
            scroll={{ x: 576 }}
            pagination={false}
          />
        </Card>
      </div>

      <Modal
        title="编辑告警规则"
        open={Boolean(editingRule)}
        onCancel={() => setEditingRule(undefined)}
        onOk={handleSaveRule}
        confirmLoading={savingRule}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item label="操作符" name="operator" rules={[{ required: true, message: '请选择操作符' }]}>
            <Select options={operatorOptions} />
          </Form.Item>
          <Form.Item label="阈值" name="threshold" rules={[{ required: true, message: '请输入阈值' }]}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="级别" name="severity" rules={[{ required: true, message: '请选择级别' }]}>
            <Select options={severityOptions} />
          </Form.Item>
          <Form.Item label="状态" name="enabled" rules={[{ required: true, message: '请选择状态' }]}>
            <Select
              options={[
                { label: '启用', value: true },
                { label: '停用', value: false },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
