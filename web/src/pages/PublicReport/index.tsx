import { getPublicReport } from '@/services/publicReport';
import type {
  PublicReportAlert,
  PublicReportInstanceDetail,
  PublicReportView,
} from '@/types/api';
import {
  formatBytes,
  formatBytesPerSecond,
  formatCurrency,
  formatDateTime,
  formatDuration,
  formatNumber,
  formatPercent,
} from '@/utils/format';
import {
  AlertOutlined,
  BarChartOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloudServerOutlined,
  DollarOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { Empty, Progress, Result, Spin, Tag } from 'antd';
import { useLocation, useParams } from '@umijs/max';
import { useEffect, useState } from 'react';
import './index.less';

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; data: PublicReportView }
  | { status: 'unavailable' };

function optionalPercent(value: number | null): string {
  return value === null ? '暂无数据' : formatPercent(value);
}

function lifecycleLabel(state: string): string {
  if (state.toUpperCase() === 'RUNNING') {
    return '运行中';
  }
  if (state.toUpperCase() === 'STOPPED') {
    return '已停止';
  }
  return state || '未知';
}

function lifecycleColor(state: string): string {
  if (state.toUpperCase() === 'RUNNING') {
    return 'green';
  }
  if (state.toUpperCase() === 'STOPPED') {
    return 'default';
  }
  return 'orange';
}

function syncLabel(status: string): string {
  const normalized = status.toUpperCase();
  if (normalized === 'SUCCESS') {
    return '成功';
  }
  if (normalized === 'FAILED') {
    return '失败';
  }
  if (normalized === 'RUNNING') {
    return '进行中';
  }
  return status || '未知';
}

function InstanceRow({ instance }: { instance: PublicReportInstanceDetail }) {
  return (
    <div className="report-list-row">
      <div className="report-list-main">
        <strong>{instance.displayName || '未命名实例'}</strong>
        <Tag color={lifecycleColor(instance.lifecycleState)}>{lifecycleLabel(instance.lifecycleState)}</Tag>
      </div>
      <div className="report-list-metrics">
        <span>CPU <b>{optionalPercent(instance.cpuUtilization)}</b></span>
        <span>内存 <b>{optionalPercent(instance.memoryUtilization)}</b></span>
      </div>
    </div>
  );
}

function AlertRow({ alert }: { alert: PublicReportAlert }) {
  const danger = alert.severity.toLowerCase() === 'danger';
  return (
    <div className={`report-alert-row ${danger ? 'is-danger' : ''}`}>
      <div className="report-alert-heading">
        <strong>{alert.title}</strong>
        <Tag color={danger ? 'red' : 'orange'}>{danger ? '严重' : '警告'}</Tag>
      </div>
      <p>{alert.description}</p>
      <span>当前 {formatNumber(alert.currentValue, 2)}{alert.unit} · 阈值 {formatNumber(alert.threshold, 2)}{alert.unit}</span>
    </div>
  );
}

export default function PublicReportPage() {
  const { snapshotId = '' } = useParams<{ snapshotId: string }>();
  const location = useLocation();
  const [loadState, setLoadState] = useState<LoadState>({ status: 'loading' });

  useEffect(() => {
    document.title = '每日明细 - OCI ARM Monitor';
    document.body.classList.add('public-report-body');
    const referrerMeta = document.createElement('meta');
    referrerMeta.name = 'referrer';
    referrerMeta.content = 'no-referrer';
    document.head.appendChild(referrerMeta);
    return () => {
      document.body.classList.remove('public-report-body');
      referrerMeta.remove();
    };
  }, []);

  useEffect(() => {
    const accessToken = new URLSearchParams(location.search).get('token')?.trim() ?? '';
    if (!snapshotId || !accessToken) {
      setLoadState({ status: 'unavailable' });
      return undefined;
    }
    const controller = new AbortController();
    setLoadState({ status: 'loading' });
    void getPublicReport(snapshotId, accessToken, controller.signal)
      .then((data) => setLoadState({ status: 'ready', data }))
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) {
          setLoadState({ status: 'unavailable' });
        }
      });
    return () => controller.abort();
  }, [location.search, snapshotId]);

  if (loadState.status === 'loading') {
    return (
      <main className="public-report-state" aria-live="polite">
        <Spin size="large" />
        <span>正在读取日报</span>
      </main>
    );
  }

  if (loadState.status === 'unavailable') {
    return (
      <main className="public-report-state">
        <Result
          status="404"
          title="报告不可用"
          subTitle="访问链接无效或已过期。"
        />
      </main>
    );
  }

  const { report, expiresAt } = loadState.data;
  const quotaPercent = report.traffic.outboundQuotaGb > 0
    ? Math.min(100, Math.max(0, report.traffic.outboundUsagePercent))
    : 0;

  return (
    <main className="public-report-page">
      <header className="public-report-header">
        <div className="public-report-brand"><span /> OCI ARM Monitor</div>
        <div className="public-report-title-row">
          <div>
            <p>{report.reportDate} · {report.zoneId}</p>
            <h1>每日运行明细</h1>
          </div>
          <Tag icon={<ClockCircleOutlined />}>有效至 {formatDateTime(expiresAt)}</Tag>
        </div>
        <div className="public-report-meta">生成时间 {formatDateTime(report.generatedAt)}</div>
      </header>

      <section className="report-summary-grid" aria-label="日报摘要">
        <div className="report-summary-item">
          <CloudServerOutlined />
          <span>实例</span>
          <strong>{report.instances.totalCount}</strong>
          <small>{report.instances.runningCount} 台运行中</small>
        </div>
        <div className="report-summary-item">
          <AlertOutlined />
          <span>告警</span>
          <strong>{report.alerts.length}</strong>
          <small>{report.alerts.length === 0 ? '当前无告警' : '需要关注'}</small>
        </div>
        <div className="report-summary-item">
          <DollarOutlined />
          <span>本月总费用</span>
          <strong>{formatCurrency(report.costs.totalCostThisMonth, report.costs.currency)}</strong>
          <small>预测 {formatCurrency(report.costs.estimatedMonthEndCost, report.costs.currency)}</small>
        </div>
        <div className="report-summary-item">
          <BarChartOutlined />
          <span>本月出站</span>
          <strong>{formatNumber(report.traffic.egressGbThisMonth, 2)} GB</strong>
          <small>{report.traffic.outboundQuotaGb > 0 ? `额度 ${formatNumber(report.traffic.outboundQuotaGb, 2)} GB` : '未配置额度'}</small>
        </div>
      </section>

      <section className="public-report-section">
        <div className="report-section-heading">
          <div><CloudServerOutlined /><h2>实例运行</h2></div>
          <span>运行 {report.instances.runningCount} · 停止 {report.instances.stoppedCount} · 其他 {report.instances.otherCount}</span>
        </div>
        {report.instances.details.length > 0 ? (
          <div className="report-list">
            {report.instances.details.map((instance) => (
              <InstanceRow key={instance.key} instance={instance} />
            ))}
          </div>
        ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无实例数据" />}
      </section>

      <section className="public-report-section">
        <div className="report-section-heading">
          <div><BarChartOutlined /><h2>主机状态</h2></div>
          <span>{report.host ? `采样于 ${formatDateTime(report.host.sampledAt)}` : '暂无采样'}</span>
        </div>
        {report.host ? (
          <>
            <div className="report-meter-grid">
              <div><span>CPU</span><strong>{formatPercent(report.host.cpuUsagePercent)}</strong><Progress percent={report.host.cpuUsagePercent} showInfo={false} /></div>
              <div><span>内存</span><strong>{formatPercent(report.host.memoryUsagePercent)}</strong><Progress percent={report.host.memoryUsagePercent} showInfo={false} /></div>
              <div><span>磁盘</span><strong>{formatPercent(report.host.diskUsagePercent)}</strong><Progress percent={report.host.diskUsagePercent} showInfo={false} /></div>
              <div><span>Swap</span><strong>{formatPercent(report.host.swapUsagePercent)}</strong><Progress percent={report.host.swapUsagePercent} showInfo={false} /></div>
            </div>
            <div className="report-detail-grid">
              <div><span>系统负载</span><strong>{formatNumber(report.host.loadOne, 2)} / {formatNumber(report.host.loadFive, 2)} / {formatNumber(report.host.loadFifteen, 2)}</strong></div>
              <div><span>网络速率</span><strong>↓ {formatBytesPerSecond(report.host.networkRxBytesPerSecond)} · ↑ {formatBytesPerSecond(report.host.networkTxBytesPerSecond)}</strong></div>
              <div><span>系统运行</span><strong>{formatDuration(report.host.uptimeSeconds)}</strong></div>
              <div><span>服务运行</span><strong>{formatDuration(report.host.processUptimeSeconds)}</strong></div>
              <div><span>JVM 内存</span><strong>{formatBytes(report.host.jvmMemoryUsedBytes)} / {formatBytes(report.host.jvmMemoryMaxBytes)}</strong></div>
              <div><span>数据库</span><strong>{formatBytes(report.host.databaseSizeBytes)}</strong></div>
            </div>
          </>
        ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无主机采样数据" />}
      </section>

      <section className="public-report-section">
        <div className="report-section-heading">
          <div><DollarOutlined /><h2>费用明细</h2></div>
          <span>币种 {report.costs.currency}</span>
        </div>
        <div className="report-detail-grid report-cost-grid">
          <div><span>OCI 费用</span><strong>{formatCurrency(report.costs.ociCostThisMonth, report.costs.currency)}</strong></div>
          <div><span>手工费用</span><strong>{formatCurrency(report.costs.manualCostThisMonth, report.costs.currency)}</strong></div>
          <div><span>本月合计</span><strong>{formatCurrency(report.costs.totalCostThisMonth, report.costs.currency)}</strong></div>
          <div><span>月末预测</span><strong>{formatCurrency(report.costs.estimatedMonthEndCost, report.costs.currency)}</strong></div>
        </div>
        {report.costs.daily.length > 0 ? (
          <div className="report-table-wrap">
            <table>
              <thead><tr><th>日期</th><th>服务</th><th>费用</th></tr></thead>
              <tbody>
                {report.costs.daily.map((cost) => (
                  <tr key={`${cost.statDate}-${cost.serviceName}-${cost.currency}`}>
                    <td>{cost.statDate}</td><td>{cost.serviceName}</td><td>{formatCurrency(cost.costAmount, cost.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        {report.costs.manualCosts.length > 0 ? (
          <div className="report-subsection">
            <h3>手工费用</h3>
            <div className="report-list">
              {report.costs.manualCosts.map((cost) => (
                <div className="report-list-row" key={cost.key}>
                  <div className="report-list-main"><strong>{cost.costName}</strong><Tag>{cost.category}</Tag></div>
                  <div className="report-list-metrics"><span>{cost.occurredOn}</span><b>{formatCurrency(cost.amount, cost.currency)}</b></div>
                </div>
              ))}
            </div>
          </div>
        ) : null}
      </section>

      <section className="public-report-section">
        <div className="report-section-heading">
          <div><BarChartOutlined /><h2>流量明细</h2></div>
          <span>本月累计</span>
        </div>
        <div className="report-traffic-summary">
          <div><span>入站</span><strong>{formatNumber(report.traffic.ingressGbThisMonth, 2)} GB</strong></div>
          <div><span>出站</span><strong>{formatNumber(report.traffic.egressGbThisMonth, 2)} GB</strong></div>
        </div>
        <div className="report-quota-row">
          <div><span>出站额度</span><strong>{report.traffic.outboundQuotaGb > 0 ? `${formatPercent(report.traffic.outboundUsagePercent)} 已用` : '未配置'}</strong></div>
          <Progress percent={quotaPercent} showInfo={false} status={quotaPercent >= 90 ? 'exception' : 'normal'} />
        </div>
        {report.traffic.daily.length > 0 ? (
          <div className="report-table-wrap">
            <table>
              <thead><tr><th>日期</th><th>入站</th><th>出站</th></tr></thead>
              <tbody>
                {report.traffic.daily.map((traffic) => (
                  <tr key={traffic.statDate}>
                    <td>{traffic.statDate}</td><td>{formatNumber(traffic.ingressGb, 2)} GB</td><td>{formatNumber(traffic.egressGb, 2)} GB</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </section>

      <section className="public-report-section">
        <div className="report-section-heading">
          <div><AlertOutlined /><h2>告警与同步</h2></div>
          <span>{report.alerts.length} 项告警</span>
        </div>
        {report.alerts.length > 0 ? (
          <div className="report-alert-list">{report.alerts.map((alert) => <AlertRow key={alert.key} alert={alert} />)}</div>
        ) : (
          <div className="report-ok-state"><CheckCircleOutlined /><span>当前无告警</span></div>
        )}
        {report.sync ? (
          <div className="report-sync-row">
            <SyncOutlined />
            <div><strong>最近同步{syncLabel(report.sync.status)}</strong><span>{formatDateTime(report.sync.finishedAt || report.sync.startedAt)}</span></div>
            <small>实例 {report.sync.instanceCount} · 指标 {report.sync.metricCount} · 流量 {report.sync.trafficCount} · 费用 {report.sync.costCount}</small>
          </div>
        ) : null}
      </section>

      <footer className="public-report-footer">OCI ARM Monitor · 快照报告</footer>
    </main>
  );
}
