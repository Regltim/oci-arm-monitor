import DataSourceAlert from '@/components/DataSourceAlert';
import MetricCard from '@/components/MetricCard';
import { useAsyncData } from '@/hooks/useAsyncData';
import { createManualCost, deleteManualCost, getCostSummary, getSyncStatus } from '@/services/monitor';
import type { CostDaily, ManualCost, ManualCostCreateRequest } from '@/types/api';
import { formatCurrency, formatNumber } from '@/utils/format';
import { PlusOutlined } from '@ant-design/icons';
import { Button, Card, Empty, Form, Input, InputNumber, Modal, Select, Space, Table, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { EChartsOption } from 'echarts';
import ReactECharts from 'echarts-for-react';
import { useMemo, useState } from 'react';

export default function CostsPage() {
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<string>();
  const [form] = Form.useForm<ManualCostCreateRequest>();
  const { data, loading, refresh } = useAsyncData(getCostSummary);
  const syncStatus = useAsyncData(getSyncStatus);
  const hasOciCostRows = (data?.daily.length ?? 0) > 0;

  const handleCreateManualCost = async (values: ManualCostCreateRequest): Promise<void> => {
    setSaving(true);
    try {
      await createManualCost(values);
      message.success('费用记录已保存');
      setOpen(false);
      form.resetFields();
      await refresh();
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteManualCost = async (id: string): Promise<void> => {
    setDeletingId(id);
    try {
      await deleteManualCost(id);
      message.success('费用记录已删除');
      await refresh();
    } finally {
      setDeletingId(undefined);
    }
  };

  const costTrend = useMemo(() => {
    const grouped = new Map<string, number>();
    data?.daily.forEach((item) => {
      grouped.set(item.statDate, (grouped.get(item.statDate) ?? 0) + item.costAmount);
    });
    data?.manualCosts.forEach((item) => {
      grouped.set(item.occurredOn, (grouped.get(item.occurredOn) ?? 0) + item.amount);
    });
    return Array.from(grouped.entries()).sort(([dateA], [dateB]) => dateA.localeCompare(dateB));
  }, [data]);

  const chartOption: EChartsOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 42, right: 18, top: 24, bottom: 36 },
    xAxis: { type: 'category', data: costTrend.map(([date]) => date.slice(5)) },
    yAxis: { type: 'value', name: 'CNY' },
    series: [
      {
        name: '费用',
        type: 'line',
        smooth: true,
        data: costTrend.map(([, value]) => Number(value.toFixed(2))),
        lineStyle: { color: '#17786b' },
        areaStyle: { color: 'rgba(23, 120, 107, 0.12)' },
      },
    ],
  };

  const costColumns: ColumnsType<CostDaily> = [
    { title: '日期', dataIndex: 'statDate', width: 120, render: (value: string) => <span className="table-nowrap">{value}</span> },
    { title: '服务', dataIndex: 'serviceName', width: 180, render: (value: string) => <span className="table-text-preview">{value}</span> },
    { title: '资源', dataIndex: 'resourceId', width: 360, render: (value: string) => <span className="table-id-preview">{value || '-'}</span> },
    {
      title: '用量',
      width: 150,
      render: (_, record) => <span className="table-number">{formatNumber(record.usageAmount)} {record.usageUnit}</span>,
    },
    {
      title: '费用',
      dataIndex: 'costAmount',
      width: 132,
      render: (value: number, record) => <span className="table-number">{formatCurrency(value, record.currency)}</span>,
    },
  ];

  const manualColumns: ColumnsType<ManualCost> = [
    { title: '日期', dataIndex: 'occurredOn', width: 120, render: (value: string) => <span className="table-nowrap">{value}</span> },
    { title: '名称', dataIndex: 'costName', width: 180, render: (value: string) => <span className="table-text-preview">{value}</span> },
    { title: '分类', dataIndex: 'category', width: 140, render: (value: string) => <span className="table-nowrap">{value}</span> },
    {
      title: '金额',
      dataIndex: 'amount',
      width: 132,
      render: (value: number, record) => <span className="table-number">{formatCurrency(value, record.currency)}</span>,
    },
    { title: '备注', dataIndex: 'note', width: 260, render: (value?: string) => <span className="table-text-preview">{value || '-'}</span> },
    {
      title: '操作',
      width: 96,
      render: (_, record) => (
        <Button
          danger
          type="link"
          loading={deletingId === record.id}
          onClick={() => {
            Modal.confirm({
              title: '删除费用记录',
              content: `确认删除「${record.costName}」吗？`,
              okText: '删除',
              okButtonProps: { danger: true },
              cancelText: '取消',
              onOk: () => handleDeleteManualCost(record.id),
            });
          }}
        >
          删除
        </Button>
      ),
    },
  ];

  return (
    <div className="page-shell">
      <div className="page-header">
        <div>
          <h1 className="page-title">成本分析</h1>
          <p className="page-subtitle">合并 OCI 账单数据和手工费用，形成月度成本视图。</p>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          记录费用
        </Button>
      </div>

      <DataSourceAlert className="section" status={syncStatus.data} />

      <div className="grid-4">
        <MetricCard title="OCI 费用" value={hasOciCostRows ? formatCurrency(data?.ociCostThisMonth ?? 0) : '无数据'} loading={loading} />
        <MetricCard title="手工费用" value={formatCurrency(data?.manualCostThisMonth ?? 0)} loading={loading} />
        <MetricCard title="本月合计" value={formatCurrency(data?.totalCostThisMonth ?? 0)} loading={loading} />
        <MetricCard title="月底预测" value={formatCurrency(data?.estimatedMonthEndCost ?? 0)} loading={loading} />
      </div>

      <div className="section">
        <Card title="费用趋势" className="chart-card" loading={loading}>
          {costTrend.length > 0 ? <ReactECharts option={chartOption} /> : <Empty description="暂无费用趋势数据" />}
        </Card>
      </div>

      <div className="section grid-2">
        <Card title="OCI 账单明细">
          <Table
            className="responsive-data-table"
            rowKey={(record) => `${record.serviceName}-${record.resourceId}-${record.statDate}-${record.usageUnit}`}
            columns={costColumns}
            dataSource={data?.daily ?? []}
            loading={loading}
            tableLayout="fixed"
            scroll={{ x: 942 }}
            pagination={{
              pageSize: 8,
              showSizeChanger: true,
              pageSizeOptions: [8, 16, 24],
              showTotal: (total) => `共 ${total} 条`,
            }}
            locale={{
              emptyText: <Empty description="暂无 OCI Usage API 账单明细" />,
            }}
          />
        </Card>
        <Card title="手工费用">
          <Table
            className="responsive-data-table"
            rowKey={(record) => record.id}
            columns={manualColumns}
            dataSource={data?.manualCosts ?? []}
            loading={loading}
            tableLayout="fixed"
            scroll={{ x: 928 }}
            pagination={{
              pageSize: 8,
              showSizeChanger: true,
              pageSizeOptions: [8, 16, 24],
              showTotal: (total) => `共 ${total} 条`,
            }}
            locale={{
              emptyText: <Empty description="暂无手工费用记录" />,
            }}
          />
        </Card>
      </div>

      <Modal
        title="记录费用"
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ currency: 'CNY', category: '第三方服务' }}
          onFinish={handleCreateManualCost}
        >
          <Form.Item label="费用名称" name="costName" rules={[{ required: true, message: '请输入费用名称' }]}>
            <Input placeholder="例如：域名续费" />
          </Form.Item>
          <Form.Item label="分类" name="category" rules={[{ required: true, message: '请选择分类' }]}>
            <Select
              options={[
                { label: '域名', value: '域名' },
                { label: '第三方服务', value: '第三方服务' },
                { label: '人工成本', value: '人工成本' },
                { label: '其他', value: '其他' },
              ]}
            />
          </Form.Item>
          <Space style={{ width: '100%' }} size={12}>
            <Form.Item label="金额" name="amount" rules={[{ required: true, message: '请输入金额' }]} style={{ flex: 1 }}>
              <InputNumber min={0.01} precision={2} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="币种" name="currency" rules={[{ required: true, message: '请选择币种' }]} style={{ width: 140 }}>
              <Select options={[{ label: 'CNY', value: 'CNY' }]} />
            </Form.Item>
          </Space>
          <Form.Item label="发生日期" name="occurredOn" rules={[{ required: true, message: '请输入日期' }]}>
            <Input placeholder="2026-07-06" />
          </Form.Item>
          <Form.Item label="备注" name="note">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
