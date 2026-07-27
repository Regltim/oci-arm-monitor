import { useAsyncData } from '@/hooks/useAsyncData';
import {
  changePassword,
  diagnoseOciSettings,
  getFreeQuota,
  getOciSettingsStatus,
  getSyncStatus,
  syncResources,
  updateFreeQuota,
  waitForSyncFinished,
} from '@/services/monitor';
import { getAuthSessionCache } from '@/services/authCache';
import type { ChangePasswordRequest, FreeQuota, OciDiagnosticStep, OciDiagnosticStatus, OciDiagnosticsResult } from '@/types/api';
import { formatDateTime } from '@/utils/format';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Space, Steps, Table, Tag, Tabs, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useState } from 'react';
import WechatNotificationSettings from './WechatNotificationSettings';

type StepStatus = 'wait' | 'process' | 'finish' | 'error';

const diagnosticStatusMap: Record<OciDiagnosticStatus, { color: string; text: string }> = {
  SUCCESS: { color: 'green', text: '通过' },
  WARNING: { color: 'orange', text: '关注' },
  FAILED: { color: 'red', text: '失败' },
  SKIPPED: { color: 'default', text: '跳过' },
};

export default function SettingsPage() {
  const [quotaForm] = Form.useForm<FreeQuota>();
  const [passwordForm] = Form.useForm<ChangePasswordRequest>();
  const [savingQuota, setSavingQuota] = useState(false);
  const [savingPassword, setSavingPassword] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [syncProgressMessage, setSyncProgressMessage] = useState('');
  const [diagnostics, setDiagnostics] = useState<OciDiagnosticsResult>();
  const [diagnosing, setDiagnosing] = useState(false);

  const ociStatusRequest = useAsyncData(getOciSettingsStatus);
  const quotaRequest = useAsyncData(async () => {
    const values = await getFreeQuota();
    quotaForm.setFieldsValue(values);
    return values;
  }, []);
  const syncStatus = useAsyncData(getSyncStatus);
  const currentUsername = getAuthSessionCache()?.username ?? '-';

  const diagnosticColumns: ColumnsType<OciDiagnosticStep> = [
    {
      title: '检查项',
      dataIndex: 'name',
      width: 160,
      render: (value: string) => <span className="table-nowrap">{value}</span>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: OciDiagnosticStatus) => {
        const statusMeta = diagnosticStatusMap[value] ?? diagnosticStatusMap.SKIPPED;
        return <Tag color={statusMeta.color}>{statusMeta.text}</Tag>;
      },
    },
    {
      title: '结果',
      dataIndex: 'message',
      width: 360,
      render: (value: string) => <span className="table-text-preview">{value}</span>,
    },
    {
      title: '建议',
      dataIndex: 'suggestion',
      width: 420,
      render: (value: string) => <span className="table-text-preview">{value || '-'}</span>,
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      width: 90,
      align: 'right',
      render: (value: number) => <span className="table-number">{value} ms</span>,
    },
  ];

  const handleSaveQuota = async (values: FreeQuota): Promise<void> => {
    setSavingQuota(true);
    try {
      await updateFreeQuota(values);
      message.success('免费额度配置已保存');
      await quotaRequest.refresh();
    } finally {
      setSavingQuota(false);
    }
  };

  const handleSyncResources = async (): Promise<void> => {
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
      await Promise.all([ociStatusRequest.refresh(), syncStatus.refresh()]);
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'OCI 同步失败');
    } finally {
      setSyncing(false);
      setSyncProgressMessage('');
    }
  };

  const handleDiagnoseOci = async (): Promise<void> => {
    setDiagnosing(true);
    try {
      const result = await diagnoseOciSettings();
      setDiagnostics(result);
      showDiagnosticMessage(result.overallStatus, result.summary);
      await ociStatusRequest.refresh();
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'OCI 连接诊断失败');
    } finally {
      setDiagnosing(false);
    }
  };

  const handleChangePassword = async (values: ChangePasswordRequest): Promise<void> => {
    setSavingPassword(true);
    try {
      await changePassword(values);
      passwordForm.resetFields();
      message.success('密码已更新');
    } finally {
      setSavingPassword(false);
    }
  };

  const renderConfigState = (configured?: boolean): string => (configured ? '已设置' : '未设置');
  const renderTenancyState = (): string => {
    if (ociStatusRequest.data?.tenancyConfigured) {
      return '已设置';
    }
    if (ociStatusRequest.data?.authMode === 'config_file' && ociStatusRequest.data.configFileConfigured) {
      return '由 OCI config 提供';
    }
    return '未设置';
  };
  const diagnosticAlertType = diagnostics?.overallStatus === 'FAILED'
    ? 'error'
    : diagnostics?.overallStatus === 'WARNING'
      ? 'warning'
      : 'success';
  const setupStepItems = [
    {
      title: '配置后端环境',
      description: ociStatusRequest.data?.configured ? '基础配置已就绪' : '填写 .env 并重启后端',
      status: ociStatusRequest.data?.configured ? 'finish' : 'process',
    },
    {
      title: '运行连接诊断',
      description: diagnostics ? diagnostics.summary : '检查认证、权限和 API',
      status: resolveDiagnosticStepStatus(diagnostics?.overallStatus, ociStatusRequest.data?.configured),
    },
    {
      title: '同步真实数据',
      description: syncStatus.data?.hasData ? '已存在真实同步数据' : '诊断通过后执行同步',
      status: syncStatus.data?.hasData ? 'finish' : 'wait',
    },
  ] satisfies Array<{ title: string; description: string; status: StepStatus }>;

  return (
    <div className="page-shell">
      <div className="page-header">
        <div>
          <h1 className="page-title">系统设置</h1>
          <p className="page-subtitle">维护 OCI 连接状态、免费额度、微信公众号通知和管理员账号。</p>
        </div>
      </div>

      <Tabs
        items={[
          {
            key: 'oci',
            label: 'OCI 配置',
            children: (
              <Card loading={ociStatusRequest.loading || syncStatus.loading}>
                <Space direction="vertical" size={16} style={{ width: '100%' }}>
                  <Alert
                    type="info"
                    showIcon
                    message="Oracle 配置只在后端保存"
                    description="推荐在 Oracle 机器上使用 Instance Principal，只需要后端 .env 配置 OCI_AUTH_MODE、OCI_REGION、OCI_TENANCY_OCID 和 OCI_COMPARTMENT_OCID。API Key 模式才需要只读挂载 OCI config 和私钥目录。"
                  />
                  {syncing && syncProgressMessage ? (
                    <Alert type="info" showIcon message="OCI 正在后台同步" description={syncProgressMessage} />
                  ) : null}
                  <div className="settings-section-title">首次部署引导</div>
                  <Steps className="settings-setup-steps" size="small" items={setupStepItems} />
                  <Descriptions bordered size="small" column={1}>
                    <Descriptions.Item label="配置来源">后端环境变量</Descriptions.Item>
                    <Descriptions.Item label="认证模式">{ociStatusRequest.data?.authModeLabel || '-'}</Descriptions.Item>
                    <Descriptions.Item label="整体状态">{syncStatus.data?.configured ? '已配置' : '未配置'}</Descriptions.Item>
                    <Descriptions.Item label="Config 文件">
                      {ociStatusRequest.data?.configFileRequired ? renderConfigState(ociStatusRequest.data?.configFileConfigured) : '无需配置'}
                    </Descriptions.Item>
                    <Descriptions.Item label="Profile">
                      {ociStatusRequest.data?.configFileRequired ? renderConfigState(ociStatusRequest.data?.profileConfigured) : '无需配置'}
                    </Descriptions.Item>
                    <Descriptions.Item label="Region">{renderConfigState(ociStatusRequest.data?.regionConfigured)}</Descriptions.Item>
                    <Descriptions.Item label="Compartment">{renderConfigState(ociStatusRequest.data?.compartmentConfigured)}</Descriptions.Item>
                    <Descriptions.Item label="Tenancy / Usage API">{renderTenancyState()}</Descriptions.Item>
                    <Descriptions.Item label="最近同步">{formatDateTime(syncStatus.data?.lastFinishedAt)}</Descriptions.Item>
                    <Descriptions.Item label="同步结果">{syncProgressMessage || syncStatus.data?.lastMessage || '-'}</Descriptions.Item>
                  </Descriptions>
                  <Button type="primary" loading={syncing} onClick={handleSyncResources}>
                    {syncing ? '同步中' : '同步 OCI 数据'}
                  </Button>
                  <div className="settings-section-title">连接诊断</div>
                  <Space wrap>
                    <Button loading={diagnosing} onClick={handleDiagnoseOci}>
                      {diagnosing ? '诊断中' : '运行 OCI 连接诊断'}
                    </Button>
                    <span className="settings-helper-text">诊断会即时访问 OCI API，只读取少量资源用于确认配置和 IAM 权限。</span>
                  </Space>
                  {diagnostics ? (
                    <>
                      <Alert
                        type={diagnosticAlertType}
                        showIcon
                        message={diagnostics.summary}
                        description={`最近诊断：${formatDateTime(diagnostics.checkedAt)}，耗时 ${diagnostics.durationMs} ms`}
                      />
                      {diagnostics.nextActions.length > 0 ? (
                        <Alert
                          type="warning"
                          showIcon
                          message="下一步建议"
                          description={diagnostics.nextActions.join('；')}
                        />
                      ) : null}
                      <Table
                        className="responsive-data-table oci-diagnostics-table"
                        rowKey="key"
                        size="small"
                        pagination={false}
                        columns={diagnosticColumns}
                        dataSource={diagnostics.steps}
                        scroll={{ x: 1120 }}
                      />
                    </>
                  ) : null}
                </Space>
              </Card>
            ),
          },
          {
            key: 'quota',
            label: '免费额度',
            children: (
              <Card loading={quotaRequest.loading}>
                <Form form={quotaForm} layout="vertical" onFinish={handleSaveQuota}>
                  <div className="grid-2">
                    <Form.Item label="ARM OCPU 小时" name="ampereOcpuHours" rules={[{ required: true, message: '请输入 OCPU 小时' }]}>
                      <InputNumber min={0} style={{ width: '100%' }} />
                    </Form.Item>
                    <Form.Item label="ARM 内存 GB 小时" name="ampereMemoryGbHours" rules={[{ required: true, message: '请输入内存 GB 小时' }]}>
                      <InputNumber min={0} style={{ width: '100%' }} />
                    </Form.Item>
                    <Form.Item label="Block Volume GB" name="blockVolumeGb" rules={[{ required: true, message: '请输入磁盘额度' }]}>
                      <InputNumber min={0} style={{ width: '100%' }} />
                    </Form.Item>
                    <Form.Item label="出站流量 GB" name="outboundDataTransferGb" rules={[{ required: true, message: '请输入出站流量额度' }]}>
                      <InputNumber min={0} style={{ width: '100%' }} />
                    </Form.Item>
                    <Form.Item label="Monitoring Ingestion Points" name="monitoringIngestionPoints" rules={[{ required: true, message: '请输入写入点数额度' }]}>
                      <InputNumber min={0} style={{ width: '100%' }} />
                    </Form.Item>
                    <Form.Item label="Monitoring Retrieval Points" name="monitoringRetrievalPoints" rules={[{ required: true, message: '请输入读取点数额度' }]}>
                      <InputNumber min={0} style={{ width: '100%' }} />
                    </Form.Item>
                  </div>
                  <Button type="primary" htmlType="submit" loading={savingQuota}>
                    保存免费额度
                  </Button>
                </Form>
              </Card>
            ),
          },
          {
            key: 'notification',
            label: '通知设置',
            children: <WechatNotificationSettings />,
          },
          {
            key: 'security',
            label: '账号安全',
            children: (
              <Card>
                <Space direction="vertical" size={16} style={{ width: '100%' }}>
                  <Descriptions bordered size="small" column={1}>
                    <Descriptions.Item label="当前账号">{currentUsername}</Descriptions.Item>
                  </Descriptions>
                  <Form form={passwordForm} layout="vertical" disabled={savingPassword} onFinish={handleChangePassword}>
                    <div className="grid-2">
                      <Form.Item label="当前密码" name="currentPassword" rules={[{ required: true, message: '请输入当前密码' }]}>
                        <Input.Password autoComplete="current-password" />
                      </Form.Item>
                      <Form.Item
                        label="新密码"
                        name="newPassword"
                        rules={[
                          { required: true, message: '请输入新密码' },
                          { min: 8, max: 128, message: '新密码长度需在 8 到 128 位之间' },
                        ]}
                      >
                        <Input.Password autoComplete="new-password" maxLength={128} />
                      </Form.Item>
                      <Form.Item
                        label="确认新密码"
                        name="confirmPassword"
                        dependencies={['newPassword']}
                        rules={[
                          { required: true, message: '请再次输入新密码' },
                          ({ getFieldValue }) => ({
                            validator(_, value?: string) {
                              if (!value || getFieldValue('newPassword') === value) {
                                return Promise.resolve();
                              }
                              return Promise.reject(new Error('两次输入的新密码不一致'));
                            },
                          }),
                        ]}
                      >
                        <Input.Password autoComplete="new-password" maxLength={128} />
                      </Form.Item>
                    </div>
                    <Button type="primary" htmlType="submit" loading={savingPassword}>
                      更新密码
                    </Button>
                  </Form>
                </Space>
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
}

function resolveDiagnosticStepStatus(status: OciDiagnosticStatus | undefined, configured?: boolean): StepStatus {
  if (!configured) {
    return 'wait';
  }
  if (!status) {
    return 'process';
  }
  if (status === 'SUCCESS') {
    return 'finish';
  }
  if (status === 'FAILED') {
    return 'error';
  }
  return 'process';
}

function showDiagnosticMessage(status: OciDiagnosticStatus, fallbackMessage: string): void {
  if (status === 'SUCCESS') {
    message.success(fallbackMessage || 'OCI 连接诊断通过');
    return;
  }
  if (status === 'WARNING') {
    message.warning(fallbackMessage || 'OCI 诊断完成，但存在需要关注的项目');
    return;
  }
  message.error(fallbackMessage || 'OCI 连接诊断失败');
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
