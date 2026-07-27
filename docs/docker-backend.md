# 后端 Docker 部署说明

后端容器只运行 Spring Boot 服务，不包含前端静态文件。前端仍可以单独构建后放到 Nginx，Nginx 反代 `/api/` 到后端容器的 `9090` 端口。

如果后端部署在 Oracle Cloud 实例本机，优先使用 [快速部署配置](quick-deploy.md) 的 Instance Principal 模式，不需要 API Key 和服务器私钥。

## 1. 文件说明

```text
server/Dockerfile       后端多阶段构建镜像
server/.dockerignore    后端构建上下文忽略规则
docker-compose.yml      后端容器启动示例
.env.example            环境变量示例
```

镜像运行阶段：

- Java 17 JRE
- 非 root 用户 `monitor`
- 默认数据库路径 `/data/oci-arm-cost-monitor.db`
- 默认 OCI config 路径 `/home/monitor/.oci/config`

## 2. 准备 `.env`

复制示例文件：

```bash
cp .env.example .env
```

修改：

```bash
MONITOR_ADMIN_USERNAME=admin
MONITOR_ADMIN_PASSWORD=替换为强密码
MONITOR_COOKIE_SECURE=false
MONITOR_CORS_ALLOWED_ORIGINS=https://monitor.example.com
OCI_AUTH_MODE=instance_principal
OCI_CONFIG_PROFILE=DEFAULT
OCI_REGION=ap-seoul-1
OCI_COMPARTMENT_OCID=ocid1.compartment.oc1..替换为目标CompartmentOCID
OCI_TENANCY_OCID=ocid1.tenancy.oc1..替换为TenancyOCID
OCI_CONFIG_DIR=./deploy/oci
MONITOR_OCI_CONNECT_TIMEOUT_MILLIS=10000
MONITOR_OCI_READ_TIMEOUT_MILLIS=60000
MONITOR_LOG_LEVEL=INFO
MONITOR_OCI_SDK_LOG_LEVEL=WARN
MONITOR_SERVER_METRICS_ENABLED=true
MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS=15000
MONITOR_SERVER_HISTORY_RETENTION_HOURS=72
```

说明：

- `MONITOR_ADMIN_USERNAME` / `MONITOR_ADMIN_PASSWORD` 只在数据库还没有管理员账号时用于初始化账号。
- 公网 HTTPS 部署时把 `MONITOR_COOKIE_SECURE` 改为 `true`。
- `MONITOR_CORS_ALLOWED_ORIGINS` 填前端访问域名，例如 `https://monitor.example.com`；多个域名用英文逗号分隔。
- `OCI_AUTH_MODE` 是 OCI 认证模式。Oracle 实例本机部署推荐 `instance_principal`；非 Oracle 服务器部署使用 `config_file`。
- `OCI_REGION` 是资源所在区域，例如 `ap-seoul-1`。
- `OCI_COMPARTMENT_OCID` 是要同步的目标 compartment OCID。
- `OCI_TENANCY_OCID` 是 tenancy OCID。`instance_principal` 模式下费用同步必填；`config_file` 模式可留空，Usage API 会优先使用 OCI config 里的 tenancy。
- `OCI_CONFIG_DIR` 是宿主机上存放 OCI config 和私钥的目录，会只读挂载到容器内。`config_file` 模式才需要真实 config 和私钥。
- `MONITOR_OCI_CONNECT_TIMEOUT_MILLIS` 是 OCI SDK 连接超时，默认 10 秒。
- `MONITOR_OCI_READ_TIMEOUT_MILLIS` 是 OCI SDK 读取超时，默认 60 秒。
- `MONITOR_LOG_LEVEL` 是业务日志级别，默认 `INFO`。
- `MONITOR_OCI_SDK_LOG_LEVEL` 是 Oracle SDK 日志级别，默认 `WARN`，排查 SDK 细节时可临时改成 `INFO`。
- `MONITOR_SERVER_METRICS_ENABLED` 是否启用本机服务器状态采样，默认 `true`。
- `MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS` 服务器状态采样间隔，默认 15 秒。
- `MONITOR_SERVER_HISTORY_RETENTION_HOURS` 服务器状态历史保留小时数，默认 72 小时。
- Oracle/OCI 凭据和部署级配置只在后端 `.env` 与挂载目录中保存，前端不会保存或提交这些字段。

## 3. 准备 OCI config

推荐在项目目录下创建部署专用目录：

```bash
mkdir -p deploy/oci
cp ~/.oci/config deploy/oci/config
cp ~/.oci/oci_api_key.pem deploy/oci/oci_api_key.pem
```

如果你的 `config` 里 `key_file` 原来是宿主机路径，需要改成容器内路径：

```ini
[DEFAULT]
user=ocid1.user.oc1..替换为用户OCID
fingerprint=替换为fingerprint
tenancy=ocid1.tenancy.oc1..替换为租户OCID
region=ap-seoul-1
key_file=/home/monitor/.oci/oci_api_key.pem
```

容器内使用非 root 用户 `monitor`，UID/GID 默认是 `10001`。为了让容器能读取只读挂载的私钥，宿主机上执行：

```bash
sudo chown -R 10001:10001 deploy/oci
sudo chmod 700 deploy/oci
sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem
```

`deploy/oci/` 已加入 `.gitignore`，不要提交私钥。

## 4. 启动后端

构建并启动：

```bash
docker compose up -d --build oci-arm-monitor-server
```

查看日志：

```bash
docker compose logs -f oci-arm-monitor-server
```

同步 OCI 数据时，`/api/sync/full` 只负责启动后台任务。实际进度通过 `/api/sync/status` 和容器日志查看：

```bash
docker compose logs --tail=300 oci-arm-monitor-server
```

日志中会输出 `OCI sync run ... progress`，用于判断同步卡在实例、VNIC、Monitoring 指标还是 Usage API 费用步骤。

自动同步默认已启用，默认 Cron 为：

```text
0 0 0 * * *
```

时区为 `Asia/Shanghai`，也就是每天凌晨 00:00 执行一次。可在前端「同步中心」修改 Cron、时区、是否服务启动后同步一次，并查看同步历史。

服务器状态页面需要读取宿主机 `/proc`。Compose 已包含只读挂载：

```yaml
volumes:
  - /proc:/host/proc:ro
```

升级到新版本后建议强制重建容器，确保挂载生效：

```bash
docker compose up -d --build --force-recreate oci-arm-monitor-server
```

每个 HTTP 请求也会输出一行请求日志，包含 method、path、status、耗时、clientIp 和 `requestId`。响应头会带 `X-Request-Id`，浏览器 Network 面板里可以复制这个值再去容器日志里搜索。

```bash
docker compose logs --tail=300 oci-arm-monitor-server | grep '<X-Request-Id>'
```

服务端不会记录请求体、Cookie、密码、Token 或 OCI 私钥内容。Docker 日志已配置轮转，默认单文件 20MB，保留 5 个文件。

查看状态：

```bash
docker compose ps
```

后端地址：

```text
http://服务器IP:9090/api
```

如果只允许 Nginx 本机反代访问，可以把 `docker-compose.yml` 里的端口改成：

```yaml
ports:
  - "127.0.0.1:9090:9090"
```

## 5. 直接 Docker 命令

不用 compose 时：

```bash
docker build -t oci-arm-cost-monitor-server:latest ./server

docker volume create oci-arm-monitor-data

docker run -d \
  --name oci-arm-cost-monitor-server \
  --restart unless-stopped \
  -p 9090:9090 \
  -e MONITOR_ADMIN_USERNAME=admin \
  -e MONITOR_ADMIN_PASSWORD='替换为强密码' \
  -e MONITOR_COOKIE_SECURE=false \
  -e MONITOR_CORS_ALLOWED_ORIGINS='https://monitor.example.com' \
  -e OCI_MONITOR_DB=/data/oci-arm-cost-monitor.db \
  -e OCI_AUTH_MODE=instance_principal \
  -e OCI_CONFIG_FILE_PATH=/home/monitor/.oci/config \
  -e OCI_CONFIG_PROFILE=DEFAULT \
  -e OCI_REGION=ap-seoul-1 \
  -e OCI_TENANCY_OCID='替换为TenancyOCID' \
  -e OCI_COMPARTMENT_OCID='替换为目标CompartmentOCID' \
  -e MONITOR_OCI_CONNECT_TIMEOUT_MILLIS=10000 \
  -e MONITOR_OCI_READ_TIMEOUT_MILLIS=60000 \
  -e MONITOR_SERVER_METRICS_ENABLED=true \
  -e MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS=15000 \
  -e MONITOR_SERVER_HISTORY_RETENTION_HOURS=72 \
  -e MONITOR_SERVER_PROC_PATH=/host/proc \
  -e MONITOR_SERVER_DISK_PATH=/data \
  -v oci-arm-monitor-data:/data \
  -v "$PWD/deploy/oci:/home/monitor/.oci:ro" \
  -v /proc:/host/proc:ro \
  oci-arm-cost-monitor-server:latest
```

## 6. 升级后端镜像

```bash
docker compose build oci-arm-monitor-server
docker compose up -d oci-arm-monitor-server
```

SQLite 数据在 Docker named volume `oci-arm-monitor-data` 中，不会因为重新构建镜像丢失。

## 7. 备份 SQLite

```bash
docker run --rm \
  -v oci-arm-cost-monitor_oci-arm-monitor-data:/data \
  -v "$PWD/backups:/backups" \
  busybox \
  sh -c 'cp /data/oci-arm-cost-monitor.db /backups/oci-arm-cost-monitor-$(date +%Y%m%d%H%M%S).db'
```

如果 compose 项目名不是默认目录名，volume 名可能不同，可用下面命令确认：

```bash
docker volume ls | grep oci-arm-monitor-data
```
