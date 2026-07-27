import type { SyncStatus } from '@/types/api';
import { Alert } from 'antd';

interface DataSourceAlertProps {
  status?: SyncStatus;
  className?: string;
}

export default function DataSourceAlert({ status, className }: DataSourceAlertProps) {
  const alertClassName = ['data-source-alert', className].filter(Boolean).join(' ');

  if (!status) {
    return null;
  }

  if (!status.configured) {
    return (
      <Alert
        className={alertClassName}
        type="warning"
        showIcon
        message="OCI 尚未配置"
        description="当前不会展示任何样例资源。请先在后端 .env 中配置 OCI_AUTH_MODE、OCI_REGION、OCI_TENANCY_OCID 和 OCI_COMPARTMENT_OCID；API Key 模式才需要挂载 OCI config。"
      />
    );
  }

  if (status.lastStatus === 'RUNNING') {
    return (
      <Alert
        className={alertClassName}
        type="info"
        showIcon
        message="OCI 正在后台同步"
        description={status.lastMessage || '请稍后刷新同步状态。'}
      />
    );
  }

  if (status.lastStatus === 'FAILED') {
    return (
      <Alert
        className={alertClassName}
        type={status.hasData ? 'warning' : 'error'}
        showIcon
        message={status.hasData ? '最近一次同步失败，当前展示上次已落库数据' : '最近一次同步失败'}
        description={status.lastMessage || '请检查 OCI 配置、网络和 IAM 权限。'}
      />
    );
  }

  if (!status.hasData) {
    return (
      <Alert
        className={alertClassName}
        type="info"
        showIcon
        message="尚未同步到真实资源"
        description={status.lastMessage || '后端 OCI 配置完成后，点击同步 OCI 数据。'}
      />
    );
  }

  return null;
}
