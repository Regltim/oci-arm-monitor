# OCI ARM Monitor

面向 Oracle Cloud ARM 实例的自托管监控与成本面板。系统从 OCI API 读取实例、指标、流量和费用数据，并将同步结果保存在服务器本地 SQLite 中。

## 主要功能

- 总览：实例数量、费用、出站流量、免费额度使用率和风险提醒。
- 实例：运行状态、公网/内网 IP、CPU 和内存趋势。
- 流量：本月入站、出站流量及免费额度进度。
- 成本：OCI Usage API 账单明细和手工费用记录。
- 服务器状态：宿主机 CPU、内存、磁盘、网络、JVM 和 SQLite 状态。
- 同步中心：手动同步、定时同步、同步进度和历史结果。
- 系统设置：OCI 配置状态、连接诊断和免费额度配置。

## 部署方式

默认使用两个 Docker 容器：

```text
浏览器
  |
  | HTTP 或 HTTPS
  v
oci-arm-monitor-web
  |-- 前端静态页面
  |-- /api/* 反向代理
  v
oci-arm-monitor-server
  |-- Spring Boot
  |-- SQLite
  |-- OCI SDK
```

服务器只需要 Docker、Docker Compose、`curl` 和 `jq`，不需要安装 Nginx、Node.js、pnpm、Java 或 Certbot。后端 `9090` 默认只在 Docker 网络内可用。

## 快速开始

1. 获取项目并进入目录：

```bash
git clone <repository-url> oci-arm-monitor
cd oci-arm-monitor
```

2. 运行初始化脚本：

```bash
bash scripts/init-deploy.sh
```

初始化时可选择：

| 模式 | 访问地址 | 网络要求 |
| --- | --- | --- |
| HTTP | `http://<server-ip>:8080` | 放行所选 TCP 端口，默认 `8080` |
| HTTPS | `https://monitor.example.com` | 域名解析到服务器，放行 TCP `80/443` |

3. 启动前端和后端：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

脚本会在结束时打印最终访问地址。首次登录使用初始化时设置的管理员账号和密码。

如果应用部署在 OCI Compute 实例上，推荐使用 Instance Principal。初始化脚本会自动读取 Instance Metadata，并生成可复制到 OCI Cloud Shell 的 IAM 配置命令。完整步骤见 [Oracle ARM 两步快速部署](docs/quick-deploy.md)。

如果应用不在 OCI 实例上，选择 `config_file`，按 [OCI 接入说明](docs/oci-setup.md) 准备 API Key 和 OCI config。

## 首次使用

登录后进入：

```text
系统设置 -> OCI 配置 -> 运行 OCI 连接诊断
```

诊断通过后点击“同步 OCI 数据”。同步任务在后端运行，可在“同步中心”查看当前步骤和历史结果。

默认同步范围：

- 实例和网卡：当前快照。
- CPU 和内存：最近 48 小时。
- 流量：当前自然月。
- Usage API 费用：当前自然月已完成的 UTC 日期。

默认定时同步为每天 `00:00`（`Asia/Shanghai`），可在同步中心修改。

## 数据与安全

- 系统不内置业务演示资源，不会把样例数据显示成真实 OCI 数据。
- Instance Principal 模式不需要在服务器保存 OCI API 私钥。
- API Key 模式的 `.env`、`deploy/oci/`、私钥和 SQLite 数据均由 Git 忽略。
- 登录使用服务端 Session 和 HttpOnly Cookie。
- HTTP/HTTPS 模式会自动生成匹配的 Origin 和 Cookie 配置。
- Caddy 状态、HTTPS 证书和 SQLite 数据使用 Docker volume 持久化。

## 常用命令

```bash
docker compose ps
docker compose logs -f
docker compose logs --tail=200 oci-arm-monitor-web
docker compose logs --tail=200 oci-arm-monitor-server
```

更新版本：

```bash
git pull --ff-only
docker compose up -d --build
```

## 文档

- [Oracle ARM 两步快速部署](docs/quick-deploy.md)
- [全新服务器部署手册](docs/fresh-deploy-docker-compose.md)
- [Docker 容器与数据说明](docs/docker-backend.md)
- [OCI 接入说明](docs/oci-setup.md)
- [Oracle 控制台通俗配置流程](docs/oracle-console-simple-guide.md)

## 使用边界

- 费用数据来自 OCI Usage API，不读取对象存储中的 Cost Reports CSV。
- Monitoring 是否产生费用取决于 Oracle 当前价格与查询规模，部署前请核对官方价格表。
- 项目不会自动修改 OCI Security List、NSG、操作系统防火墙或 DNS。
- 没有正确的 OCI 认证、IAM Policy 和实例监控插件时，页面只能显示配置状态和空态，不能同步云端数据。
