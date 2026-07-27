# 全新服务器部署手册

本手册适合在一台全新的 Linux 服务器上部署 OCI ARM Monitor。默认方案通过 Docker Compose 同时运行前端和后端，不需要在宿主机安装 Nginx、Node.js、pnpm、Java 或 Certbot。

如果应用直接部署在 Oracle Cloud Compute 实例上，推荐先看更短的 [Oracle ARM 两步快速部署](quick-deploy.md)。

## 1. 架构

```text
浏览器
  |
  | HTTP :8080 或 HTTPS :443
  v
oci-arm-monitor-web
  |-- Caddy
  |-- Umi 静态页面
  |-- /api/* 反向代理
  |
  | Docker 内网 :9090
  v
oci-arm-monitor-server
  |-- Spring Boot
  |-- SQLite
  |-- OCI SDK
```

默认安全边界：

- HTTP 和 HTTPS 入口互斥，不会同时开放。
- 后端 `9090` 不发布到宿主机。
- SQLite、Caddy 状态和 HTTPS 证书使用 Docker volume 持久化。
- OCI 私密配置仅保存在服务器 `.env` 和 `deploy/oci/`。

## 2. 服务器要求

- Linux，支持 `linux/arm64` 或 `linux/amd64` 容器。
- Docker Engine 和 Docker Compose plugin。
- `curl`、`jq` 和 `git`。
- 至少 2 GB 可用内存用于首次镜像构建。

Ubuntu / Debian 安装基础工具：

```bash
sudo apt update
sudo apt install -y ca-certificates curl jq git
```

Oracle Linux 安装基础工具：

```bash
sudo dnf install -y ca-certificates curl jq git
```

Docker 请按 [Docker Engine 官方安装文档](https://docs.docker.com/engine/install/) 安装。完成后确认：

```bash
docker --version
docker compose version
```

如果当前用户通过 Docker 用户组运行容器，加入用户组后需要重新登录：

```bash
sudo usermod -aG docker "$USER"
```

## 3. 获取项目

```bash
sudo mkdir -p /opt/oci-arm-monitor
sudo chown -R "$USER":"$USER" /opt/oci-arm-monitor
git clone <repository-url> /opt/oci-arm-monitor
cd /opt/oci-arm-monitor
```

确认至少存在：

```text
docker-compose.yml
docker-compose.http.yml
docker-compose.https.yml
server/Dockerfile
web/Dockerfile
scripts/init-deploy.sh
```

## 4. 选择访问模式

### HTTP 模式

适合首次验证、内网或已有上层访问控制的环境。

- 访问地址：`http://<server-ip>:<port>`。
- 默认端口：TCP `8080`。
- 可以在初始化时修改端口。
- 不需要域名和证书。

### HTTPS 模式

适合公网长期运行。

- 访问地址：`https://monitor.example.com`。
- 域名 A/AAAA 记录必须指向服务器。
- OCI Security List / NSG 和系统防火墙必须放行 TCP `80/443`。
- TCP `80/443` 不能被其他服务占用。
- Caddy 自动申请和续期证书。

项目不会自动修改 DNS、OCI 网络规则或操作系统防火墙。

## 5. 初始化配置

在项目根目录运行：

```bash
bash scripts/init-deploy.sh
```

脚本会依次收集：

1. HTTP 或 HTTPS 访问模式。
2. 管理员账号和密码。
3. OCI 认证模式。
4. Region、Tenancy 和目标 Compartment。
5. OCI config 路径和运行参数。

生成的 `.env` 包含匹配当前访问模式的 Compose 文件、Origin 和 Cookie 设置。重新运行脚本会备份旧 `.env`，已有私密值在提示中显示为“已设置，回车保留”。

### 5.1 Instance Principal

应用运行在 OCI Compute 实例时选择：

```text
instance_principal
```

脚本会尝试从 Instance Metadata 自动读取当前 Instance、Tenancy、Compartment 和 Region，并输出一条 Cloud Shell IAM 配置命令。按 [Oracle ARM 两步快速部署](quick-deploy.md) 完成 Dynamic Group 和 Policy。

### 5.2 API Key / config_file

应用不在 OCI Compute 实例时选择：

```text
config_file
```

准备目录：

```bash
mkdir -p deploy/oci
```

容器内 OCI config 必须使用这个私钥路径：

```ini
[DEFAULT]
user=ocid1.user.oc1..replace-with-your-user-ocid
fingerprint=replace-with-your-api-key-fingerprint
tenancy=ocid1.tenancy.oc1..replace-with-your-tenancy-ocid
region=ap-seoul-1
key_file=/home/monitor/.oci/oci_api_key.pem
```

文件位置：

```text
deploy/oci/config
deploy/oci/oci_api_key.pem
```

设置权限：

```bash
sudo chown -R 10001:10001 deploy/oci
sudo chmod 700 deploy/oci
sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem
```

完整 API Key 与 IAM 设置见 [OCI 接入说明](oci-setup.md)。

## 6. 启动

验证配置并启动完整应用：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

首次构建会下载基础镜像和前后端依赖，所需时间取决于网络速度。

查看全部日志：

```bash
docker compose logs -f
```

分别查看：

```bash
docker compose logs --tail=200 oci-arm-monitor-web
docker compose logs --tail=200 oci-arm-monitor-server
```

初始化脚本最后会打印访问地址。打开该地址并使用初始化时设置的管理员账号和密码登录。

## 7. 首次诊断与同步

登录后进入：

```text
系统设置 -> OCI 配置 -> 运行 OCI 连接诊断
```

诊断会读取少量数据验证 OCI Provider、Compute、VNIC、Monitoring 和 Usage API 权限，不会写入数据库。

诊断通过后点击“同步 OCI 数据”。同步中心会展示当前步骤、结果和历史记录。

## 8. 验证部署

HTTP 示例：

```bash
curl -I http://127.0.0.1:8080/
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/auth/me
```

HTTPS 示例：

```bash
curl -I https://monitor.example.com/
curl -sS -o /dev/null -w '%{http_code}\n' https://monitor.example.com/api/auth/me
```

首页应返回成功响应，未登录访问 `/api/auth/me` 应返回 `401`。如果 HTTP 使用了自定义端口，请替换命令中的 `8080`。

确认后端没有发布宿主机端口：

```bash
docker compose port oci-arm-monitor-server 9090
```

默认配置下该命令不应返回宿主机端口。

## 9. 切换 HTTP / HTTPS

重新运行初始化脚本并选择另一种模式：

```bash
bash scripts/init-deploy.sh
docker compose up -d --build
docker compose ps
```

脚本会更新 `.env` 中的 `COMPOSE_FILE`、站点地址、Origin 和 Cookie 设置。切换到 HTTPS 前必须先完成 DNS 和 TCP `80/443` 配置。

## 10. 更新

```bash
cd /opt/oci-arm-monitor
git status --short
git pull --ff-only
docker compose up -d --build
docker compose ps
```

镜像重建不会删除命名卷中的 SQLite、Caddy 状态或证书。

## 11. 数据备份

先获取实际数据卷名称：

```bash
docker inspect oci-arm-cost-monitor-server \
  --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}'
```

为保证 SQLite 文件一致，备份时短暂停止后端：

```bash
mkdir -p backups
docker compose stop oci-arm-monitor-server
```

把 `<data-volume-name>` 替换成上一条命令输出：

```bash
docker run --rm \
  -v <data-volume-name>:/data:ro \
  -v "$PWD/backups:/backups" \
  busybox \
  sh -c 'cp /data/oci-arm-cost-monitor.db /backups/oci-arm-cost-monitor-$(date +%Y%m%d%H%M%S).db'
```

重新启动：

```bash
docker compose start oci-arm-monitor-server
```

## 12. 常见问题

### 12.1 镜像构建失败

```bash
docker compose build --no-cache oci-arm-monitor-web
docker compose build --no-cache oci-arm-monitor-server
```

检查服务器 DNS、磁盘空间、内存以及访问 Docker Hub 和 npm/Maven 仓库的网络。

### 12.2 页面无法访问

```bash
docker compose ps
docker compose logs --tail=200 oci-arm-monitor-web
```

HTTP 检查所选端口；HTTPS 检查 DNS、TCP `80/443`、系统时间和端口占用。

### 12.3 HTTPS 证书申请失败

```bash
docker compose logs --tail=300 oci-arm-monitor-web
```

常见原因是域名尚未解析到服务器、AAAA 记录指向错误地址、`80/443` 未放行或端口被其他 Web 服务占用。

### 12.4 页面返回 502

```bash
docker compose ps
docker compose logs --tail=300 oci-arm-monitor-server
```

Web 容器通过 Docker 网络访问后端。重点检查管理员配置、OCI 必填变量和后端健康状态。

### 12.5 OCI 同步返回 401 / 403

- Instance Principal：检查 Dynamic Group matching rule 和 Policy scope。
- API Key：检查 user、fingerprint、tenancy、私钥配对和文件权限。
- 费用同步：确认存在 `read usage-report in tenancy`。
- 新 IAM Policy 可能需要几分钟生效。

### 12.6 CPU、内存或流量为空

在 OCI Console 确认目标实例的 Oracle Cloud Agent 中 `Compute Instance Monitoring` plugin 为 Enabled / Running。

### 12.7 修改 `.env` 密码后仍是旧密码

管理员账号只在 SQLite 中没有管理员用户时初始化。已有数据库不会因为修改 `.env` 自动重置密码。

### 12.8 端口已被占用

HTTP 自定义端口或 HTTPS `80/443` 被占用时，Compose 会启动失败。先查占用服务，再选择其他 HTTP 端口或停止冲突的 Web 服务。

## 13. 高级兼容：已有宿主机 Nginx

默认部署不需要本节。仅当服务器已经有统一 Nginx、面板必须接入既有站点时使用。

创建一个仅本机发布后端端口的覆盖文件：

```yaml
# docker-compose.backend-local.yml
services:
  oci-arm-monitor-server:
    ports:
      - "127.0.0.1:9090:9090"
```

只启动后端：

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.backend-local.yml \
  up -d --build oci-arm-monitor-server
```

已有 Nginx 站点必须把 `/api/` 原样代理到 `http://127.0.0.1:9090`，并为前端路由配置 `index.html` fallback：

```nginx
location /api/ {
  proxy_pass http://127.0.0.1:9090;
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
}

location / {
  try_files $uri $uri/ /index.html;
}
```

此模式下需要自行提供 `web/dist` 静态文件和 HTTPS，并确保 `.env` 中的 Origin、Cookie 与外部访问地址一致。新部署优先使用默认 Caddy 容器方案。
