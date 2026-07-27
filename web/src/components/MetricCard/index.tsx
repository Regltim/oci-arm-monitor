import { Card, Progress, Statistic } from 'antd';
import type { ReactNode } from 'react';

interface MetricCardProps {
  title: string;
  value: string | number;
  suffix?: string;
  description?: ReactNode;
  percent?: number;
  loading?: boolean;
}

export default function MetricCard({
  title,
  value,
  suffix,
  description,
  percent,
  loading,
}: MetricCardProps) {
  return (
    <Card className="metric-card" loading={loading}>
      <Statistic title={title} value={value} suffix={suffix} />
      {typeof percent === 'number' ? (
        <Progress percent={Math.min(Number(percent.toFixed(1)), 100)} size="small" status={percent >= 95 ? 'exception' : 'active'} />
      ) : null}
      {description ? <div className="muted-text">{description}</div> : null}
    </Card>
  );
}
