import type { RunTimeLayoutConfig } from '@umijs/max';
import { history, Link } from '@umijs/max';
import {
  BarChartOutlined,
  CloudServerOutlined,
  DashboardOutlined,
  DesktopOutlined,
  DollarOutlined,
  LogoutOutlined,
  SettingOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { App as AntdApp, Button, ConfigProvider, message } from 'antd';
import React from 'react';
import { clearAuthSessionCache } from '@/services/authCache';
import { logout } from '@/services/monitor';
import './global.less';

const menuIconMap: Record<string, React.ReactNode> = {
  '/dashboard': <DashboardOutlined />,
  '/instances': <CloudServerOutlined />,
  '/traffic': <BarChartOutlined />,
  '/costs': <DollarOutlined />,
  '/server': <DesktopOutlined />,
  '/sync': <SyncOutlined />,
  '/settings': <SettingOutlined />,
};

const menuLabelMap: Record<string, string> = {
  '/dashboard': '总览',
  '/instances': '实例',
  '/traffic': '流量',
  '/costs': '成本',
  '/server': '服务器',
  '/sync': '同步',
  '/settings': '设置',
};

function HeaderActions() {
  const handleLogout = async (): Promise<void> => {
    await logout();
    clearAuthSessionCache();
    message.success('已退出登录');
    history.replace('/login');
  };

  return (
    <Button className="header-logout-button" icon={<LogoutOutlined />} onClick={handleLogout}>
      退出登录
    </Button>
  );
}

export const layout: RunTimeLayoutConfig = () => ({
  title: 'OCI ARM 成本监控',
  logo: false,
  layout: 'top',
  navTheme: 'light',
  fixedHeader: true,
  headerHeight: 68,
  contentWidth: 'Fluid',
  splitMenus: false,
  className: 'monitor-pro-layout',
  contentStyle: {
    padding: 0,
    background: '#eef3f6',
  },
  menu: {
    locale: false,
  },
  menuItemRender: (item, dom) => {
    if (!item.path) {
      return dom;
    }
    return (
      <Link className="top-menu-link" to={item.path}>
        {menuIconMap[item.path]}
        <span>{menuLabelMap[item.path] ?? item.name}</span>
      </Link>
    );
  },
  token: {
    header: {
      colorBgHeader: '#0b1520',
      colorHeaderTitle: '#eef8f6',
    },
  },
  onMenuHeaderClick: () => history.push('/dashboard'),
  rightContentRender: () => <HeaderActions />,
});

export function rootContainer(container: React.ReactNode) {
  return (
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: '#17786b',
          colorInfo: '#17786b',
          colorSuccess: '#1f8f6f',
          colorWarning: '#b7791f',
          colorError: '#c2413a',
          colorBgLayout: '#eef3f6',
          colorBgContainer: '#fbfcfd',
          colorTextBase: '#14212b',
          colorTextSecondary: '#637381',
          colorBorder: '#d8e1e8',
          borderRadius: 8,
          boxShadow:
            '0 14px 36px rgba(20, 33, 43, 0.08), 0 1px 0 rgba(255, 255, 255, 0.72) inset',
          fontFamily:
            '"HarmonyOS Sans SC", "MiSans", "Alibaba PuHuiTi", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
        },
        components: {
          Card: {
            borderRadiusLG: 8,
            headerBg: 'transparent',
            headerFontSize: 15,
            headerHeight: 52,
            bodyPadding: 20,
            boxShadowTertiary: '0 14px 36px rgba(20, 33, 43, 0.07)',
          },
          Button: {
            borderRadius: 8,
            fontWeight: 700,
            primaryShadow: '0 10px 24px rgba(23, 120, 107, 0.22)',
          },
          Menu: {
            horizontalItemBorderRadius: 8,
            horizontalItemHoverBg: 'rgba(238, 248, 246, 0.08)',
            horizontalItemSelectedBg: 'rgba(29, 199, 174, 0.16)',
            horizontalItemSelectedColor: '#f7fffd',
            horizontalItemHoverColor: '#f7fffd',
            itemPaddingInline: 12,
          },
          Table: {
            headerBg: '#f2f6f8',
            headerColor: '#40515f',
            rowHoverBg: '#f3f8f7',
          },
          Tabs: {
            itemSelectedColor: '#17786b',
            inkBarColor: '#17786b',
          },
          Progress: {
            defaultColor: '#17786b',
          },
        },
      }}
    >
      <AntdApp>{container}</AntdApp>
    </ConfigProvider>
  );
}
