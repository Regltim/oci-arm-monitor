import DataSourceAlert from '@/components/DataSourceAlert';
import { useAsyncData } from '@/hooks/useAsyncData';
import { getSyncStatus, listInstanceMetrics, listInstances } from '@/services/monitor';
import type { InstanceOverview, MetricPoint } from '@/types/api';
import { formatCurrency, formatDateTime, formatNumber, formatPercent } from '@/utils/format';
import { Card, Descriptions, Empty, Progress, Space, Tag } from 'antd';
import type { EChartsOption } from 'echarts';
import ReactECharts from 'echarts-for-react';
import { useEffect, useMemo, useState } from 'react';

function buildLineOption(title: string, points: MetricPoint[], color: string): EChartsOption {
  return {
    title: { text: title, textStyle: { fontSize: 14, fontWeight: 500 } },
    tooltip: { trigger: 'axis' },
    grid: { left: 36, right: 16, top: 44, bottom: 32 },
    xAxis: {
      type: 'category',
      data: points.map((point) => formatDateTime(point.sampledAt)),
    },
    yAxis: { type: 'value' },
    series: [
      {
        name: title,
        type: 'line',
        smooth: true,
        data: points.map((point) => Number(point.value.toFixed(2))),
        lineStyle: { color },
        areaStyle: { color: color === '#17786b' ? 'rgba(23, 120, 107, 0.12)' : 'rgba(183, 121, 31, 0.12)' },
      },
    ],
  };
}

export default function InstancesPage() {
  const [selectedInstanceId, setSelectedInstanceId] = useState<string>();
  const { data, loading } = useAsyncData(listInstances);
  const syncStatus = useAsyncData(getSyncStatus);
  const instances = data ?? [];
  const selectedInstance = useMemo(
    () => instances.find((item) => item.instance.id === selectedInstanceId) ?? instances[0],
    [instances, selectedInstanceId],
  );

  useEffect(() => {
    if (!instances.length) {
      setSelectedInstanceId(undefined);
      return;
    }
    if (!selectedInstanceId || !instances.some((item) => item.instance.id === selectedInstanceId)) {
      setSelectedInstanceId(instances[0].instance.id);
    }
  }, [instances, selectedInstanceId]);

  const cpuRequest = useAsyncData(
    () => listInstanceMetrics(selectedInstance?.instance.id ?? '', 'cpu_utilization'),
    [selectedInstance?.instance.id],
    { enabled: Boolean(selectedInstance) },
  );
  const memoryRequest = useAsyncData(
    () => listInstanceMetrics(selectedInstance?.instance.id ?? '', 'memory_utilization'),
    [selectedInstance?.instance.id],
    { enabled: Boolean(selectedInstance) },
  );

  return (
    <div className="page-shell">
      <div className="page-header">
        <div>
          <h1 className="page-title">实例监控</h1>
          <p className="page-subtitle">按实例查看 CPU、内存、流量、规格和本月成本。</p>
        </div>
      </div>

      <DataSourceAlert className="section" status={syncStatus.data} />

      {loading ? (
        <Card loading />
      ) : instances.length > 0 ? (
        <>
          <div className="instance-card-grid">
            {instances.map((item) => (
              <Card
                key={item.instance.id}
                hoverable
                className={item.instance.id === selectedInstance?.instance.id ? 'instance-summary-card is-active' : 'instance-summary-card'}
                onClick={() => setSelectedInstanceId(item.instance.id)}
              >
                <div className="instance-card-header">
                  <strong>{item.instance.displayName}</strong>
                  <Tag color={item.instance.lifecycleState === 'RUNNING' ? 'green' : 'orange'}>{item.instance.lifecycleState}</Tag>
                </div>
                <div className="table-id-preview">{item.instance.id}</div>
                <Space wrap className="instance-card-tags">
                  <Tag>{item.instance.shape}</Tag>
                  <Tag>{item.instance.region}</Tag>
                  <Tag>{formatNumber(item.instance.ocpus, 1)} OCPU</Tag>
                  <Tag>{formatNumber(item.instance.memoryGb, 1)} GB</Tag>
                </Space>
                <div className="instance-card-progress">
                  <div className="quota-title">
                    <span>CPU</span>
                    <strong>{formatPercent(item.cpuUtilization)}</strong>
                  </div>
                  <Progress percent={Number(item.cpuUtilization.toFixed(1))} size="small" />
                  <div className="quota-title">
                    <span>内存</span>
                    <strong>{formatPercent(item.memoryUtilization)}</strong>
                  </div>
                  <Progress percent={Number(item.memoryUtilization.toFixed(1))} size="small" strokeColor="#b7791f" />
                </div>
              </Card>
            ))}
          </div>

          {selectedInstance ? (
            <>
              <div className="section grid-4">
                <Card className="metric-card">
                  <Descriptions column={1} size="small">
                    <Descriptions.Item label="公网 IP">{selectedInstance.instance.publicIp || '-'}</Descriptions.Item>
                    <Descriptions.Item label="私网 IP">{selectedInstance.instance.privateIp || '-'}</Descriptions.Item>
                    <Descriptions.Item label="启动盘">{formatNumber(selectedInstance.instance.bootVolumeGb, 1)} GB</Descriptions.Item>
                  </Descriptions>
                </Card>
                <Card className="metric-card">
                  <Descriptions column={1} size="small">
                    <Descriptions.Item label="今日出站">{formatNumber(selectedInstance.egressGbToday)} GB</Descriptions.Item>
                    <Descriptions.Item label="本月成本">{formatCurrency(selectedInstance.costAmountThisMonth)}</Descriptions.Item>
                    <Descriptions.Item label="创建时间">{formatDateTime(selectedInstance.instance.createdAt)}</Descriptions.Item>
                  </Descriptions>
                </Card>
                <Card className="metric-card">
                  <div className="quota-title">
                    <span>CPU</span>
                    <strong>{formatPercent(selectedInstance.cpuUtilization)}</strong>
                  </div>
                  <Progress percent={Number(selectedInstance.cpuUtilization.toFixed(1))} />
                </Card>
                <Card className="metric-card">
                  <div className="quota-title">
                    <span>内存</span>
                    <strong>{formatPercent(selectedInstance.memoryUtilization)}</strong>
                  </div>
                  <Progress percent={Number(selectedInstance.memoryUtilization.toFixed(1))} strokeColor="#b7791f" />
                </Card>
              </div>

              <div className="section grid-2">
                <Card title="CPU 使用率趋势" className="chart-card" loading={cpuRequest.loading}>
                  {(cpuRequest.data?.length ?? 0) > 0 ? (
                    <ReactECharts option={buildLineOption('CPU 使用率', cpuRequest.data ?? [], '#17786b')} />
                  ) : (
                    <Empty description="暂无 CPU 指标" />
                  )}
                </Card>
                <Card title="内存利用率趋势" className="chart-card" loading={memoryRequest.loading}>
                  {(memoryRequest.data?.length ?? 0) > 0 ? (
                    <ReactECharts option={buildLineOption('内存利用率', memoryRequest.data ?? [], '#b7791f')} />
                  ) : (
                    <Empty description="暂无内存指标" />
                  )}
                </Card>
              </div>
            </>
          ) : null}
        </>
      ) : (
        <Card>
          <Empty description="暂无实例数据，请先同步 OCI 真实资源" />
        </Card>
      )}
    </div>
  );
}
