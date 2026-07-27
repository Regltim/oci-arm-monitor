# Oracle 控制台通俗配置流程

这份文档只讲本项目需要的 Oracle Cloud 配置。目标是让后端可以安全地读取真实资源、监控指标和费用数据。

如果面板后端就部署在 Oracle Cloud 实例本机，优先看快速版：[quick-deploy.md](quick-deploy.md)。快速版只需要 Dynamic Group 和 Policy，不需要 API 用户、公钥、私钥和 `deploy/oci/config`。

本文下面主要讲 API Key 备用模式，适合后端部署在非 Oracle 服务器，或者你暂时不想使用 Instance Principal。

先记住一句话：

```text
API 用户负责拿钥匙调用 Oracle API。
用户组负责装这个 API 用户。
Policy 负责告诉 Oracle：这个用户组只能读取哪些资源。
```

前端不需要配置 Oracle。所有 Oracle 配置都放在服务器 `.env` 和 `deploy/oci/` 目录。

## 1. 你最终要准备哪些东西

部署前需要拿到这些值：

```text
user OCID              API 调用用户的 OCID，写进 OCI config
tenancy OCID           你的 Oracle 租户 OCID，写进 OCI config
fingerprint            上传 API 公钥后 Oracle 生成，写进 OCI config
region                 资源所在区域，例如 ap-seoul-1，写进 OCI config 和 .env
compartment OCID       ARM 实例所在 compartment 的 OCID，写进 .env
private key            服务器上的私钥文件，挂载给后端容器
```

## 2. 创建专用 API 用户

推荐创建一个专门给本系统用的用户，不要直接用你的管理员账号。

Oracle 控制台路径可能有两种：

```text
Identity & Security -> Domains -> Default domain -> Users
```

如果你的控制台没有 Domains，找这个路径：

```text
Identity & Security -> Users
```

操作：

1. 点击 Create user。
2. 用户名建议填 `oci-monitor-api-user`。
3. 创建后进入这个用户详情页。
4. 复制这个用户的 OCID，后面写 `user=...` 会用到。

## 3. 创建用户组

路径：

```text
Identity & Security -> Domains -> Default domain -> Groups
```

旧版控制台可能是：

```text
Identity & Security -> Groups
```

操作：

1. 点击 Create group。
2. 名称建议填 `oci-monitor-readers`。
3. 创建后进入用户组详情。
4. 把 `oci-monitor-api-user` 加入这个组。

可以把用户组理解成“权限篮子”。后面 Policy 不是直接授权给用户，而是授权给这个组。

## 4. 确认 ARM 实例在哪个 Compartment

路径：

```text
Compute -> Instances
```

看页面上方或筛选栏里的 Compartment，确认你的 ARM 实例属于哪个 compartment。

然后复制这个 compartment 的 OCID：

```text
Identity & Security -> Compartments -> 选择对应 compartment -> Copy OCID
```

这个值要填到后端 `.env`：

```env
OCI_COMPARTMENT_OCID=ocid1.compartment.oc1..你的值
```

如果你的实例直接放在 root compartment，`OCI_COMPARTMENT_OCID` 通常填 tenancy OCID。但更推荐单独建一个普通 compartment 放实例，权限边界更清楚。

## 5. 写 IAM Policy

路径：

```text
Identity & Security -> Policies
```

点击 Create policy。

建议名称：

```text
oci-monitor-read-policy
```

Description 可以填：

```text
Read-only policy for OCI ARM cost monitor
```

### 5.0 Policy Builder 怎么填

如果 Create Policy 页面有 Policy Builder，可以按下面的字段理解：

```text
Policy use cases / Use case      选择 Custom 或手动创建语句
Group                            oci-monitor-readers
Verb                             read
Resource type                    instance-family / virtual-network-family / metrics / usage-report
Location / Compartment           你的 ARM 实例所在 compartment
Condition                        不填
```

本项目需要 4 条 statement，所以在 Policy Builder 里要添加 4 次：

```text
第 1 条：Group = oci-monitor-readers，Verb = read，Resource type = instance-family，Location = 目标 compartment
第 2 条：Group = oci-monitor-readers，Verb = read，Resource type = virtual-network-family，Location = 目标 compartment
第 3 条：Group = oci-monitor-readers，Verb = read，Resource type = metrics，Location = 目标 compartment
第 4 条：Group = oci-monitor-readers，Verb = read，Resource type = usage-report，Location = tenancy
```

注意：

- 前 3 条读实例、网卡和监控指标，Location 选 ARM 实例所在 compartment。
- 第 4 条读费用数据，Location 要选 tenancy。
- Condition 不需要填。
- 如果 Policy Builder 下拉框里找不到 `usage-report`、`metrics` 或 `virtual-network-family`，点击 Show manual editor / 手动编辑，直接粘贴下面 5.1 的策略文本。

### 5.1 推荐写法：只读一个 Compartment

如果你只监控一个 compartment，推荐这样写。把 `<compartment-name>` 换成你的 compartment 名称：

```text
Allow group oci-monitor-readers to read instance-family in compartment <compartment-name>
Allow group oci-monitor-readers to read virtual-network-family in compartment <compartment-name>
Allow group oci-monitor-readers to read metrics in compartment <compartment-name>
Allow group oci-monitor-readers to read usage-report in tenancy
```

每一行的意思：

```text
instance-family           读取 Compute 实例列表和实例基础信息
virtual-network-family    读取 VNIC、公网 IP、私网 IP
metrics                   读取 CPU、内存、网卡流量等 Monitoring 指标
usage-report              读取费用和用量数据
```

费用数据是租户级的，所以 `usage-report` 要写 `in tenancy`。

### 5.2 如果 compartment 名称不确定

可以用 compartment OCID 写 Policy。把 OCID 换成你的真实 compartment OCID：

```text
Allow group oci-monitor-readers to read instance-family in compartment id ocid1.compartment.oc1..你的值
Allow group oci-monitor-readers to read virtual-network-family in compartment id ocid1.compartment.oc1..你的值
Allow group oci-monitor-readers to read metrics in compartment id ocid1.compartment.oc1..你的值
Allow group oci-monitor-readers to read usage-report in tenancy
```

### 5.3 更容易跑通的写法：全租户只读

如果你刚开始不确定实例在哪个 compartment，可以先用这个写法跑通。它仍然是只读权限，但范围比上面大：

```text
Allow group oci-monitor-readers to read instance-family in tenancy
Allow group oci-monitor-readers to read virtual-network-family in tenancy
Allow group oci-monitor-readers to read metrics in tenancy
Allow group oci-monitor-readers to read usage-report in tenancy
```

实例直接位于根 Compartment 时，也应使用这组 `in tenancy` 策略。根 Compartment 的 OCID 与 Tenancy OCID 相同，不能写成 `in compartment id <Tenancy OCID>`。

跑通后，建议再收窄成 5.1 或 5.2。

### 5.4 报错：Compartment xxx does not exist or is not part of the policy compartment subtree

这个报错通常不是权限语句本身的问题，而是这几个原因之一：

```text
原因 1：Create Policy 页面顶部的 Compartment 选错了。
原因 2：把 compartment OCID 当成名称写了。
原因 3：目标 compartment 是多级子目录，但你只写了最后一级名称。
```

最省事的做法：

```text
Create Policy 页面顶部的 Compartment 选择 root tenancy。
Policy 语句里用 compartment id 写真实 OCID。
```

例如你的目标 compartment OCID 是：

```text
ocid1.compartment.oc1..xxxxxxxxxxxxxxxx
```

手动编辑时写：

```text
Allow group oci-monitor-readers to read instance-family in compartment id ocid1.compartment.oc1..xxxxxxxxxxxxxxxx
Allow group oci-monitor-readers to read virtual-network-family in compartment id ocid1.compartment.oc1..xxxxxxxxxxxxxxxx
Allow group oci-monitor-readers to read metrics in compartment id ocid1.compartment.oc1..xxxxxxxxxxxxxxxx
Allow group oci-monitor-readers to read usage-report in tenancy
```

如果你只知道 compartment 名称，并且名称就是 `xxx`，可以写：

```text
Allow group oci-monitor-readers to read instance-family in compartment xxx
Allow group oci-monitor-readers to read virtual-network-family in compartment xxx
Allow group oci-monitor-readers to read metrics in compartment xxx
Allow group oci-monitor-readers to read usage-report in tenancy
```

如果 `xxx` 是多级 compartment，例如 root 下有 `Prod`，`Prod` 下面才是 `ARM`，并且 Policy 不是创建在 `Prod` 下，就要写路径：

```text
Allow group oci-monitor-readers to read instance-family in compartment Prod:ARM
Allow group oci-monitor-readers to read virtual-network-family in compartment Prod:ARM
Allow group oci-monitor-readers to read metrics in compartment Prod:ARM
Allow group oci-monitor-readers to read usage-report in tenancy
```

更推荐用 `compartment id` 写 OCID，少踩名称和层级路径的坑。

## 6. 创建 API Key

在部署服务器上执行：

```bash
mkdir -p /opt/oci-arm-cost-monitor/deploy/oci
cd /opt/oci-arm-cost-monitor/deploy/oci

openssl genrsa -out oci_api_key.pem 2048
openssl rsa -pubout -in oci_api_key.pem -out oci_api_key_public.pem
```

回到 Oracle 控制台，进入 `oci-monitor-api-user` 用户详情：

```text
API Keys -> Add API key -> Paste public key
```

把 `oci_api_key_public.pem` 的内容粘进去。

上传后 Oracle 会生成 fingerprint，并提供 config snippet。复制这段配置。

## 7. 创建 OCI config

在服务器创建：

```bash
nano /opt/oci-arm-cost-monitor/deploy/oci/config
```

内容类似：

```ini
[DEFAULT]
user=ocid1.user.oc1..你的API用户OCID
fingerprint=你的fingerprint
tenancy=ocid1.tenancy.oc1..你的租户OCID
region=ap-seoul-1
key_file=/home/monitor/.oci/oci_api_key.pem
```

注意：

- `key_file` 必须写容器内路径 `/home/monitor/.oci/oci_api_key.pem`。
- 不要写宿主机路径 `/opt/oci-arm-cost-monitor/deploy/oci/oci_api_key.pem`。

设置权限：

```bash
cd /opt/oci-arm-cost-monitor
sudo chown -R 10001:10001 deploy/oci
sudo chmod 700 deploy/oci
sudo chmod 600 deploy/oci/config deploy/oci/oci_api_key.pem
```

## 8. 配置后端 .env

编辑：

```bash
nano /opt/oci-arm-cost-monitor/.env
```

至少要有：

```env
MONITOR_ADMIN_USERNAME=admin
MONITOR_ADMIN_PASSWORD=替换为强密码
MONITOR_COOKIE_SECURE=true

OCI_AUTH_MODE=config_file
OCI_CONFIG_PROFILE=DEFAULT
OCI_REGION=ap-seoul-1
OCI_COMPARTMENT_OCID=ocid1.compartment.oc1..你的CompartmentOCID
OCI_TENANCY_OCID=
OCI_CONFIG_DIR=./deploy/oci
```

`OCI_TENANCY_OCID` 可以先留空，因为 config 文件里已经有 `tenancy=...`。

## 9. 启动并验证

启动后端：

```bash
cd /opt/oci-arm-cost-monitor
docker compose up -d --build oci-arm-monitor-server
docker compose logs -f oci-arm-monitor-server
```

登录面板：

```text
https://你的域名
```

进入系统设置：

1. 看 OCI 配置状态是否为已配置。
2. 点击同步 OCI 数据。
3. 回到总览、实例、流量、成本页面看真实数据。

## 10. 常见报错怎么判断

### 10.1 同步失败，401 或 NotAuthenticated

优先检查 API Key：

- `deploy/oci/config` 里的 `user` 是不是 API 用户 OCID。
- `fingerprint` 是否和控制台 API Keys 页面一致。
- `key_file` 是否是 `/home/monitor/.oci/oci_api_key.pem`。
- 私钥文件是否就是上传公钥对应的那把私钥。
- 容器是否能读取 `deploy/oci/oci_api_key.pem`。

### 10.2 同步失败，403 或 NotAuthorized

优先检查 IAM Policy：

- API 用户是否已经加入 `oci-monitor-readers` 组。
- Policy 里的 group 名称是否写对。
- Policy 是否创建在正确的 tenancy 或父级 compartment。
- 资源是否真的在你授权的 compartment 里。
- 刚创建的 Policy 可能需要等待几分钟再生效。

### 10.3 配置显示已配置，但实例列表为空

优先检查：

- `.env` 的 `OCI_REGION` 是否是实例所在 region。
- `.env` 的 `OCI_COMPARTMENT_OCID` 是否是实例所在 compartment 的 OCID。
- ARM 实例是否在 root compartment，如果是，尝试填 tenancy OCID。

### 10.4 实例有了，但 CPU、内存、流量为空

检查 Compute Instance Monitoring plugin：

```text
Compute -> Instances -> 选择实例 -> Oracle Cloud Agent -> Compute Instance Monitoring
```

确认插件是 Enabled / Running。

### 10.5 成本为空

可能原因：

- `read usage-report in tenancy` 没生效。
- Usage API 数据有延迟。
- 当前账号或当前月份确实没有产生可归集费用。

## 11. 官方参考

- [Oracle API signing key](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm)
- [Oracle SDK and CLI config file](https://docs.oracle.com/iaas/Content/API/Concepts/sdkconfig.htm)
- [Oracle IAM policy syntax](https://docs.oracle.com/en-us/iaas/Content/Identity/Concepts/policysyntax.htm)
- [Oracle common policies](https://docs.oracle.com/iaas/Content/Identity/Concepts/commonpolicies.htm)
- [Oracle core services policy reference](https://docs.oracle.com/iaas/Content/Identity/Reference/corepolicyreference.htm)
