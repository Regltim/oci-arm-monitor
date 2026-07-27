# OCI 真实数据接入说明

这份说明用于把面板接到真实 OCI 数据。当前实现不依赖付费数据库、Logging、Alarm、Notifications 或 Cost Reports 对象存储 CSV；后端直接通过 OCI Java SDK 拉取 Compute、VNIC、Monitoring 和 Usage API，并落到本机 SQLite。

如果后端部署在 Oracle Cloud 实例本机，优先使用快速版：[quick-deploy.md](quick-deploy.md)。快速版使用 Instance Principal，不需要 API Key 和服务器私钥。

如果你需要把后端部署在非 Oracle 服务器上，再按本文的 API Key / OCI config 模式配置。对 Oracle 控制台、用户组、Policy 不熟时，建议先看通俗版操作流程：[oracle-console-simple-guide.md](oracle-console-simple-guide.md)。

官方参考：

- [OCI API Signing Keys](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm)
- [Calling Services from an Instance](https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/callingservicesfrominstances.htm)
- [SDK and CLI Configuration File](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/sdkconfig.htm)
- [Compute Instance Monitoring Plugin](https://docs.oracle.com/en-us/iaas/Content/Compute/Tasks/enablingmonitoring.htm)
- [Common IAM Policies](https://docs.oracle.com/iaas/Content/Identity/Concepts/commonpolicies.htm)
- [OCI Price List](https://www.oracle.com/cloud/price-list/)

## 1. 创建 OCI API Key

在运行后端的服务器上执行：

```bash
mkdir -p ~/.oci
openssl genrsa -out ~/.oci/oci_api_key.pem 2048
chmod 600 ~/.oci/oci_api_key.pem
openssl rsa -pubout -in ~/.oci/oci_api_key.pem -out ~/.oci/oci_api_key_public.pem
```

登录 OCI Console：

1. 进入用户详情页。
2. 打开 API Keys。
3. 上传 `~/.oci/oci_api_key_public.pem` 的内容。
4. 记录 fingerprint。

## 2. 创建 `~/.oci/config`

在服务器上创建：

```ini
[DEFAULT]
user=ocid1.user.oc1..替换为用户OCID
fingerprint=替换为fingerprint
tenancy=ocid1.tenancy.oc1..替换为租户OCID
region=ap-seoul-1
key_file=/home/monitor/.oci/oci_api_key.pem
```

权限：

```bash
chmod 600 ~/.oci/config
chmod 600 ~/.oci/oci_api_key.pem
```

如果后端以 `monitor` 用户运行，路径应是 `/home/monitor/.oci/config`，不要使用 root 用户的 config。

## 3. 配置 IAM 只读策略

更通俗的逐步操作说明见 [oracle-console-simple-guide.md](oracle-console-simple-guide.md)。

创建一个用户组，例如 `oci-monitor-readers`，把 API Key 所属用户加入该组。

在 tenancy 或目标 compartment 上配置策略。下面是当前功能需要的只读起点：

```text
Allow group oci-monitor-readers to read instance-family in compartment <compartment-name>
Allow group oci-monitor-readers to read virtual-network-family in compartment <compartment-name>
Allow group oci-monitor-readers to read metrics in compartment <compartment-name>
Allow group oci-monitor-readers to read usage-report in tenancy
```

如果你的实例、VNIC 或指标跨多个 compartment，改成 tenancy 范围或分别给多个 compartment 授权。

Usage API 读取成本需要 tenancy 范围的 `read usage-report in tenancy`。如果同步成本时返回 403，优先检查该策略和用户组是否在正确身份域下生效。

## 4. 启用 Compute Instance Monitoring Plugin

每台需要采集 CPU、内存和实例级指标的 Compute 实例都要确认 Oracle Cloud Agent 里的 Compute Instance Monitoring plugin 已启用。

路径通常是：

```text
Compute -> Instances -> 选择实例 -> Oracle Cloud Agent -> Compute Instance Monitoring
```

注意：

- 插件本身不是本项目创建的付费资源。
- Monitoring 服务按 ingestion/retrieval 数据点计量。
- Oracle 当前价格表显示 Monitoring ingestion 前 500 million datapoints、retrieval 前 1 billion datapoints 为 Free；超过后可能收费。
- 本项目只做手动同步，并把结果落本地 SQLite，避免页面频繁请求 Monitoring API。

## 5. 用 Docker 启动后端

后端推荐用 Docker 跑服务，详细说明见 [docker-backend.md](docker-backend.md)。

准备环境变量：

```bash
cp .env.example .env
```

准备 OCI config 目录：

```bash
mkdir -p deploy/oci
cp ~/.oci/config deploy/oci/config
cp ~/.oci/oci_api_key.pem deploy/oci/oci_api_key.pem
```

注意把 `deploy/oci/config` 里的 `key_file` 改成容器内路径：

```ini
key_file=/home/monitor/.oci/oci_api_key.pem
```

容器默认使用 UID/GID `10001` 读取 OCI config：

```bash
sudo chown -R 10001:10001 deploy/oci
sudo chmod 700 deploy/oci
sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem
```

启动：

```bash
docker compose up -d --build oci-arm-monitor-server
docker compose logs -f oci-arm-monitor-server
```

首次启动必须设置管理员账号，可在 `.env` 中配置：

```bash
MONITOR_ADMIN_USERNAME=admin
MONITOR_ADMIN_PASSWORD=替换为强密码
```

生产环境走 HTTPS 时建议设置：

```bash
MONITOR_COOKIE_SECURE=true
```

如果忘记设置管理员账号，后端不会自动创建默认账号。

## 5.1 本地 Maven 启动

首次启动必须设置管理员账号：

```bash
cd server
MONITOR_ADMIN_USERNAME=admin \
MONITOR_ADMIN_PASSWORD='替换为强密码' \
OCI_MONITOR_DB=/home/monitor/oci-arm-cost-monitor.db \
OCI_AUTH_MODE=config_file \
OCI_CONFIG_FILE_PATH=/home/monitor/.oci/config \
OCI_CONFIG_PROFILE=DEFAULT \
OCI_REGION=ap-seoul-1 \
OCI_COMPARTMENT_OCID='替换为目标CompartmentOCID' \
mvn spring-boot:run
```

生产环境走 HTTPS 时建议加：

```bash
MONITOR_COOKIE_SECURE=true
```

如果忘记设置管理员账号，后端不会自动创建默认账号。

## 6. 后端配置并同步

Oracle/OCI 配置不放在前端页面里。请在后端 `.env` 或启动环境变量中配置：

```env
OCI_AUTH_MODE=config_file
OCI_CONFIG_FILE_PATH=/home/monitor/.oci/config
OCI_CONFIG_PROFILE=DEFAULT
OCI_REGION=ap-seoul-1
OCI_COMPARTMENT_OCID=替换为目标CompartmentOCID
OCI_TENANCY_OCID=
```

登录面板后进入系统设置，确认 OCI 配置状态为已配置，然后点击同步 OCI 数据。

同步结果会写入 `sync_run` 表，并在页面展示：

- 未配置
- 尚未同步
- 最近一次同步失败
- 最近一次同步成功及同步数量

## 7. Nginx 反向代理示例

前端静态文件部署到 `/var/www/oci-arm-cost-monitor`，后端监听 `127.0.0.1:9090`：

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

公网部署建议配 HTTPS，并设置 `MONITOR_COOKIE_SECURE=true`。

## 8. 旧本地库处理

早期开发阶段如果生成过本地样例数据，当前后端启动会清理固定 ID 为 `demo-arm-a1-01` 的旧记录。

如果你希望从零开始：

```bash
cd server
mv oci-arm-cost-monitor.db oci-arm-cost-monitor.db.bak
```

然后重新启动后端。

## 9. 已知边界

- 费用当前来自 Usage API，不读取对象存储里的 Cost Reports CSV。
- Boot Volume 容量当前字段已预留，但同步阶段还没有补充 Block Volume API。
- Monitoring 指标维度依赖 OCI 返回的 `resourceId`，如果你的区域或实例指标维度不同，需要根据同步失败信息调整查询。
- 没有真实 OCI config、后端环境变量和 IAM 权限时，只能验证登录、配置状态、空态和本地手工费用，不能证明云端数据一定能同步成功。
