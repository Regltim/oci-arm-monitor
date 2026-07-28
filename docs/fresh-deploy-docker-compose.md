# 全新服务器部署手册

本文说明如何用 Docker Compose 同时运行 OCI ARM Monitor 的前端和后端，并接入服务器已有的 Nginx、OpenResty 或 1Panel。

## 1. 架构和边界

```text
公网域名 :80/:443
       |
Nginx / OpenResty / 1Panel
       |
http://127.0.0.1:28461
       |
oci-arm-monitor-web :8080
       |
Docker 内网
       |
oci-arm-monitor-server :9090
```

- 容器不管理域名、TLS 或证书。
- Web 端口默认只绑定宿主机 `127.0.0.1:28461`。
- Web 容器提供前端页面，并把 `/api/*` 转发到后端。
- 后端 `9090` 不发布到宿主机。
- SQLite 数据保存在 Docker 命名卷。

## 2. 服务器要求

- Linux ARM64 或 AMD64
- Docker Engine
- Docker Compose v2
- `git`、`curl`、`jq`
- 已有 Nginx、OpenResty 或 1Panel 站点管理能力

确认环境：

```bash
docker --version
docker compose version
git --version
curl --version
jq --version
```

如果应用部署在 OCI Compute 实例上，推荐使用 Instance Principal。其他服务器可使用 OCI API Key。

## 3. 获取代码

```bash
sudo mkdir -p /opt/oci-arm-monitor
sudo chown "$(id -u):$(id -g)" /opt/oci-arm-monitor
git clone https://github.com/Regltim/oci-arm-monitor.git /opt/oci-arm-monitor
cd /opt/oci-arm-monitor
```

## 4. 初始化配置

```bash
bash scripts/init-deploy.sh
```

脚本会收集：

1. 用户最终访问的完整 Origin，例如 `https://monitor.example.com`。
2. 宿主机 Web 端口，默认 `28461`。
3. 管理员账号和密码。
4. OCI 认证模式、Region、Tenancy 和目标 Compartment。
5. API Key 模式所需的 OCI config 路径。
6. 可选的微信公众号双模板、接收人和推送策略。

公开 Origin 只能包含协议、主机名和可选端口，不能带路径、查询参数或末尾斜杠。它用于生成 CORS 和 Cookie 配置，不负责容器 TLS。

脚本生成的关键配置：

```env
COMPOSE_FILE=docker-compose.yml
MONITOR_PUBLIC_URL=https://monitor.example.com
MONITOR_WEB_BIND_ADDRESS=127.0.0.1
MONITOR_WEB_PORT=28461
MONITOR_WECHAT_ENABLED=false
MONITOR_WECHAT_APP_ID=
MONITOR_WECHAT_APP_SECRET=
MONITOR_WECHAT_TEMPLATE_ID=
MONITOR_WECHAT_COST_TEMPLATE_ID=
MONITOR_WECHAT_OPEN_IDS=
MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED=true
MONITOR_WECHAT_DAILY_SUMMARY_ENABLED=false
MONITOR_WECHAT_DAILY_SUMMARY_TIME=09:00
MONITOR_WECHAT_ZONE_ID=Asia/Shanghai
MONITOR_SETTINGS_ENCRYPTION_KEY=
MONITOR_CORS_ALLOWED_ORIGINS=https://monitor.example.com
MONITOR_COOKIE_SECURE=true
```

微信公众号通知可以在初始化脚本中配置，也可以部署后在“系统设置 → 通知设置”中维护。运行状态和费用流量使用两份独立 Template ID，每日摘要开启时会发送两条不带网页跳转的消息。脚本会自动生成 `MONITOR_SETTINGS_ENCRYPTION_KEY`，用于加密页面保存到 SQLite 的公众号凭据。详细步骤见[微信公众号通知配置](wechat-notifications.md)。

重新运行脚本会备份旧 `.env`。已有域名、OCID 和密码不会在提示中明文回显。

## 5. OCI 认证

### Instance Principal

在 OCI Compute 实例上选择：

```text
instance_principal
```

脚本会读取 Instance Metadata，并输出 OCI Cloud Shell 命令。按输出执行 `scripts/oci-cloud-shell-setup.sh`，创建只匹配当前实例的 Dynamic Group 和只读 Policy。

详细步骤见 [Oracle ARM 两步快速部署](quick-deploy.md)。

### API Key

在非 OCI 服务器选择：

```text
config_file
```

然后准备：

```text
deploy/oci/config
deploy/oci/oci_api_key.pem
```

详细步骤见 [OCI 接入说明](oci-setup.md)。

## 6. 启动容器

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

预期：

- `oci-arm-monitor-server` 状态为 healthy。
- `oci-arm-monitor-web` 状态为 running。
- Web 端口显示为 `127.0.0.1:28461->8080/tcp`。
- 后端没有宿主机端口映射。

先在服务器本机验证：

```bash
curl -I http://127.0.0.1:28461/
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:28461/api/auth/me
```

首页应返回成功响应；未登录访问 `/api/auth/me` 返回 `401` 属于正常行为。

## 7. 配置 Nginx / OpenResty

为 `monitor.example.com` 创建站点并配置证书，然后把整个站点代理到：

```text
http://127.0.0.1:28461
```

Nginx / OpenResty 示例：

```nginx
location / {
    proxy_pass http://127.0.0.1:28461;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

不要再单独代理 `/api/` 到 `9090`。Web 容器已经处理前端路由和 API 转发。

### 1Panel

创建网站并启用 HTTPS 后，添加反向代理：

```text
代理地址：http://127.0.0.1:28461
发送域名：$host
```

### 网络规则

- 公网只按现有站点需要放行 `80/443`。
- 不要在 OCI Security List、NSG 或系统防火墙中放行 `28461`。
- `28461` 只供本机 Nginx/OpenResty 使用。

## 8. 验证域名

```bash
curl -I https://monitor.example.com/
curl -sS -o /dev/null -w '%{http_code}\n' https://monitor.example.com/api/auth/me
```

打开域名后应直接进入 OCI ARM Monitor 登录页。登录后进入“系统设置”，运行 OCI 连接诊断，再执行同步。

## 9. 自定义端口

如果 `28461` 已被占用，重新运行初始化脚本并选择另一个 `1024-65535` 的高位端口：

```bash
sudo ss -ltnp 'sport = :28461'
bash scripts/init-deploy.sh
docker compose up -d --build
```

随后同步修改 Nginx/OpenResty 的 `proxy_pass`。不要改成常见服务端口，也不要把绑定地址改为 `0.0.0.0`。

## 10. 更新

```bash
cd /opt/oci-arm-monitor
git pull --ff-only
bash scripts/init-deploy.sh
docker compose up -d --build
docker compose ps
```

镜像重建不会删除 `oci-arm-monitor-data` 中的 SQLite 数据。

## 11. 备份

备份前获取数据卷名称：

```bash
docker inspect oci-arm-cost-monitor-server \
  --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}'
```

完整备份步骤见 [Docker 容器与数据说明](docker-backend.md)。

## 12. 排障

### 容器启动时报端口占用

```bash
sudo ss -ltnp 'sport = :28461'
```

选择另一个高位端口并同时更新反向代理。现有 Nginx/OpenResty 占用 `80/443` 是正常的，不需要停止。

### 域名返回 502

先检查本地目标：

```bash
curl -I http://127.0.0.1:28461/
docker compose ps
docker compose logs --tail=200 oci-arm-monitor-web
docker compose logs --tail=300 oci-arm-monitor-server
```

本地正常而域名仍为 502 时，检查 Nginx/OpenResty 的代理地址和站点配置。

### 登录后立即回到登录页

确认 `MONITOR_PUBLIC_URL` 与浏览器地址的协议、域名和端口完全一致。HTTPS 域名应生成 `MONITOR_COOKIE_SECURE=true`。

### 后端不健康

```bash
docker compose logs --tail=300 oci-arm-monitor-server
```

重点检查管理员密码、OCI Region、Compartment OCID 和认证配置。
