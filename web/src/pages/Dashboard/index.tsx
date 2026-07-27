import DataSourceAlert from '@/components/DataSourceAlert';
import MetricCard from '@/components/MetricCard';
import { useAsyncData } from '@/hooks/useAsyncData';
import { getDashboardSummary, getServerStatus, getSyncStatus, getTrafficSummary, listInstances, syncResources, waitForSyncFinished } from '@/services/monitor';
import { formatBytesPerSecond, formatCurrency, formatDateTime, formatNumber, formatPercent } from '@/utils/format';
import type { RiskAlert } from '@/types/api';
import { Alert, Button, Card, Descriptions, Empty, Progress, Space, Tag, message } from 'antd';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { useMemo, useState } from 'react';

const riskTypeMap: Record<RiskAlert['level'], 'success' | 'warning' | 'error'> = {
  success: 'success',
  warning: 'warning',
  danger: 'error',
};

export default function DashboardPage() {
  const [syncing, setSyncing] = useState(false);
  const [syncProgressMessage, setSyncProgressMessage] = useState('');
  const summaryRequest = useAsyncData(getDashboardSummary);
  const syncStatus = useAsyncData(getSyncStatus);
  const trafficRequest = useAsyncData(getTrafficSummary);
  const serverRequest = useAsyncData(getServerStatus);
  const instancesRequest = useAsyncData(listInstances);
  const data = summaryRequest.data;
  const loading = summaryRequest.loading;
  const hasRealResourceData = syncStatus.data?.hasData === true;
  const instances = instancesRequest.data ?? [];
  const trafficDaily = trafficRequest.data?.daily ?? [];
  const currentServer = serverRequest.data?.current;
  const runningInstanceCount = instances.filter((item) => item.instance.lifecycleState === 'RUNNING').length;
  const totalOcpus = instances.reduce((sum, item) => sum + item.instance.ocpus, 0);
  const totalMemoryGb = instances.reduce((sum, item) => sum + item.instance.memoryGb, 0);
  const totalBootVolumeGb = instances.reduce((sum, item) => sum + item.instance.bootVolumeGb, 0);
  const totalCostThisMonth = (data?.ociCostThisMonth ?? 0) + (data?.manualCostThisMonth ?? 0);

  const handleSync = async (): Promise<void> => {
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
      await Promise.all([
        summaryRequest.refresh(),
        syncStatus.refresh(),
        trafficRequest.refresh(),
        serverRequest.refresh(),
        instancesRequest.refresh(),
      ]);
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'OCI 同步失败');
    } finally {
      setSyncing(false);
      setSyncProgressMessage('');
    }
  };

  const quotaChart: EChartsOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 32, right: 16, top: 24, bottom: 36 },
    xAxis: {
      type: 'category',
      data: data?.quotaUsages.map((item) => item.name) ?? [],
      axisLabel: { interval: 0 },
    },
    yAxis: { type: 'value', max: 100 },
    series: [
      {
        type: 'bar',
        data: data?.quotaUsages.map((item) => Number(item.percent.toFixed(2))) ?? [],
        itemStyle: { color: '#17786b' },
      },
    ],
  };

  const costMixChart: EChartsOption = useMemo(() => ({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [
      {
        name: '成本构成',
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        data: [
          { name: 'OCI', value: Number((data?.ociCostThisMonth ?? 0).toFixed(2)), itemStyle: { color: '#17786b' } },
          { name: '手工', value: Number((data?.manualCostThisMonth ?? 0).toFixed(2)), itemStyle: { color: '#2f6fbe' } },
        ],
      },
    ],
  }), [data]);

  const trafficTrendChart: EChartsOption = useMemo(() => ({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 40, right: 18, top: 24, bottom: 52 },
    xAxis: {
      type: 'category',
      data: trafficDaily.map((item) => item.statDate.slice(5)),
    },
    yAxis: { type: 'value', name: 'GB' },
    series: [
      {
        name: '入站',
        type: 'bar',
        data: trafficDaily.map((item) => Number(item.ingressGb.toFixed(2))),
        itemStyle: { color: '#2f6fbe' },
      },
      {
        name: '出站',
        type: 'line',
        smooth: true,
        data: trafficDaily.map((item) => Number(item.egressGb.toFixed(2))),
        lineStyle: { color: '#b7791f' },
        areaStyle: { color: 'rgba(183, 121, 31, 0.1)' },
      },
    ],
  }), [trafficDaily]);

  return (
    <div className="page-shell">
      <div className="page-header">
        <div>
          <h1 className="page-title">总览看板</h1>
          <p className="page-subtitle">统一查看 Oracle ARM 免费额度、资源利用率、流量和成本风险。</p>
        </div>
        <Button type="primary" loading={syncing} onClick={handleSync}>
          同步 OCI 数据
        </Button>
      </div>

      {syncing && syncProgressMessage ? (
        <Alert className="section" type="info" showIcon message="OCI 正在后台同步" description={syncProgressMessage} />
      ) : (
        <DataSourceAlert className="section" status={syncStatus.data} />
      )}

      <div className="grid-4">
        <MetricCard
          title="OCI 本月费用"
          value={hasRealResourceData ? formatCurrency(data?.ociCostThisMonth ?? 0) : '无数据'}
          loading={loading}
          description="以 Usage API 同步结果为准"
        />
        <MetricCard
          title="本月总成本"
          value={hasRealResourceData ? formatCurrency(totalCostThisMonth) : '无数据'}
          loading={loading}
          description={`手工 ${formatCurrency(data?.manualCostThisMonth ?? 0)}`}
        />
        <MetricCard
          title="月底预测"
          value={hasRealResourceData ? formatCurrency(data?.estimatedMonthEndCost ?? 0) : '无数据'}
          loading={loading}
          description="按当前月已发生费用线性估算"
        />
        <MetricCard
          title="本月出站流量"
          value={hasRealResourceData ? formatNumber(trafficRequest.data?.egressGbThisMonth ?? data?.egressGbThisMonth ?? 0) : '无数据'}
          suffix={hasRealResourceData ? 'GB' : undefined}
          loading={loading || trafficRequest.loading}
          description={`剩余额度 ${formatNumber(Math.max((trafficRequest.data?.outboundQuotaGb ?? 0) - (trafficRequest.data?.egressGbThisMonth ?? 0), 0))} GB`}
        />
      </div>

      <div className="section grid-4">
        <MetricCard
          title="运行实例"
          value={hasRealResourceData ? `${runningInstanceCount}/${instances.length}` : '无数据'}
          loading={instancesRequest.loading}
          description={`${formatNumber(totalOcpus, 1)} OCPU / ${formatNumber(totalMemoryGb, 1)} GB`}
        />
        <MetricCard
          title="Block Volume"
          value={hasRealResourceData ? formatNumber(totalBootVolumeGb, 1) : '无数据'}
          suffix={hasRealResourceData ? 'GB' : undefined}
          loading={instancesRequest.loading}
          description="按当前同步实例规格汇总"
        />
        <MetricCard
          title="服务器 CPU"
          value={currentServer ? formatPercent(currentServer.cpuUsagePercent) : '-'}
          loading={serverRequest.loading}
          percent={currentServer?.cpuUsagePercent}
          description={currentServer ? `Load ${formatNumber(currentServer.loadOne)} / ${formatNumber(currentServer.loadFive)}` : undefined}
        />
        <MetricCard
          title="网络出站速率"
          value={currentServer ? formatBytesPerSecond(currentServer.networkTxBytesPerSecond) : '-'}
          loading={serverRequest.loading}
          description={currentServer ? `入站 ${formatBytesPerSecond(currentServer.networkRxBytesPerSecond)}` : undefined}
        />
      </div>

      <div className="section grid-2">
        <Card title="免费额度使用率" className="chart-card" loading={loading}>
          {hasRealResourceData ? <ReactECharts option={quotaChart} /> : <Empty description="同步后显示免费额度使用率" />}
        </Card>
        <Card title="运行健康" loading={serverRequest.loading}>
          {currentServer ? (
            <Space direction="vertical" size={18} style={{ width: '100%' }}>
              <div>
                <div className="quota-title">
                  <span>内存</span>
                  <strong>{formatPercent(currentServer.memoryUsagePercent)}</strong>
                </div>
                <Progress percent={Number(currentServer.memoryUsagePercent.toFixed(1))} strokeColor="#17786b" />
              </div>
              <div>
                <div className="quota-title">
                  <span>磁盘</span>
                  <strong>{formatPercent(currentServer.diskUsagePercent)}</strong>
                </div>
                <Progress percent={Number(currentServer.diskUsagePercent.toFixed(1))} strokeColor="#b7791f" />
              </div>
              <Descriptions size="small" column={1}>
                <Descriptions.Item label="当前告警">{serverRequest.data?.alerts.length ?? 0}</Descriptions.Item>
                <Descriptions.Item label="采样时间">{formatDateTime(currentServer.sampledAt)}</Descriptions.Item>
              </Descriptions>
            </Space>
          ) : (
            <Empty description="等待服务器状态采样" />
          )}
        </Card>
      </div>

      <div className="section grid-2">
        <Card title="成本构成" className="chart-card" loading={loading}>
          {hasRealResourceData ? <ReactECharts option={costMixChart} /> : <Empty description="同步后显示成本构成" />}
        </Card>
        <Card title="本月流量趋势" className="chart-card" loading={trafficRequest.loading}>
          {trafficDaily.length > 0 ? <ReactECharts option={trafficTrendChart} /> : <Empty description="同步后显示流量趋势" />}
        </Card>
      </div>

      <div className="section grid-2">
        <Card title="资源利用率" loading={loading}>
          {hasRealResourceData ? (
            <Space direction="vertical" size={18} style={{ width: '100%' }}>
            <div>
              <div className="quota-title">
                <span>平均 CPU</span>
                <strong>{formatPercent(data?.averageCpuUtilization ?? 0)}</strong>
              </div>
              <Progress percent={Number((data?.averageCpuUtilization ?? 0).toFixed(1))} strokeColor="#17786b" />
            </div>
            <div>
              <div className="quota-title">
                <span>平均内存</span>
                <strong>{formatPercent(data?.averageMemoryUtilization ?? 0)}</strong>
              </div>
              <Progress percent={Number((data?.averageMemoryUtilization ?? 0).toFixed(1))} strokeColor="#b7791f" />
            </div>
            <Space wrap>
              {data?.quotaUsages.map((item) => (
                <Tag key={item.name} color={item.percent >= 80 ? 'orange' : 'green'}>
                  {item.name} {formatPercent(item.percent)}
                </Tag>
              ))}
            </Space>
            </Space>
          ) : (
            <Empty description="同步后显示 CPU 和内存利用率" />
          )}
        </Card>
        <Card title="最近同步" loading={syncStatus.loading}>
          <Descriptions column={1} size="small">
            <Descriptions.Item label="状态">{syncStatus.data?.lastStatus ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="完成时间">{formatDateTime(syncStatus.data?.lastFinishedAt)}</Descriptions.Item>
            <Descriptions.Item label="数据量">
              实例 {syncStatus.data?.instanceCount ?? 0} / 指标 {syncStatus.data?.metricCount ?? 0} / 流量 {syncStatus.data?.trafficCount ?? 0} / 费用 {syncStatus.data?.costCount ?? 0}
            </Descriptions.Item>
            <Descriptions.Item label="消息">{syncProgressMessage || syncStatus.data?.lastMessage || '-'}</Descriptions.Item>
          </Descriptions>
        </Card>
      </div>

      <div className="section">
        <Card title="风险提醒" loading={loading}>
          {hasRealResourceData ? (
            <Space direction="vertical" style={{ width: '100%' }}>
              {data?.riskAlerts.map((item) => (
                <Alert
                  key={`${item.level}-${item.title}`}
                  type={riskTypeMap[item.level]}
                  message={item.title}
                  description={item.description}
                  showIcon
                />
              ))}
            </Space>
          ) : (
            <Empty description="同步后显示免费额度风险" />
          )}
        </Card>
      </div>
    </div>
  );
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
