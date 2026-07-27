# OCI 接入说明

OCI ARM Monitor 支持两种认证方式：

| 模式 | 适用环境 | 服务器是否保存 OCI 私钥 |
| --- | --- | --- |
| `instance_principal` | 应用运行在 OCI Compute 实例 | 否 |
| `config_file` | 应用运行在其他云或本地服务器 | 是 |

应用部署在 OCI Compute 实例时优先使用 Instance Principal。API Key 只用于无法使用实例身份的环境。

## 1. 必要权限

系统需要以下只读权限：

```text
instance-family           Compute 实例
virtual-network-family    VNIC、公网 IP 和私网 IP
metrics                   CPU、内存和流量指标
usage-report              费用和用量
```

针对普通 Compartment：

```text
Allow <principal> to read instance-family in compartment id <compartment-ocid>
Allow <principal> to read virtual-network-family in compartment id <compartment-ocid>
Allow <principal> to read metrics in compartment id <compartment-ocid>
Allow <principal> to read usage-report in tenancy
```

针对根 Compartment：

```text
Allow <principal> to read instance-family in tenancy
Allow <principal> to read virtual-network-family in tenancy
Allow <principal> to read metrics in tenancy
Allow <principal> to read usage-report in tenancy
```

`<principal>` 在 Instance Principal 模式下是 `dynamic-group <name>`，在 API Key 模式下是 `group <name>`。

费用权限必须使用 tenancy 范围。新 Policy 可能需要几分钟生效。

## 2. Instance Principal

### 2.1 初始化服务器

在 OCI Compute 实例的项目目录运行：

```bash
bash scripts/init-deploy.sh
```

认证模式选择：

```text
instance_principal
```

脚本会从 Instance Metadata 读取当前 Instance OCID、Tenancy OCID、Compartment OCID 和 Region，并输出 Cloud Shell 命令。

### 2.2 创建 Dynamic Group 和 Policy

在 OCI Console Cloud Shell 获取项目脚本，然后粘贴初始化脚本输出的命令：

```bash
bash scripts/oci-cloud-shell-setup.sh \
  --tenancy-id 'ocid1.tenancy.oc1..replace-with-your-tenancy-ocid' \
  --instance-id 'ocid1.instance.oc1.region.replace-with-your-instance-ocid' \
  --resource-compartment-id 'ocid1.compartment.oc1..replace-with-your-compartment-ocid'
```

脚本默认创建：

```text
Dynamic Group: oci-arm-monitor-instances
Policy:        oci-arm-monitor-readonly
```

Matching rule 只匹配当前监控服务器实例：

```text
instance.id = '<monitor-instance-ocid>'
```

如果目标资源位于根 Compartment，`--resource-compartment-id` 传入 Tenancy OCID。脚本会生成 `in tenancy`，不会把 Tenancy OCID 当作普通 Compartment OCID。

完整步骤见 [Oracle ARM 两步快速部署](quick-deploy.md)。

## 3. API Key / config_file

### 3.1 创建专用用户和用户组

推荐创建专用 API 用户，并加入只读用户组，例如：

```text
User:  oci-monitor-api-user
Group: oci-monitor-readers
```

不要直接给 API 用户管理员权限。

### 3.2 创建密钥

在部署服务器执行：

```bash
mkdir -p deploy/oci
cd deploy/oci
openssl genrsa -out oci_api_key.pem 2048
openssl rsa -pubout -in oci_api_key.pem -out oci_api_key_public.pem
```

在 OCI Console 打开 API 用户详情：

```text
API Keys -> Add API key -> Paste public key
```

粘贴 `oci_api_key_public.pem`，并记录 OCI 返回的 fingerprint。

### 3.3 创建 OCI config

创建 `deploy/oci/config`：

```ini
[DEFAULT]
user=ocid1.user.oc1..replace-with-your-user-ocid
fingerprint=replace-with-your-api-key-fingerprint
tenancy=ocid1.tenancy.oc1..replace-with-your-tenancy-ocid
region=ap-seoul-1
key_file=/home/monitor/.oci/oci_api_key.pem
```

`key_file` 必须使用容器内路径 `/home/monitor/.oci/oci_api_key.pem`。

设置权限：

```bash
cd <project-directory>
sudo chown -R 10001:10001 deploy/oci
sudo chmod 700 deploy/oci
sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem
```

### 3.4 创建 IAM Policy

为 `oci-monitor-readers` 创建只读策略：

```text
Allow group oci-monitor-readers to read instance-family in compartment id <compartment-ocid>
Allow group oci-monitor-readers to read virtual-network-family in compartment id <compartment-ocid>
Allow group oci-monitor-readers to read metrics in compartment id <compartment-ocid>
Allow group oci-monitor-readers to read usage-report in tenancy
```

如果目标资源位于根 Compartment，将前三条改为 `in tenancy`。

控制台逐步说明见 [Oracle 控制台通俗配置流程](oracle-console-simple-guide.md)。

## 4. 启用实例监控插件

每台需要采集 CPU、内存和实例级指标的 Compute 实例都要启用 Oracle Cloud Agent 的 `Compute Instance Monitoring` plugin。

控制台路径通常是：

```text
Compute -> Instances -> 选择实例 -> Oracle Cloud Agent -> Compute Instance Monitoring
```

确认状态为 Enabled / Running。也可以在实例 Metrics 页面选择 namespace：

```text
oci_computeagent
```

如果 Compute 实例能在 OCI Console 显示 CPU、Memory 等图表，说明 Monitoring 正在接收指标。

## 5. 生成应用配置

运行：

```bash
bash scripts/init-deploy.sh
```

Instance Principal 选择 `instance_principal`；API Key 选择 `config_file`。脚本会生成 `.env` 并检查 API Key 文件位置和权限。

关键配置：

```env
OCI_AUTH_MODE=instance_principal
OCI_REGION=ap-seoul-1
OCI_COMPARTMENT_OCID=ocid1.compartment.oc1..replace-with-your-compartment-ocid
OCI_TENANCY_OCID=ocid1.tenancy.oc1..replace-with-your-tenancy-ocid
```

API Key 模式还需要：

```env
OCI_AUTH_MODE=config_file
OCI_CONFIG_PROFILE=DEFAULT
OCI_CONFIG_DIR=./deploy/oci
```

`.env`、`deploy/oci/` 和私钥不会进入 Git。

## 6. 启动和诊断

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

登录面板后进入：

```text
系统设置 -> OCI 配置 -> 运行 OCI 连接诊断
```

诊断依次验证 Provider、Compute、VNIC、Monitoring 和 Usage API。诊断不写数据库，通过后再执行同步。

## 7. 常见错误

### 401 / NotAuthenticated

API Key 模式检查：

- `user` 是否为 API 用户 OCID。
- fingerprint 是否与控制台一致。
- 私钥是否与已上传的公钥配对。
- `key_file` 是否为容器内路径。
- 容器用户是否可读取文件。

### 403 / NotAuthorized

检查：

- Dynamic Group matching rule 或 API 用户组成员关系。
- Policy 的 principal 名称。
- Policy 所在 Tenancy 和目标 Compartment scope。
- `read usage-report in tenancy` 是否存在。

### 实例为空

检查 `.env` 中的 Region 和 Compartment OCID。根 Compartment 应使用 Tenancy OCID，并配套 `in tenancy` Policy。

### CPU、内存或流量为空

检查目标实例的 Compute Instance Monitoring plugin，以及 `read metrics` 权限。

### 成本为空

Usage API 数据可能有延迟，当前月份也可能没有可归集费用。先确认 `read usage-report in tenancy` 诊断通过。

## 8. 官方参考

- [Calling Services from an Instance](https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/callingservicesfrominstances.htm)
- [API signing key](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm)
- [SDK and CLI Configuration File](https://docs.oracle.com/iaas/Content/API/Concepts/sdkconfig.htm)
- [IAM policy syntax](https://docs.oracle.com/en-us/iaas/Content/Identity/Concepts/policysyntax.htm)
- [Common Policies](https://docs.oracle.com/iaas/Content/Identity/Concepts/commonpolicies.htm)
- [Enabling Monitoring for Compute Instances](https://docs.oracle.com/iaas/Content/Compute/Tasks/enablingmonitoring.htm)
