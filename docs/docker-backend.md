# Docker 容器与数据说明

`docker compose up -d --build` 会同时启动 Web 和后端容器。服务器已有的 Nginx、OpenResty 或 1Panel 负责公网域名和 TLS。

## 1. 服务组成

| 服务 | 作用 | 宿主机端口 |
| --- | --- | --- |
| `oci-arm-monitor-web` | 前端静态页面、SPA fallback、`/api/*` 代理 | 默认 `127.0.0.1:28461` |
| `oci-arm-monitor-server` | Spring Boot、SQLite、OCI 同步、定时任务 | 不发布，Docker 内网 `9090` |

Web 镜像中的 Caddy 只监听容器内纯 HTTP `8080`，不申请、不保存、不续期证书。后端健康后 Web 服务才会启动。

## 2. 配置文件

```text
docker-compose.yml          前后端服务、端口、健康检查和数据卷
.env                        当前服务器私密配置，不进入 Git
.env.example                公共占位示例
server/Dockerfile           后端镜像
web/Dockerfile              前端构建和 Web 运行镜像
web/Caddyfile               静态页面、SPA fallback 和 API 代理
```

推荐通过脚本生成 `.env`：

```bash
bash scripts/init-deploy.sh
```

与入口相关的配置：

```env
COMPOSE_FILE=docker-compose.yml
MONITOR_PUBLIC_URL=https://monitor.example.com
MONITOR_WEB_BIND_ADDRESS=127.0.0.1
MONITOR_WEB_PORT=28461
MONITOR_CORS_ALLOWED_ORIGINS=https://monitor.example.com
MONITOR_COOKIE_SECURE=true
```

- `MONITOR_PUBLIC_URL` 是浏览器实际使用的 Origin。
- `MONITOR_WEB_BIND_ADDRESS` 默认固定为回环地址。
- `MONITOR_WEB_PORT` 是 Nginx/OpenResty 的本机代理目标端口。
- HTTPS Origin 自动生成 Secure Cookie 配置，但 TLS 仍由外部反向代理处理。
- 开启微信免登录明细时，`MONITOR_PUBLIC_URL` 也用于生成 H5 Hash 路由；容器不会新增公网端口。

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

HTTP 响应带 `X-Request-Id`，可在后端日志中定位请求：

```bash
docker compose logs --tail=300 oci-arm-monitor-server | grep '<X-Request-Id>'
```

服务端不会记录请求体、Cookie、密码、Token 或 OCI 私钥内容。微信明细令牌位于 URL Fragment，读取快照时通过 `Authorization` 传递，不会进入请求 URI 日志。

## 4. 端口和网络

查看 Web 端口：

```bash
docker compose port oci-arm-monitor-web 8080
```

默认输出应指向：

```text
127.0.0.1:28461
```

后端没有宿主机端口：

```bash
docker compose port oci-arm-monitor-server 9090
```

默认不应输出端口。不要将 Web 改绑 `0.0.0.0`，也不要把后端 `9090` 对公网发布。

容器内请求路径：

```text
/api/* -> http://oci-arm-monitor-server:9090
```

因此外部 Nginx/OpenResty 只需要把整个站点代理到 `http://127.0.0.1:28461`。

## 5. 数据卷

Compose 只定义一个命名卷：

| 卷 | 内容 |
| --- | --- |
| `oci-arm-monitor-data` | SQLite 数据库 |

`docker compose down` 会删除容器但保留数据卷。不要在有数据的环境执行：

```bash
docker compose down --volumes
```

除非已经完成备份并明确要清空数据。

## 6. OCI 配置挂载

### Instance Principal

不需要 `deploy/oci/config` 和 API 私钥。后端通过 OCI Instance Metadata 和 Dynamic Group 身份访问 API。

### config_file

宿主机目录默认只读挂载到 `/home/monitor/.oci`：

```text
deploy/oci/config
deploy/oci/oci_api_key.pem
```

`config` 中的 `key_file` 必须使用容器路径：

```ini
key_file=/home/monitor/.oci/oci_api_key.pem
```

后端容器使用 UID/GID `10001`：

```bash
sudo chown -R 10001:10001 deploy/oci
sudo chmod 700 deploy/oci
sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem
```

## 7. 服务器状态采集

后端只读挂载宿主机 `/proc`：

```yaml
- /proc:/host/proc:ro
```

它用于读取宿主机 CPU、内存、网络和运行时间。SQLite 所在 `/data` 用于磁盘容量统计。

## 8. 仅启动后端

仅用于排障：

```bash
docker compose up -d --build oci-arm-monitor-server
docker compose logs -f oci-arm-monitor-server
```

如果确实需要从宿主机临时访问后端，可创建本地覆盖文件：

```yaml
# docker-compose.backend-local.yml
services:
  oci-arm-monitor-server:
    ports:
      - "127.0.0.1:39091:9090"
```

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.backend-local.yml \
  up -d --build oci-arm-monitor-server
```

排障结束后移除覆盖文件，不要把后端端口加入公网防火墙规则。

## 9. 更新镜像

```bash
git pull --ff-only
bash scripts/init-deploy.sh
docker compose up -d --build
docker compose ps
```

强制重新创建但保留数据卷：

```bash
docker compose up -d --build --force-recreate
```

## 10. 备份 SQLite

获取实际卷名：

```bash
docker inspect oci-arm-cost-monitor-server \
  --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}'
```

为保证文件一致，先停止后端，再复制数据库：

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
