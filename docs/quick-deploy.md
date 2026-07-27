# Oracle ARM 两步快速部署

本指南适合把 OCI ARM Monitor 部署在 Oracle Cloud Compute 实例本机。推荐使用 Instance Principal，服务器无需保存 OCI API 私钥。

部署分为两部分：

```text
ARM 实例终端                         OCI Cloud Shell
运行初始化脚本                       创建 Dynamic Group 和只读 Policy
启动 Docker Compose                  不接触应用数据库和管理员密码
```

## 1. 准备服务器

需要：

- Linux ARM 实例。
- Docker 和 Docker Compose。
- `curl`、`jq` 和 `git`。
- HTTP 模式放行所选 TCP 端口，默认 `8080`；或 HTTPS 模式放行 TCP `80/443`。

Ubuntu / Debian 安装基础工具：

```bash
sudo apt update
sudo apt install -y curl jq git
```

Oracle Linux 安装基础工具：

```bash
sudo dnf install -y curl jq git
```

确认 Docker 可用：

```bash
docker --version
docker compose version
```

## 2. 获取项目

```bash
git clone <repository-url> oci-arm-monitor
cd oci-arm-monitor
```

已有部署目录更新时先确认本地没有未处理的修改：

```bash
git status --short
git pull --ff-only
```

服务器本机的 `.env`、`deploy/oci/` 和 Docker volume 不应上传到代码仓库。

## 3. 初始化应用

```bash
bash scripts/init-deploy.sh
```

访问模式二选一：

### HTTP

适合首次验证、受信网络或已有上层网络保护的环境。

```text
访问模式：http
服务器公网 IPv4 或主机名：203.0.113.10
HTTP 访问端口：8080
```

启动后访问：

```text
http://203.0.113.10:8080
```

### HTTPS

适合公网长期运行。输入不带协议、端口和路径的域名：

```text
访问模式：https
HTTPS 访问域名：monitor.example.com
```

开始部署前确认：

- 域名 A/AAAA 记录指向当前服务器。
- OCI Security List 或 NSG 放行 TCP `80` 和 `443`。
- 操作系统防火墙放行 TCP `80` 和 `443`。
- 服务器上的 `80/443` 没有被其他服务占用。

Caddy 会自动申请和续期证书。启动后访问：

```text
https://monitor.example.com
```

## 4. 确认 OCI 配置

认证模式保留推荐值：

```text
instance_principal
```

脚本会从 OCI Instance Metadata 自动读取：

- 当前服务器的 Instance OCID。
- Tenancy OCID。
- 当前实例所在 Compartment OCID。
- OCI canonical region。

如果被监控资源不在当前实例的 Compartment，在提示时输入目标 Compartment OCID。已有私密值在重新初始化时只显示“已设置，回车保留”。

脚本会生成本机 `.env`，并输出一条 OCI Cloud Shell 命令。Metadata 读取失败时会回退到手工输入。

## 5. 在 Cloud Shell 创建 IAM

打开 OCI Console 顶部的 Cloud Shell。首次使用时先获取同一版本的项目脚本：

```bash
git clone <repository-url> oci-arm-monitor
cd oci-arm-monitor
```

粘贴 ARM 实例初始化脚本输出的命令。格式如下，所有值均为占位符：

```bash
bash scripts/oci-cloud-shell-setup.sh \
  --tenancy-id 'ocid1.tenancy.oc1..replace-with-your-tenancy-ocid' \
  --instance-id 'ocid1.instance.oc1.region.replace-with-your-instance-ocid' \
  --resource-compartment-id 'ocid1.compartment.oc1..replace-with-your-compartment-ocid'
```

脚本会创建或更新：

- Dynamic Group：`oci-arm-monitor-instances`。
- IAM Policy：`oci-arm-monitor-readonly`。
- Compute、VNIC、Monitoring 和 Usage API 的只读权限。

如果被监控资源位于根 Compartment，`--resource-compartment-id` 直接填写与 `--tenancy-id` 相同的 Tenancy OCID。脚本会自动生成 `in tenancy` 策略，不会错误地把 Tenancy OCID 当作普通 Compartment OCID。

只预览命令而不调用 OCI API：

```bash
bash scripts/oci-cloud-shell-setup.sh \
  --tenancy-id 'ocid1.tenancy.oc1..replace-with-your-tenancy-ocid' \
  --instance-id 'ocid1.instance.oc1.region.replace-with-your-instance-ocid' \
  --resource-compartment-id 'ocid1.compartment.oc1..replace-with-your-compartment-ocid' \
  --dry-run
```

Cloud Shell 当前用户必须有管理 Dynamic Group 和 Policy 的权限。IAM Policy 可能需要几分钟生效。

## 6. 启动完整应用

回到 ARM 实例项目目录：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

查看日志：

```bash
docker compose logs -f
```

`oci-arm-monitor-web` 提供前端并转发 `/api/*`，`oci-arm-monitor-server` 只在 Docker 网络内监听 `9090`。宿主机不需要配置 Nginx。

## 7. 登录、诊断和同步

打开初始化脚本打印的地址，使用初始化时设置的管理员账号和密码登录。

进入：

```text
系统设置 -> OCI 配置 -> 运行 OCI 连接诊断
```

诊断依次检查：

- 后端 OCI 基础配置。
- Instance Principal Provider。
- Compute 实例读取权限。
- VNIC 网络读取权限。
- Monitoring 指标读取权限。
- Usage API 费用读取权限。

诊断不会写数据库。全部通过后点击“同步 OCI 数据”，并在“同步中心”查看进度。

## 8. 常见问题

### 页面无法访问

```bash
docker compose ps
docker compose logs --tail=200 oci-arm-monitor-web
```

HTTP 模式检查所选端口；HTTPS 模式检查 DNS、TCP `80/443`、系统时间和端口占用。

### 页面返回 502

```bash
docker compose logs --tail=200 oci-arm-monitor-server
docker compose ps
```

Web 容器会等待后端健康检查通过。后端启动失败时重点检查 `.env` 必填项和 OCI 配置。

### OCI 诊断返回 403

确认 Dynamic Group matching rule 使用当前监控实例 OCID，并确认 Policy 创建在正确的 Tenancy。新 Policy 生效可能有短暂延迟。

### 非 OCI 服务器

非 OCI Compute 实例不能使用 Instance Principal。重新运行初始化脚本并选择：

```text
config_file
```

然后按 [OCI 接入说明](oci-setup.md) 准备 API Key、`deploy/oci/config` 和私钥。

## 9. 更新与运维

```bash
git pull --ff-only
docker compose up -d --build
docker compose ps
```

SQLite、Caddy 状态和 HTTPS 证书保存在 Docker volume 中，重建容器不会删除这些数据。备份方式见 [Docker 容器与数据说明](docker-backend.md)。

## 10. Oracle 官方参考

- [Calling Services from an Instance](https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/callingservicesfrominstances.htm)
- [Managing Dynamic Groups](https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/managingdynamicgroups.htm)
- [Cloud Shell](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/cloudshellintro.htm)
