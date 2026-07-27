import { useAsyncData } from '@/hooks/useAsyncData';
import {
  getWechatNotificationSettings,
  listWechatNotificationDeliveries,
  sendWechatTestNotification,
  updateWechatNotificationSettings,
} from '@/services/monitor';
import type {
  WechatDeliveryResult,
  WechatNotificationSettingsUpdateRequest,
} from '@/types/api';
import { formatDateTime } from '@/utils/format';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useState } from 'react';

const zoneOptions = [
  { label: '中国标准时间（Asia/Shanghai）', value: 'Asia/Shanghai' },
  { label: '协调世界时（UTC）', value: 'UTC' },
  { label: '香港时间（Asia/Hong_Kong）', value: 'Asia/Hong_Kong' },
  { label: '东京时间（Asia/Tokyo）', value: 'Asia/Tokyo' },
  { label: '伦敦时间（Europe/London）', value: 'Europe/London' },
  { label: '洛杉矶时间（America/Los_Angeles）', value: 'America/Los_Angeles' },
];

const deliveryTypeMap: Record<WechatDeliveryResult['notificationType'], string> = {
  TEST: '测试',
  ALERT: '告警',
  RECOVERY: '恢复',
  DAILY_SUMMARY: '每日摘要',
  ALERT_TRANSITION: '告警状态',
};

export default function WechatNotificationSettings() {
  const [form] = Form.useForm<WechatNotificationSettingsUpdateRequest>();
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const dailySummaryEnabled = Form.useWatch('dailySummaryEnabled', form);

  const settingsRequest = useAsyncData(async () => {
    const settings = await getWechatNotificationSettings();
    form.setFieldsValue({
      enabled: settings.enabled,
      appId: '',
      appSecret: '',
      templateId: '',
      openIds: '',
      publicUrl: settings.publicUrl,
      immediatePushEnabled: settings.immediatePushEnabled,
      dailySummaryEnabled: settings.dailySummaryEnabled,
      dailySummaryTime: settings.dailySummaryTime,
      zoneId: settings.zoneId,
    });
    return settings;
  }, []);
  const deliveriesRequest = useAsyncData(listWechatNotificationDeliveries);

  const handleSave = async (values: WechatNotificationSettingsUpdateRequest): Promise<void> => {
    setSaving(true);
    try {
      await updateWechatNotificationSettings(values);
      message.success('微信公众号通知配置已保存');
      await Promise.all([settingsRequest.refresh(), deliveriesRequest.refresh({ silent: true })]);
    } finally {
      setSaving(false);
    }
  };

  const handleSendTest = async (): Promise<void> => {
    setTesting(true);
    try {
      const result = await sendWechatTestNotification();
      if (result.failureCount > 0) {
        message.warning(result.message);
      } else {
        message.success(result.message);
      }
      await deliveriesRequest.refresh({ silent: true });
    } finally {
      setTesting(false);
    }
  };

  const columns: ColumnsType<WechatDeliveryResult> = [
    {
      title: '类型',
      dataIndex: 'notificationType',
      width: 110,
      render: (value: WechatDeliveryResult['notificationType']) => deliveryTypeMap[value] ?? value,
    },
    {
      title: '状态',
      key: 'status',
      width: 100,
      render: (_, record) => (
        <Tag color={record.failureCount === 0 ? 'green' : record.successCount > 0 ? 'orange' : 'red'}>
          {record.failureCount === 0 ? '成功' : record.successCount > 0 ? '部分成功' : '失败'}
        </Tag>
      ),
    },
    {
      title: '结果',
      dataIndex: 'message',
      render: (value: string) => <span className="table-text-preview">{value}</span>,
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 180,
      render: (value: string) => <span className="table-nowrap">{formatDateTime(value)}</span>,
    },
  ];

  const status = settingsRequest.data;
  const sourceLabel = status?.source === 'DATABASE'
    ? '后台设置'
    : status?.source === 'ENVIRONMENT'
      ? '服务器环境变量'
      : '未配置';

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {!status?.encryptionReady ? (
        <Alert
          type="warning"
          showIcon
          message="后台保存暂不可用"
          description="请先在服务器环境中配置 MONITOR_SETTINGS_ENCRYPTION_KEY，再保存公众号凭据。"
        />
      ) : null}
      <Card loading={settingsRequest.loading} title="公众号配置">
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label="配置来源">{sourceLabel}</Descriptions.Item>
            <Descriptions.Item label="AppID">{status?.appIdMasked || '未设置'}</Descriptions.Item>
            <Descriptions.Item label="AppSecret">{status?.appSecretConfigured ? '已设置' : '未设置'}</Descriptions.Item>
            <Descriptions.Item label="Template ID">{status?.templateIdMasked || '未设置'}</Descriptions.Item>
            <Descriptions.Item label="接收人数">{status?.recipientCount ?? 0}</Descriptions.Item>
          </Descriptions>

          <Form form={form} layout="vertical" disabled={saving} onFinish={handleSave}>
            <Form.Item label="启用微信公众号通知" name="enabled" valuePropName="checked">
              <Switch />
            </Form.Item>
            <div className="grid-2">
              <Form.Item label="AppID" name="appId" extra="留空表示保留当前值">
                <Input.Password autoComplete="off" placeholder={status?.appIdMasked || '请输入 AppID'} />
              </Form.Item>
              <Form.Item label="AppSecret" name="appSecret" extra="留空表示保留当前值">
                <Input.Password autoComplete="new-password" placeholder={status?.appSecretConfigured ? '已设置，留空保持不变' : '请输入 AppSecret'} />
              </Form.Item>
              <Form.Item label="Template ID" name="templateId" extra="留空表示保留当前值">
                <Input.Password autoComplete="off" placeholder={status?.templateIdMasked || '请输入 Template ID'} />
              </Form.Item>
              <Form.Item
                label="监控面板地址"
                name="publicUrl"
                rules={[
                  { required: true, message: '请输入监控面板地址' },
                  { type: 'url', message: '请输入有效的 HTTP 或 HTTPS 地址' },
                ]}
              >
                <Input placeholder="https://monitor.example.com" />
              </Form.Item>
            </div>
            <Form.Item label="接收人 OpenID" name="openIds" extra="支持逗号或换行分隔；留空表示保留当前接收人">
              <Input.TextArea rows={3} autoComplete="off" placeholder={status?.recipientCount ? `已设置 ${status.recipientCount} 个接收人` : 'openid_example_1,openid_example_2'} />
            </Form.Item>

            <div className="settings-section-title">推送策略</div>
            <div className="grid-2">
              <Form.Item label="告警状态变化时立即推送" name="immediatePushEnabled" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item label="每日状态摘要" name="dailySummaryEnabled" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item
                label="每日推送时间"
                name="dailySummaryTime"
                rules={[{ required: true, message: '请选择每日推送时间' }]}
              >
                <Input type="time" disabled={!dailySummaryEnabled} />
              </Form.Item>
              <Form.Item label="时区" name="zoneId" rules={[{ required: true, message: '请选择时区' }]}>
                <Select showSearch disabled={!dailySummaryEnabled} options={zoneOptions} />
              </Form.Item>
            </div>

            <Space wrap>
              <Button type="primary" htmlType="submit" loading={saving} disabled={!status?.encryptionReady}>
                保存通知设置
              </Button>
              <Button
                loading={testing}
                disabled={!status?.enabled || !status.configured}
                onClick={handleSendTest}
              >
                发送测试通知
              </Button>
              <a href="https://mp.weixin.qq.com/debug/cgi-bin/sandbox?t=sandbox/login" target="_blank" rel="noreferrer">
                微信测试公众号
              </a>
            </Space>
          </Form>
        </Space>
      </Card>

      <Card title="最近推送记录" loading={deliveriesRequest.loading}>
        <Table
          className="responsive-data-table"
          rowKey={(record) => `${record.createdAt}-${record.notificationType}-${record.metricName}`}
          size="small"
          pagination={false}
          columns={columns}
          dataSource={deliveriesRequest.data ?? []}
          locale={{ emptyText: '暂无推送记录' }}
          scroll={{ x: 720 }}
        />
      </Card>
    </Space>
  );
}
