import { defineConfig } from '@umijs/max';

export default defineConfig({
  npmClient: 'pnpm',
  hash: true,
  history: {
    type: 'hash',
  },
  antd: {},
  layout: {
    title: 'OCI ARM 成本监控',
    locale: false,
  },
  request: {},
  routes: [
    { path: '/login', component: './Login', layout: false },
    { path: '/', redirect: '/dashboard' },
    { name: '总览看板', path: '/dashboard', component: './Dashboard', wrappers: ['@/wrappers/auth'] },
    { name: '实例监控', path: '/instances', component: './Instances', wrappers: ['@/wrappers/auth'] },
    { name: '流量分析', path: '/traffic', component: './Traffic', wrappers: ['@/wrappers/auth'] },
    { name: '成本分析', path: '/costs', component: './Costs', wrappers: ['@/wrappers/auth'] },
    { name: '服务器状态', path: '/server', component: './ServerStatus', wrappers: ['@/wrappers/auth'] },
    { name: '同步中心', path: '/sync', component: './SyncCenter', wrappers: ['@/wrappers/auth'] },
    { name: '系统设置', path: '/settings', component: './Settings', wrappers: ['@/wrappers/auth'] },
  ],
  proxy: {
    '/api': {
      target: 'http://localhost:9090',
      changeOrigin: true,
    },
  },
});
