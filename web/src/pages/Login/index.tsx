import { setAuthSessionCache } from '@/services/authCache';
import { login } from '@/services/monitor';
import { resolveLoginRedirect } from '@/utils/router';
import { ApiOutlined, CloudServerOutlined, LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { history, useLocation } from '@umijs/max';
import { Button, Form, Input, message } from 'antd';
import { useState } from 'react';
import './index.less';

interface LoginFormValues {
  username: string;
  password: string;
}

const guardrails = [
  {
    icon: <SafetyCertificateOutlined />,
    title: '凭据后端托管',
    description: 'OCI config、私钥和 compartment 只在服务器侧读取。',
  },
  {
    icon: <CloudServerOutlined />,
    title: '真实资源同步',
    description: '面板只展示 OCI API 和本机采样落库后的数据。',
  },
  {
    icon: <ApiOutlined />,
    title: '同步任务可追踪',
    description: '手动同步、定时同步和历史结果都能在系统内查看。',
  },
];

export default function LoginPage() {
  const location = useLocation();
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (values: LoginFormValues): Promise<void> => {
    setLoading(true);
    try {
      const session = await login(values);
      setAuthSessionCache(session);
      message.success('登录成功');
      history.replace(resolveLoginRedirect(location.search));
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="login-page">
      <section className="login-shell" aria-labelledby="login-title">
        <div className="login-brand-panel">
          <div className="login-brand-mark">
            <CloudServerOutlined />
          </div>
          <p className="login-kicker">Cloud Ops Console</p>
          <h1 id="login-title">OCI ARM 成本监控</h1>
          <p className="login-summary">登录后查看真实资源、流量、成本和服务器运行状态。</p>
          <div className="login-guardrail-list">
            {guardrails.map((item) => (
              <div className="login-guardrail-item" key={item.title}>
                <span className="login-guardrail-icon">{item.icon}</span>
                <span>
                  <strong>{item.title}</strong>
                  <small>{item.description}</small>
                </span>
              </div>
            ))}
          </div>
        </div>

        <div className="login-panel">
          <div className="login-panel-header">
            <span className="login-lock-icon">
              <LockOutlined />
            </span>
            <div>
              <h2>安全登录</h2>
              <p>使用服务器配置的监控面板账号。</p>
            </div>
          </div>

          <Form layout="vertical" requiredMark={false} onFinish={handleSubmit}>
            <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input autoComplete="username" autoFocus />
            </Form.Item>
            <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password autoComplete="current-password" />
            </Form.Item>
            <Button className="login-submit-button" type="primary" htmlType="submit" loading={loading} block>
              登录
            </Button>
          </Form>
        </div>
      </section>
    </main>
  );
}
