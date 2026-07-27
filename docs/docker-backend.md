# Docker 容器与数据说明

默认 `docker compose up -d --build` 会同时启动前端和后端。服务器不需要单独安装 Java、Node.js 或 Web 代理。

## 1. 服务组成

| 服务 | 作用 | 宿主机端口 |
| --- | --- | --- |
| `oci-arm-monitor-web` | Caddy、前端静态页面、`/api/*` 反向代理、自动 HTTPS | HTTP 模式为所选端口；HTTPS 模式为 `80/443` |
| `oci-arm-monitor-server` | Spring Boot、SQLite、OCI 同步、定时任务 | 默认不发布，Docker 内网 `9090` |

后端健康后 Web 服务才会启动。两个容器均使用 `restart: unless-stopped`，日志默认单文件 20 MB、保留 5 个文件。

## 2. 配置文件

```text
docker-compose.yml          两个服务、网络、健康检查和数据卷
docker-compose.http.yml     HTTP 入口端口
docker-compose.https.yml    HTTPS 入口端口 80/443
.env                        当前服务器私密配置，不进入 Git
.env.example                公共占位示例
server/Dockerfile           后端镜像
web/Dockerfile              前端构建和 Caddy 运行镜像
web/Caddyfile               静态页面和 API 代理规则
```

推荐通过脚本生成 `.env`：

```bash
bash scripts/init-deploy.sh
```

初始化脚本负责让这些配置保持一致：

- `COMPOSE_FILE`
- `MONITOR_ACCESS_MODE`
- `MONITOR_SITE_ADDRESS`
- `MONITOR_CORS_ALLOWED_ORIGINS`
- `MONITOR_COOKIE_SECURE`

不要分别手工修改这些值，否则可能导致入口端口、Origin 或 Cookie 冲突。

## 3. 启动与日志

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

查看日志：

```bash
docker compose logs -f
docker compose logs --tail=200 oci-arm-monitor-web
docker compose logs --tail=300 oci-arm-monitor-server
```

同步任务通过后端日志输出当前步骤。HTTP 响应带 `X-Request-Id`，可在日志中按该值定位请求：

```bash
docker compose logs --tail=300 oci-arm-monitor-server | grep '<X-Request-Id>'
```

服务端不会记录请求体、Cookie、密码、Token 或 OCI 私钥内容。

## 4. 数据卷

Compose 定义三个命名卷：

| 卷 | 内容 |
| --- | --- |
| `oci-arm-monitor-data` | SQLite 数据库 |
| `oci-arm-monitor-caddy-data` | 证书和 Caddy 运行状态 |
| `oci-arm-monitor-caddy-config` | Caddy 持久配置状态 |

重新构建或替换容器不会删除命名卷。以下命令会删除容器但保留卷：

```bash
docker compose down
```

不要在有数据的环境执行 `docker compose down --volumes`，除非已经完成备份并明确要清空数据。

## 5. OCI 配置挂载

### Instance Principal

不需要 `deploy/oci/config` 和 API 私钥。后端通过 OCI Instance Metadata 和 Dynamic Group 身份访问 API。

### config_file

宿主机目录默认只读挂载到 `/home/monitor/.oci`：

```text
deploy/oci/config
deploy/oci/oci_api_key.pem
```

`config` 中的 `key_file` 必须是容器路径：

```ini
key_file=/home/monitor/.oci/oci_api_key.pem
```

后端容器使用 UID/GID `10001`：

```bash
sudo chown -R 10001:10001 deploy/oci
sudo chmod 700 deploy/oci
sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem
```

## 6. 服务器状态采集

后端只读挂载宿主机 `/proc`：

```yaml
- /proc:/host/proc:ro
```

用于读取宿主机 CPU、内存、网络和运行时间。SQLite 所在 `/data` 用于磁盘容量统计。

## 7. 仅启动后端

仅用于排障或接入已有外部 Web 代理：

```bash
docker compose up -d --build oci-arm-monitor-server
docker compose logs -f oci-arm-monitor-server
```

默认情况下后端 `9090` 仍然只在 Docker 网络内可用。如果需要从宿主机访问，创建本地覆盖文件：

```yaml
# docker-compose.backend-local.yml
services:
  oci-arm-monitor-server:
    ports:
      - "127.0.0.1:9090:9090"
```

然后显式启动：

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.backend-local.yml \
  up -d --build oci-arm-monitor-server
```

不要把 `9090` 绑定到 `0.0.0.0`，否则可能绕过 Web 入口直接暴露 API。

## 8. 更新镜像

```bash
git pull --ff-only
docker compose up -d --build
docker compose ps
```

强制重新创建但保留数据卷：

```bash
docker compose up -d --build --force-recreate
```

## 9. 备份 SQLite

获取实际卷名：

```bash
docker inspect oci-arm-cost-monitor-server \
  --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}'
```

为保证文件一致，先停止后端，再用只读方式复制数据库：

```bash
mkdir -p backups
docker compose stop oci-arm-monitor-server

docker run --rm \
  -v <data-volume-name>:/data:ro \
  -v "$PWD/backups:/backups" \
  busybox \
  sh -c 'cp /data/oci-arm-cost-monitor.db /backups/oci-arm-cost-monitor-$(date +%Y%m%d%H%M%S).db'

docker compose start oci-arm-monitor-server
```

把 `<data-volume-name>` 替换成第一条命令输出。

## 10. 常用检查

查看端口：

```bash
docker compose port oci-arm-monitor-web 8080
docker compose port oci-arm-monitor-web 443
docker compose port oci-arm-monitor-server 9090
```

HTTP 模式只有 Web 的 HTTP 端口有结果；HTTPS 模式只有 Web 的 `80/443` 有结果；后端 `9090` 默认无结果。

查看数据卷：

```bash
docker volume ls | grep oci-arm-monitor
```

查看健康状态：

```bash
docker inspect oci-arm-cost-monitor-server \
  --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}'
```
