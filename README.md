# OCI ARM 成本监控

面向 Oracle Cloud ARM 免费资源的监控和成本分析工具。项目采用前后端分离，但统一放在一个目录下维护。

## 目录结构

```text
oci-arm-cost-monitor/
  server/  Spring Boot 后端，负责登录校验、SQLite 落库、聚合 API、OCI 同步
  web/     Umi Max 前端，负责总览、实例、流量、成本和设置页面
  docs/    OCI 接入、部署和权限说明
```

## 技术栈

- 前端：React 18、TypeScript、Umi Max、Ant Design 5、ProComponents、ECharts、pnpm
- 后端：Java 17、Spring Boot 3.5、JDBC、SQLite、OCI Java SDK、Spring Scheduling
- 数据库：默认 SQLite 文件 `server/oci-arm-cost-monitor.db`
- 登录：服务端 Session + HttpOnly Cookie
- CORS：生产域名通过 `MONITOR_CORS_ALLOWED_ORIGINS` 配置
- 后端容器：Docker / Docker Compose

## 当前实现范围

- 登录页和后端登录校验，未登录访问会跳转到 `/login`
- 总览看板：费用、实例数、出站流量、免费额度使用率、风险提醒
- 实例监控：实例列表、CPU/内存趋势
- 流量分析：入站/出站流量、本月出站免费额度进度
- 成本分析：OCI Usage API 账单明细、手工费用新增和删除
- 服务器状态：CPU、内存、磁盘、网络速率、JVM、SQLite 大小、历史曲线和告警规则
- 同步中心：手动同步、定时同步配置、同步历史
- 系统设置：后端 OCI 配置状态、OCI 连接诊断、首次部署引导、免费额度配置

## 数据来源

系统不内置业务演示资源，不会把本地样例数据伪装成真实云资源。

- 实例：OCI Compute API
- 公网/内网 IP：OCI VNIC API
- CPU/内存/流量：OCI Monitoring API
- 用量和费用：OCI Usage API
- 手工费用：用户在页面手动录入

第一次启动如果后端没有配置 OCI 环境变量或没有执行同步，页面会显示未配置、未同步或空态。默认定时同步为每天 `00:00`（`Asia/Shanghai`）执行一次，可在前端「同步中心」修改。

## 同步策略

同步不是无限制拉取所有历史数据：

- 实例和网卡：读取当前目标 compartment 的最新快照。
- CPU/内存指标：同步最近 48 小时，按实例、指标、时间覆盖写入。
- 流量：同步当前自然月，按实例和日期覆盖写入。
- Usage API 费用：同步当前自然月已完成的 UTC 日期，按服务、资源、日期、单位覆盖写入。

这样手动同步和定时同步重复运行不会产生重复数据。

## 本地启动

后端需要先设置管理员账号，首次启动时会写入本地 SQLite：

```bash
cd server
MONITOR_ADMIN_USERNAME=admin MONITOR_ADMIN_PASSWORD='替换为强密码' mvn spring-boot:run
```

前端：

```bash
cd web
pnpm install
pnpm dev
```

默认地址：

- 前端：http://localhost:8000
- 后端：http://localhost:9090/api

## Docker 启动后端

后端推荐用 Docker 跑服务：

```bash
cp .env.example .env
docker compose up -d --build oci-arm-monitor-server
docker compose logs -f oci-arm-monitor-server
```

Oracle ARM 本机优先使用 [Cloud Shell 两步快速部署](docs/quick-deploy.md)：实例脚本自动读取 OCI Metadata，Cloud Shell 脚本创建 Dynamic Group 和 Policy。详细说明见 [docs/docker-backend.md](docs/docker-backend.md)，全新服务器部署见 [docs/fresh-deploy-docker-compose.md](docs/fresh-deploy-docker-compose.md)。

## 对接真实 OCI

详细步骤见 [docs/oci-setup.md](docs/oci-setup.md)。如果不熟悉 Oracle 控制台和 IAM Policy，先看通俗版流程：[docs/oracle-console-simple-guide.md](docs/oracle-console-simple-guide.md)。

关键要求：

- 推荐使用 Instance Principal：给当前监控实例配置 Dynamic Group 和只读 IAM policy，不需要在服务器保存 OCI 私钥。
- 如果后端不在 Oracle 实例上，再使用 API Key 模式：创建 `~/.oci/config`、API 私钥文件，并在 OCI 用户上上传 API 公钥。
- 在 ARM 实例上启用 Compute Instance Monitoring plugin。
- 在后端 `.env` 配置 `OCI_AUTH_MODE`、`OCI_REGION`、`OCI_TENANCY_OCID`、`OCI_COMPARTMENT_OCID`，前端只查看配置状态并点击同步。

## 免费优先原则

- 不使用 OCI Logging、Notifications、Alarm、额外托管数据库等可能带来额外费用的资源。
- Monitoring 指标按手动同步落到本地 SQLite，页面读取本地聚合数据。
- 当前成本数据优先使用 Usage API；对象存储 Cost Reports CSV 可作为后续增强，不作为当前必需项。
- Monitoring 是否产生费用取决于 Oracle 当前价格表中的 ingestion/retrieval 额度和你的查询规模，部署前请核对官方价格表。
- 服务器状态来自本机 `/proc` 和 JVM runtime，不调用 OCI 付费服务。

## 开源发布

发布前运行：

```bash
bash scripts/check-public-release.sh
```

不要直接公开带旧作者信息的开发仓库历史。推荐从检查通过的当前快照创建新的公开仓库，详见 [docs/open-source-release.md](docs/open-source-release.md)。
