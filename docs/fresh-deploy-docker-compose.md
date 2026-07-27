# 全新部署手册（Docker Compose）

本文档面向一台全新的 Linux 服务器，目标是部署 OCI ARM 成本监控面板：

> 如果面板就部署在 Oracle Cloud 实例本机，优先看快速版：[quick-deploy.md](quick-deploy.md)。快速版使用 Instance Principal，不需要 API Key 和服务器私钥。

- 后端：Docker Compose 运行 Spring Boot 服务。
- 数据库：SQLite，持久化到 Docker volume。
- OCI 凭据：推荐 Instance Principal；API Key 模式才需要宿主机目录只读挂载到容器。
- Oracle/OCI 配置：只放后端 `.env` 和服务器文件，前端不保存凭据或 OCID。
- 服务器状态：容器只读挂载宿主机 `/proc`，用于展示 CPU、内存、网络和运行时间。
- 前端：本地或服务器构建后，由 Nginx 提供静态文件，并反向代理 `/api/` 到后端。

> 文档中的 `monitor.example.com`、OCID、region、密码、compartment 名称都需要替换成你的真实值。

## 1. 部署架构

```text
浏览器
  |
  | https://monitor.example.com
  v
Nginx
  |-- /            -> 前端静态文件 /var/www/oci-arm-cost-monitor
  |-- /api/        -> http://127.0.0.1:9090/api/
                      |
                      v
                Docker Compose 后端容器
                  - Spring Boot
                  - SQLite: /data/oci-arm-cost-monitor.db
                  - OCI config: /home/monitor/.oci/config
```

## 2. 服务器准备

以 Ubuntu/Debian 为例：

```bash
sudo apt update
sudo apt install -y ca-certificates curl gnupg jq nginx openssl rsync
```

安装 Docker：

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
```

重新登录 SSH 后验证：

```bash
docker --version
docker compose version
```

建议目录：

```bash
sudo mkdir -p /opt/oci-arm-cost-monitor
sudo chown -R "$USER":"$USER" /opt/oci-arm-cost-monitor
cd /opt/oci-arm-cost-monitor
```

## 3. 获取项目代码

如果用 Git：

```bash
git clone <你的仓库地址> .
```

已有 Git 部署目录更新代码时执行：

```bash
git status --short
git pull --ff-only
```

如果是手工上传，把整个项目目录上传到：

```text
/opt/oci-arm-cost-monitor
```

如果旧目录来自 GitHub ZIP，请把最新 ZIP 解压到新目录，再保留服务器本机的 `.env`、`deploy/oci/` 和持久化数据。不要直接上传包含这些私有文件的整个服务器目录。

确认目录里至少有：

```text
docker-compose.yml
.env.example
server/Dockerfile
web/package.json
```

## 4. Oracle Cloud 配置

本章是非 OCI 服务器使用的 API Key 备用路径。如果应用就运行在 Oracle Cloud ARM 实例本机，优先使用 [Cloud Shell 两步快速部署](quick-deploy.md)，并跳过本章的私钥配置。

如果你第一次配置 Oracle IAM Policy，建议先按通俗版流程走一遍：[oracle-console-simple-guide.md](oracle-console-simple-guide.md)。

### 4.1 创建 API Key

在部署服务器上执行：

```bash
mkdir -p /opt/oci-arm-cost-monitor/deploy/oci
cd /opt/oci-arm-cost-monitor/deploy/oci

openssl genrsa -out oci_api_key.pem 2048
openssl rsa -pubout -in oci_api_key.pem -out oci_api_key_public.pem
```

登录 OCI Console：

1. 进入 API 调用用户的详情页。
2. 打开 API Keys。
3. 上传或粘贴 `oci_api_key_public.pem`。
4. 记录生成的 fingerprint。

Oracle 官方文档说明 API signing key 需要 RSA PEM 公钥，并可从 API key 页面获取 config snippet。参见 Oracle 的 [API signing key 文档](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm)。

### 4.2 创建 OCI config

创建：

```bash
cd /opt/oci-arm-cost-monitor/deploy/oci
nano config
```

内容示例：

```ini
[DEFAULT]
user=ocid1.user.oc1..替换为用户OCID
fingerprint=替换为APIKeyFingerprint
tenancy=ocid1.tenancy.oc1..替换为租户OCID
region=ap-seoul-1
key_file=/home/monitor/.oci/oci_api_key.pem
```

字段说明：

- `user`：API 调用用户 OCID。
- `fingerprint`：上传 API 公钥后 OCI 生成的 fingerprint。
- `tenancy`：租户 OCID。
- `region`：资源所在 region，例如 `ap-seoul-1`、`ap-tokyo-1`。
- `key_file`：必须写容器内路径 `/home/monitor/.oci/oci_api_key.pem`。

Oracle SDK/CLI config 官方要求包含 user、fingerprint、tenancy、region、key_file 等字段，参见 [SDK and CLI Configuration File](https://docs.oracle.com/iaas/Content/API/Concepts/sdkconfig.htm)。

### 4.3 设置文件权限

后端容器默认使用 UID/GID `10001` 的非 root 用户 `monitor` 读取 OCI config：

```bash
cd /opt/oci-arm-cost-monitor

sudo chown -R 10001:10001 deploy/oci
sudo chmod 700 deploy/oci
sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem
```

不要把 `deploy/oci/` 提交到 Git。项目 `.gitignore` 已忽略该目录。

## 5. Oracle IAM Policy

这一节是简版策略。看不懂时优先看 [oracle-console-simple-guide.md](oracle-console-simple-guide.md)，里面按控制台点击顺序解释了用户、用户组、compartment 和 policy。

创建用户组，例如：

```text
oci-monitor-readers
```

把 API Key 所属用户加入该组。

在 OCI Console 的 Identity & Security -> Policies 中添加只读策略。最小起点如下：

```text
Allow group oci-monitor-readers to read instance-family in compartment <compartment-name>
Allow group oci-monitor-readers to read virtual-network-family in compartment <compartment-name>
Allow group oci-monitor-readers to read metrics in compartment <compartment-name>
Allow group oci-monitor-readers to read usage-report in tenancy
```

说明：

- `instance-family`：读取 Compute 实例。
- `virtual-network-family`：读取 VNIC、公网 IP、私网 IP。
- `metrics`：读取 Monitoring 指标。
- `usage-report`：读取 Usage API / 成本用量数据，通常需要 tenancy 范围。

如果资源分布在多个 compartment，需要给每个 compartment 配策略，或者改成 tenancy 范围。Oracle 的常见策略可参考 [Common Policies](https://docs.oracle.com/iaas/Content/Identity/Concepts/commonpolicies.htm)。

## 6. 开启 Compute Instance Monitoring

每台需要监控的 ARM Compute 实例都要确认 Oracle Cloud Agent 中的 Compute Instance Monitoring plugin 已启用并运行。

Console 路径通常是：

```text
Compute -> Instances -> 选择实例 -> Oracle Cloud Agent / Management -> Compute Instance Monitoring
```

也可以进入实例详情的 Metrics 页面，Metric namespace 选择：

```text
oci_computeagent
```

如果能看到 CPU、Memory 等图表，说明 Monitoring 正在收到指标。Oracle 官方说明：实例只有在 Compute Instance Monitoring plugin 启用并运行时才会发出 compute instance metrics，参见 [Enabling Monitoring for Compute Instances](https://docs.oracle.com/iaas/Content/Compute/Tasks/enablingmonitoring.htm) 和 [Oracle Cloud Agent](https://docs.oracle.com/en-us/iaas/Content/Compute/Tasks/manage-plugins.htm)。

## 7. 配置 Docker Compose 环境变量

在项目根目录：

```bash
cd /opt/oci-arm-cost-monitor
cp .env.example .env
nano .env
```

示例。当前章节是 API Key / OCI config 完整路径，如果使用快速版 Instance Principal，请以 [quick-deploy.md](quick-deploy.md) 为准：

```env
MONITOR_ADMIN_USERNAME=admin
MONITOR_ADMIN_PASSWORD=替换为强密码
MONITOR_COOKIE_SECURE=true
MONITOR_CORS_ALLOWED_ORIGINS=https://monitor.example.com
OCI_AUTH_MODE=config_file
OCI_CONFIG_PROFILE=DEFAULT
OCI_REGION=ap-seoul-1
OCI_COMPARTMENT_OCID=ocid1.compartment.oc1..替换为目标CompartmentOCID
OCI_TENANCY_OCID=
OCI_CONFIG_DIR=./deploy/oci
MONITOR_OCI_CONNECT_TIMEOUT_MILLIS=10000
MONITOR_OCI_READ_TIMEOUT_MILLIS=60000
MONITOR_LOG_LEVEL=INFO
MONITOR_OCI_SDK_LOG_LEVEL=WARN
MONITOR_SERVER_METRICS_ENABLED=true
MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS=15000
MONITOR_SERVER_HISTORY_RETENTION_HOURS=72
```

字段说明：

- `MONITOR_ADMIN_USERNAME`：初始化管理员用户名。
- `MONITOR_ADMIN_PASSWORD`：初始化管理员密码。
- `MONITOR_COOKIE_SECURE`：HTTPS 生产环境建议 `true`；如果只是 HTTP 内网测试，设为 `false`。
- `MONITOR_CORS_ALLOWED_ORIGINS`：允许访问后端的前端域名。生产环境填你的面板域名，例如 `https://monitor.example.com`。
- `OCI_AUTH_MODE`：OCI 认证模式。Oracle 实例本机部署推荐 `instance_principal`；本文 API Key 路径使用 `config_file`。
- `OCI_CONFIG_PROFILE`：OCI config 里的 profile，默认 `DEFAULT`。`config_file` 模式需要配置。
- `OCI_REGION`：资源所在 region，例如 `ap-seoul-1`。
- `OCI_COMPARTMENT_OCID`：要同步的目标 compartment OCID。
- `OCI_TENANCY_OCID`：通常可留空，Usage API 会优先使用 OCI config 中的 tenancy；需要显式指定时再填写。
- `OCI_CONFIG_DIR`：宿主机 OCI config 目录，compose 会只读挂载到容器内。`config_file` 模式才需要真实 config 和私钥。
- `MONITOR_OCI_CONNECT_TIMEOUT_MILLIS`：OCI SDK 连接超时，默认 10 秒。
- `MONITOR_OCI_READ_TIMEOUT_MILLIS`：OCI SDK 读取超时，默认 60 秒；如果 Usage API 偶发较慢，可以适当调大。
- `MONITOR_LOG_LEVEL`：业务日志级别，默认 `INFO`。
- `MONITOR_OCI_SDK_LOG_LEVEL`：Oracle SDK 日志级别，默认 `WARN`，排查 SDK 细节时可临时改为 `INFO`。
- `MONITOR_SERVER_METRICS_ENABLED`：是否启用服务器状态采样，默认 `true`。
- `MONITOR_SERVER_METRICS_SAMPLE_DELAY_MILLIS`：服务器状态采样间隔，默认 15 秒。
- `MONITOR_SERVER_HISTORY_RETENTION_HOURS`：服务器状态历史保留小时数，默认 72 小时。

注意：

- 管理员账号只在 SQLite 里还没有管理员用户时初始化。
- 如果已经启动过并生成了数据库，修改 `.env` 中的管理员密码不会自动改库里的密码。

## 8. 启动后端容器

构建并启动：

```bash
docker compose up -d --build oci-arm-monitor-server
```

查看状态：

```bash
docker compose ps
```

查看日志：

```bash
docker compose logs -f oci-arm-monitor-server
```

后端监听：

```text
http://127.0.0.1:9090/api
```

如果你不希望后端端口暴露到公网，建议把 `docker-compose.yml` 的端口改成：

```yaml
ports:
  - "127.0.0.1:9090:9090"
```

## 9. 构建前端

服务器上安装 Node.js 和 pnpm，或者在本地构建后上传 `web/dist`。

服务器构建方式：

```bash
cd /opt/oci-arm-cost-monitor/web
corepack enable
pnpm install
pnpm build
```

部署静态文件：

```bash
sudo mkdir -p /var/www/oci-arm-cost-monitor
sudo rsync -a --delete dist/ /var/www/oci-arm-cost-monitor/
sudo chown -R www-data:www-data /var/www/oci-arm-cost-monitor
```

确认生产静态文件已经包含新路由：

```bash
grep -R "服务器状态\|/server" /var/www/oci-arm-cost-monitor/assets | head
```

如果没有任何输出，说明线上静态目录还是旧前端包，需要重新 `pnpm build` 并再次 `rsync --delete dist/`。

## 10. 配置 Nginx

创建：

```bash
sudo nano /etc/nginx/sites-available/oci-arm-cost-monitor
```

HTTP 示例：

```nginx
server {
  listen 80;
  server_name monitor.example.com;

  root /var/www/oci-arm-cost-monitor;
  index index.html;

  location /api/ {
    proxy_pass http://127.0.0.1:9090/api/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }

  location / {
    try_files $uri $uri/ /index.html;
  }
}
```

启用配置：

```bash
sudo ln -sf /etc/nginx/sites-available/oci-arm-cost-monitor /etc/nginx/sites-enabled/oci-arm-cost-monitor
sudo nginx -t
sudo systemctl reload nginx
```

生产环境建议配置 HTTPS。配置 HTTPS 后，把 `.env` 中的：

```env
MONITOR_COOKIE_SECURE=true
```

然后重启后端：

```bash
docker compose up -d oci-arm-monitor-server
```

## 11. 首次登录和同步

访问：

```text
https://monitor.example.com/#/login
```

登录：

- 用户名：`.env` 中的 `MONITOR_ADMIN_USERNAME`
- 密码：`.env` 中的 `MONITOR_ADMIN_PASSWORD`

进入系统设置，确认：

- OCI 配置状态为已配置。
- 同步状态不是后端环境变量未配置完整。

Oracle/OCI 的 config 路径、profile、region、compartment 都由后端 `.env` 和只读挂载目录控制，前端不会保存这些字段。

然后点击：

```text
同步 OCI 数据
```

同步任务会在后端后台执行，页面会轮询 `/api/sync/status` 展示当前步骤。同步成功后，总览、实例、流量、成本页面会显示真实落库数据。

定时同步默认已启用：

```text
Cron: 0 0 0 * * *
时区: Asia/Shanghai
```

也就是每天凌晨 00:00 自动同步一次。登录后可以进入「同步中心」修改 Cron、时区、是否开机同步一次，并查看同步历史。

同步范围不是无限制全量历史：

- 实例和网卡：读取当前快照。
- CPU/内存指标：最近 48 小时。
- 流量：当前自然月。
- Usage API 费用：当前自然月里已经完成的 UTC 日期。

数据写入使用唯一键覆盖，同一天、同一资源重复同步不会产生重复行。

前端使用 hash 路由，页面地址形如：

```text
https://monitor.example.com/#/dashboard
https://monitor.example.com/#/settings
```

这样刷新页面不会直接请求服务器上的 `/settings` 静态路径。部署后请统一使用 `/#/...` 地址，不再兼容旧的 `/settings` 直连地址。

## 12. 验证命令

后端未登录应返回 401：

```bash
curl -i http://127.0.0.1:9090/api/dashboard/summary
```

登录并保存 Cookie：

```bash
curl -i -c /tmp/oci-monitor-cookie.txt \
  -X POST http://127.0.0.1:9090/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"替换为你的密码"}'
```

查看同步状态：

```bash
curl -i -b /tmp/oci-monitor-cookie.txt \
  http://127.0.0.1:9090/api/sync/status
```

查看服务器状态：

```bash
curl -i -b /tmp/oci-monitor-cookie.txt \
  http://127.0.0.1:9090/api/server/status
```

查看定时同步配置：

```bash
curl -i -b /tmp/oci-monitor-cookie.txt \
  http://127.0.0.1:9090/api/sync/schedule
```

查看容器日志：

```bash
docker compose logs --tail=200 oci-arm-monitor-server
```

## 13. 数据备份

SQLite 在 Docker volume `oci-arm-cost-monitor_oci-arm-monitor-data` 中。先确认 volume 名：

```bash
docker volume ls | grep oci-arm-monitor-data
```

备份：

```bash
mkdir -p backups

docker run --rm \
  -v oci-arm-cost-monitor_oci-arm-monitor-data:/data \
  -v "$PWD/backups:/backups" \
  busybox \
  sh -c 'cp /data/oci-arm-cost-monitor.db /backups/oci-arm-cost-monitor-$(date +%Y%m%d%H%M%S).db'
```

## 14. 升级部署

拉取或上传新代码后：

```bash
cd /opt/oci-arm-cost-monitor
docker compose build oci-arm-monitor-server
docker compose up -d oci-arm-monitor-server
```

前端升级：

```bash
cd /opt/oci-arm-cost-monitor/web
pnpm install
pnpm build
sudo rsync -a --delete dist/ /var/www/oci-arm-cost-monitor/
sudo systemctl reload nginx
```

注意：后端 Docker 重新构建不会自动更新前端菜单。`服务器状态` 菜单来自前端静态包，升级后必须重新构建并同步 `web/dist`。

## 15. 常见问题

### 15.1 容器启动失败，提示缺少环境变量

确认 `.env` 存在，并且包含：

```env
MONITOR_ADMIN_USERNAME=...
MONITOR_ADMIN_PASSWORD=...
MONITOR_CORS_ALLOWED_ORIGINS=...
OCI_REGION=...
OCI_COMPARTMENT_OCID=...
```

### 15.2 OCI 同步返回 401 / 403

检查：

- `deploy/oci/config` 里的 user、tenancy、fingerprint、region 是否正确。
- API 公钥是否上传到了同一个 user。
- IAM policy 是否绑定到该 user 所属 group。
- Usage API 是否有 `read usage-report in tenancy`。

### 15.3 容器提示无法读取私钥

检查 `deploy/oci/config`：

```ini
key_file=/home/monitor/.oci/oci_api_key.pem
```

检查权限：

```bash
ls -la deploy/oci
sudo chown -R 10001:10001 deploy/oci
sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem
```

### 15.4 页面能打开，但接口 404 或 502

检查 Nginx：

```bash
sudo nginx -t
sudo systemctl status nginx
```

检查后端：

```bash
docker compose ps
docker compose logs --tail=200 oci-arm-monitor-server
curl -i http://127.0.0.1:9090/api/sync/status
```

### 15.5 端口 9090 被占用

查占用：

```bash
sudo lsof -i :9090
```

如果是旧后端进程，停止后再启动 Docker Compose。

### 15.6 修改 `.env` 密码后登录还是旧密码

管理员账号只会在首次初始化数据库时创建。已有数据库不会因为 `.env` 改动自动更新管理员密码。

全新部署时可以删除 Docker volume 后重建，但会清空已有数据：

```bash
docker compose down
docker volume rm oci-arm-cost-monitor_oci-arm-monitor-data
docker compose up -d --build oci-arm-monitor-server
```

生产环境删除 volume 前务必先备份。

### 15.7 页面一直显示同步中

新版后端会把同步放到后台执行。排查时先看同步状态：

```bash
curl -i -b /tmp/oci-monitor-cookie.txt \
  http://127.0.0.1:9090/api/sync/status
```

重点看返回里的：

- `lastStatus`：`RUNNING` 表示后台仍在跑，`FAILED` 表示同步失败。
- `lastMessage`：当前卡在哪一步，例如读取实例、读取主网卡、同步 Monitoring 指标或同步 Usage API 费用。

再看容器日志：

```bash
docker compose logs --tail=300 oci-arm-monitor-server
```

日志中会出现类似：

```text
OCI sync run <id> progress: 正在读取 Compute 实例列表。
OCI sync run <id> progress: 本月流量已同步，正在同步 Usage API 费用。
```

每个 HTTP 请求也会输出一行请求日志，包含 method、path、status、耗时、clientIp 和 `requestId`。响应头会带 `X-Request-Id`，浏览器 Network 面板里可以复制这个值再去容器日志里搜索。

```bash
docker compose logs --tail=300 oci-arm-monitor-server | grep '<X-Request-Id>'
```

服务端不会记录请求体、Cookie、密码、Token 或 OCI 私钥内容。Docker 日志已配置轮转，默认单文件 20MB，保留 5 个文件。

如果服务重启时有未完成的旧同步任务，后端会把旧任务标记为失败并提示重新点击同步。

### 15.8 服务器状态没有宿主机数据

确认 `docker-compose.yml` 里存在只读挂载：

```yaml
volumes:
  - /proc:/host/proc:ro
```

升级到带服务器状态的新版本后，建议重新创建容器：

```bash
docker compose up -d --build --force-recreate oci-arm-monitor-server
```

如果没有这个挂载，后端会回退读取容器内 `/proc`，页面仍可显示数据，但不一定完整代表宿主机。

### 15.9 前端看不到服务器状态菜单

先确认你访问的是新前端包，而不是旧的 `dist`：

```bash
grep -R "服务器状态\|/server" /var/www/oci-arm-cost-monitor/assets | head
```

- 没有输出：重新构建前端并同步静态文件。
- 有输出但浏览器没有菜单：强制刷新浏览器缓存，或清理 1Panel / Nginx / CDN 缓存。
- 仍然没有：确认 1Panel 网站根目录指向 `/var/www/oci-arm-cost-monitor`，不是旧目录。

重新部署前端：

```bash
cd /opt/oci-arm-cost-monitor/web
pnpm install
pnpm build
sudo rsync -a --delete dist/ /var/www/oci-arm-cost-monitor/
sudo systemctl reload nginx
```

前端现在使用 hash 路由，直接访问 `https://你的域名/#/server` 应该进入服务器状态页。如果这个地址也没有页面，基本就是静态包没有更新。

## 16. 免费资源注意事项

- 本项目不创建 OCI Logging、Alarm、Notifications、托管数据库等额外资源。
- 后端在你手动点击同步或定时任务触发时读取 OCI API，并把结果落本地 SQLite。
- 服务器状态采样只读取本机 `/proc` 和 JVM runtime，不调用 OCI API。
- Compute Instance Monitoring plugin 本身是 Oracle Cloud Agent 的插件；指标采集与查询可能受 Monitoring 服务计量规则影响。
- 部署前建议在 Oracle 官方价格表核对 Monitoring ingestion/retrieval 免费额度和超额价格。
