# OCI ARM Monitor

OCI ARM Monitor 是一个可自托管的 Oracle Cloud Infrastructure 资源监控面板，用于查看 Compute 实例、运行状态、流量、免费额度和费用数据。

## 功能

- OCI Compute 实例和网络信息同步
- CPU、内存、磁盘、运行时间和流量监控
- 免费额度与费用汇总
- OCI 连接诊断和定时同步
- 微信公众号告警、恢复、运行状态和费用流量摘要通知
- Instance Principal 和 API Key 两种认证方式
- 前端、后端和 SQLite 一体化 Docker Compose 部署

## 部署架构

```text
浏览器 https://monitor.example.com
              |
      Nginx / OpenResty / 1Panel
              |
       http://127.0.0.1:28461
              |
       oci-arm-monitor-web
       |-- 前端静态页面
       |-- /api/* 反向代理
              |
  oci-arm-monitor-server:9090
```

容器只在宿主机回环地址暴露一个 Web 端口。域名、证书、TLS 和公网入口由服务器已有的 Nginx、OpenResty 或 1Panel 管理；容器不会申请、保存或续期证书。后端 `9090` 只在 Docker 网络内可用。

## 快速部署

服务器需要 Docker、Docker Compose、`git`、`curl` 和 `jq`。

```bash
git clone https://github.com/Regltim/oci-arm-monitor.git
cd oci-arm-monitor
bash scripts/init-deploy.sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

初始化时填写用户最终访问的完整 Origin，例如：

```text
https://monitor.example.com
```

默认 Web 端口为不常见的 `28461`，并固定绑定 `127.0.0.1`。脚本结束后会打印：

```text
公开访问地址：https://monitor.example.com
Nginx/OpenResty 反向代理目标：http://127.0.0.1:28461
```

## 配置反向代理

Nginx / OpenResty 站点只需把整个域名代理到 Web 容器：

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

1Panel 中创建“反向代理”网站时，代理地址填写 `http://127.0.0.1:28461`。不要把 `28461` 对公网放行，也不要把代理目标写成后端 `9090`。

## OCI 认证

应用部署在 OCI Compute 实例时，推荐使用 Instance Principal，不需要在服务器保存 OCI API 私钥。初始化脚本会读取 Instance Metadata，并输出可在 OCI Cloud Shell 执行的 Dynamic Group 和 Policy 配置命令。

部署在其他云或本地服务器时，可使用 `config_file` 模式挂载 OCI API Key。

- [Oracle ARM 两步快速部署](docs/quick-deploy.md)
- [OCI 接入说明](docs/oci-setup.md)
- [Oracle 控制台通俗配置流程](docs/oracle-console-simple-guide.md)
- [完整 Docker Compose 部署](docs/fresh-deploy-docker-compose.md)
- [Docker 容器与数据说明](docs/docker-backend.md)
- [微信公众号通知配置](docs/wechat-notifications.md)

## 常用命令

```bash
docker compose ps
docker compose logs -f
docker compose up -d --build
docker compose down
```

更新：

```bash
git pull --ff-only
bash scripts/init-deploy.sh
docker compose up -d --build
```

SQLite 数据保存在 Docker 命名卷 `oci-arm-monitor-data`。不要在未备份时执行 `docker compose down --volumes`。

## 开源安全

- `.env`、`deploy/oci/`、私钥、SQLite 数据和构建产物均由 Git 忽略。
- 公开仓库只保留示例域名、示例 OCID 和占位凭据。
- 发布前可运行 `bash scripts/check-public-release.sh` 检查敏感内容。
