import DataSourceAlert from '@/components/DataSourceAlert';
import MetricCard from '@/components/MetricCard';
import { useAsyncData } from '@/hooks/useAsyncData';
import { getSyncStatus, getTrafficSummary } from '@/services/monitor';
import { formatNumber, formatPercent } from '@/utils/format';
import { Card, Empty, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { EChartsOption } from 'echarts';
import ReactECharts from 'echarts-for-react';
import type { TrafficDaily } from '@/types/api';

export default function TrafficPage() {
  const { data, loading } = useAsyncData(getTrafficSummary);
  const syncStatus = useAsyncData(getSyncStatus);
  const hasTrafficRows = (data?.daily.length ?? 0) > 0;
  const egressGb = data?.egressGbThisMonth ?? 0;
  const outboundQuotaGb = data?.outboundQuotaGb ?? 0;
  const outboundUsagePercent = data?.outboundUsagePercent ?? 0;
  const remainingQuotaGb = Math.max(outboundQuotaGb - egressGb, 0);

  const chartOption: EChartsOption = {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 40, right: 18, top: 24, bottom: 52 },
    xAxis: {
      type: 'category',
      data: data?.daily.map((item) => item.statDate.slice(5)) ?? [],
    },
    yAxis: { type: 'value', name: 'GB' },
    series: [
      {
        name: '入站',
        type: 'bar',
        data: data?.daily.map((item) => item.ingressGb) ?? [],
        itemStyle: { color: '#2f6fbe' },
      },
      {
        name: '出站',
        type: 'bar',
        data: data?.daily.map((item) => item.egressGb) ?? [],
        itemStyle: { color: '#b7791f' },
      },
    ],
  };

  const quotaGaugeOption: EChartsOption = {
    tooltip: {
      formatter: () => `出站使用率：${formatPercent(outboundUsagePercent)}`,
    },
    series: [
      {
        name: '出站额度',
        type: 'gauge',
        min: 0,
        max: 100,
        startAngle: 210,
        endAngle: -30,
        radius: '92%',
        progress: {
          show: true,
          width: 16,
          itemStyle: {
            color: outboundUsagePercent >= 95 ? '#c2413a' : outboundUsagePercent >= 80 ? '#b7791f' : '#17786b',
          },
        },
        axisLine: {
          lineStyle: {
            width: 16,
            color: [
              [0.8, 'rgba(23, 120, 107, 0.2)'],
              [0.95, 'rgba(183, 121, 31, 0.22)'],
              [1, 'rgba(194, 65, 58, 0.22)'],
            ],
          },
        },
        axisTick: { show: false },
        splitLine: { distance: -20, length: 8, lineStyle: { color: '#8a9aa8', width: 1 } },
        axisLabel: { color: '#637381', distance: -4 },
        pointer: { width: 4, itemStyle: { color: '#14212b' } },
        title: { offsetCenter: [0, '54%'], color: '#637381', fontSize: 13 },
        detail: {
          valueAnimation: true,
          formatter: (value: number) => `${formatNumber(value, 1)}%`,
          offsetCenter: [0, '26%'],
          color: '#14212b',
          fontSize: 28,
          fontWeight: 800,
        },
        data: [{ value: Number(outboundUsagePercent.toFixed(1)), name: '出站使用率' }],
      },
    ],
  };

  const columns: ColumnsType<TrafficDaily> = [
    { title: '日期', dataIndex: 'statDate', width: 128, render: (value: string) => <span className="table-nowrap">{value}</span> },
    { title: '实例', dataIndex: 'instanceId', width: 460, render: (value: string) => <span className="table-id-preview">{value}</span> },
    {
      title: '入站流量',
      dataIndex: 'ingressGb',
      width: 140,
      render: (value: number) => <span className="table-number">{formatNumber(value)} GB</span>,
    },
    {
      title: '出站流量',
      dataIndex: 'egressGb',
      width: 140,
      render: (value: number) => <span className="table-number">{formatNumber(value)} GB</span>,
    },
  ];

  return (
    <div className="page-shell">
      <div className="page-header">
        <div>
          <h1 className="page-title">流量分析</h1>
          <p className="page-subtitle">统计实例入站、出站流量，并跟踪已配置的出站免费额度使用率。</p>
        </div>
      </div>

      <DataSourceAlert className="section" status={syncStatus.data} />

      <div className="grid-4">
        <MetricCard
          title="本月入站"
          value={hasTrafficRows ? formatNumber(data?.ingressGbThisMonth ?? 0) : '无数据'}
          suffix={hasTrafficRows ? 'GB' : undefined}
          loading={loading}
        />
        <MetricCard
          title="本月出站"
          value={hasTrafficRows ? formatNumber(data?.egressGbThisMonth ?? 0) : '无数据'}
          suffix={hasTrafficRows ? 'GB' : undefined}
          loading={loading}
        />
        <MetricCard title="出站免费额度" value={formatNumber(data?.outboundQuotaGb ?? 0, 0)} suffix="GB" loading={loading} />
        <MetricCard
          title="额度使用率"
          value={hasTrafficRows ? formatPercent(data?.outboundUsagePercent ?? 0) : '无数据'}
          percent={hasTrafficRows ? data?.outboundUsagePercent ?? 0 : undefined}
          loading={loading}
        />
      </div>

      <div className="section grid-2">
        <Card title="每日流量趋势" className="chart-card" loading={loading}>
          {hasTrafficRows ? <ReactECharts option={chartOption} /> : <Empty description="同步后显示每日流量趋势" />}
        </Card>
        <Card title="出站额度进度" loading={loading}>
          {hasTrafficRows ? (
            <div className="traffic-quota-panel">
              <ReactECharts option={quotaGaugeOption} style={{ height: 250 }} />
              <div className="traffic-quota-grid">
                <div>
                  <span>已用</span>
                  <strong>{formatNumber(egressGb)} GB</strong>
                </div>
                <div>
                  <span>剩余</span>
                  <strong>{formatNumber(remainingQuotaGb)} GB</strong>
                </div>
                <div>
                  <span>额度</span>
                  <strong>{formatNumber(outboundQuotaGb, 0)} GB</strong>
                </div>
                <div>
                  <span>状态</span>
                  <strong>{outboundUsagePercent >= 95 ? '危险' : outboundUsagePercent >= 80 ? '关注' : '正常'}</strong>
                </div>
              </div>
            </div>
          ) : (
            <Empty description="同步后显示出站额度进度" />
          )}
        </Card>
      </div>

      <div className="section">
        <Card title="流量明细">
          <Table
            className="responsive-data-table"
            rowKey={(record) => `${record.instanceId}-${record.statDate}`}
            columns={columns}
            dataSource={data?.daily ?? []}
            loading={loading}
            tableLayout="fixed"
            scroll={{ x: 868 }}
            pagination={{
              pageSize: 10,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 30],
              showTotal: (total) => `共 ${total} 条`,
            }}
            locale={{
              emptyText: <Empty description="暂无流量明细，请先同步 OCI Monitoring 指标" />,
            }}
          />
        </Card>
      </div>
    </div>
  );
}
