# Oracle ARM 两步快速部署

这份文档适合把监控面板部署在 Oracle Cloud ARM 实例本机。

推荐使用 `Instance Principal`：服务器不保存 OCI 私钥，OCI Console Cloud Shell 只负责创建 Dynamic Group 和只读 Policy，ARM 实例终端负责生成配置和启动应用。

```text
OCI Cloud Shell                    Oracle ARM 实例
创建 Dynamic Group / Policy       读取 Instance Metadata
                                  生成 .env 并启动应用
```

Cloud Shell 是 OCI 托管终端，不是目标 ARM 实例。它不会替你启动 Docker，也不会读取项目数据库。

## 0. 先确认服务器使用最新源码

如果项目通过 Git 克隆，先在服务器项目目录执行：

```bash
git status --short
git pull --ff-only
```

如果 `git rev-parse --is-inside-work-tree` 提示不是 Git 工作区，当前目录通常来自 GitHub ZIP。请重新下载最新 ZIP 到新目录，再保留服务器本机的 `.env`、`deploy/oci/` 和持久化数据；不要把这些私有文件上传到仓库。

执行初始化前可以核对提示文案：

```bash
grep -n "面板访问域名" scripts/init-deploy.sh
```

当前版本应使用 `https://monitor.example.com` 作为公开示例。若仍出现其他示例域名，说明服务器源码尚未更新。已有 `.env` 中的域名和 OCI 标识只会显示为“已设置，回车保留”，不会作为默认值直接回显。

## 1. ARM 实例自动生成配置

确认项目已经放到 ARM 实例，例如：

```bash
cd /opt/oci-arm-cost-monitor
```

初始化脚本使用 `curl` 和 `jq` 读取 OCI Instance Metadata。Ubuntu / Debian 安装：

```bash
sudo apt update
sudo apt install -y curl jq
```

Oracle Linux 安装：

```bash
sudo dnf install -y curl jq
```

执行：

```bash
bash scripts/init-deploy.sh
```

认证模式保留默认值：

```text
instance_principal
```

脚本会自动读取：

- 当前服务器的 Instance OCID。
- Tenancy OCID。
- 当前实例所在 Compartment OCID。
- OCI canonical region。

你只需要确认面板域名、管理员账号和密码。如果被监控资源不在当前实例的 Compartment，可在提示时替换成目标 Compartment OCID。脚本读取到已有私有配置时不会把域名、OCID 或 fingerprint 直接显示在终端。

脚本会生成本机 `.env`，并输出一条包含当前实例信息的 Cloud Shell 命令。`.env` 已被 Git 忽略，不会进入源码仓库。

如果 Metadata 读取失败，脚本会回退为手工输入，不会中断部署。先确认 `curl`、`jq` 已安装，并确认命令确实运行在 OCI Compute 实例内。

## 2. Cloud Shell 一键创建 IAM

在 OCI Console 顶部打开 Cloud Shell。Cloud Shell 是独立环境，第一次使用时先获取项目脚本：

```bash
git clone https://github.com/your-account/oci-arm-cost-monitor.git
cd oci-arm-cost-monitor
```

将第 1 步初始化脚本输出的命令粘贴到 Cloud Shell。格式如下，文档中的值仅为占位符：

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

脚本可重复执行。同名资源已经存在时会同步 matching rule 和 policy statements，不会重复创建。

只想预览、不调用 OCI API 时，在命令末尾加：

```bash
--dry-run
```

Cloud Shell 当前用户必须具备管理 Dynamic Group 和 Policy 的权限。如果没有权限，请让 tenancy 管理员运行这条命令。IAM Policy 通常需要几分钟生效。

## 3. ARM 实例启动后端

回到 ARM 实例项目目录：

```bash
docker compose config --quiet
docker compose up -d --build oci-arm-monitor-server
docker compose ps
docker compose logs -f oci-arm-monitor-server
```

如果只允许本机 Nginx 反代访问，建议将 `docker-compose.yml` 端口限制为：

```yaml
ports:
  - "127.0.0.1:9090:9090"
```

首次构建前端和配置 Nginx，继续执行 [完整部署手册](fresh-deploy-docker-compose.md)的“构建前端”和“配置 Nginx”章节。

## 4. 登录后运行连接诊断

进入：

```text
系统设置 -> OCI 配置 -> 运行 OCI 连接诊断
```

诊断会依次读取少量真实数据，检查：

- 后端 `.env` 基础配置。
- Instance Principal Provider。
- Compute 实例读取权限。
- VNIC 网络读取权限。
- Monitoring 指标读取权限。
- Usage API 费用读取权限。

诊断不会写入数据库。全部通过后再点击“同步 OCI 数据”。

## 5. 非 OCI 服务器

如果后端不在 Oracle Cloud 实例上，无法使用 Instance Principal。改用 API Key 模式：

```env
OCI_AUTH_MODE=config_file
OCI_CONFIG_PROFILE=DEFAULT
OCI_CONFIG_DIR=./deploy/oci
```

然后按 [OCI 接入说明](oci-setup.md)配置 `deploy/oci/config` 和 `oci_api_key.pem`。

## 6. 发布前检查

项目开源前运行：

```bash
bash scripts/check-public-release.sh
```

该检查扫描 Git 已跟踪文件和未跟踪但未被忽略的发布候选文件，不读取已忽略的 `.env`、私钥或本地数据库。完整流程见 [开源发布检查清单](open-source-release.md)。

## 7. Oracle 官方参考

- [Calling Services from an Instance](https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/callingservicesfrominstances.htm)
- [Managing Dynamic Groups](https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/managingdynamicgroups.htm)
- [Cloud Shell](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/cloudshellintro.htm)
