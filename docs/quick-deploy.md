# Oracle ARM 两步快速部署

适合把 OCI ARM Monitor 直接部署在 Oracle Cloud Compute 实例上。推荐使用 Instance Principal，不需要创建 API 用户，也不需要把 OCI 私钥保存到服务器。

## 前提

- OCI Compute 实例可访问互联网。
- 服务器已安装 Docker、Docker Compose、`git`、`curl` 和 `jq`。
- 服务器已有 Nginx、OpenResty 或 1Panel，用于域名和证书。
- 域名已经指向服务器。

容器默认只监听 `127.0.0.1:28461`，不占用 Nginx/OpenResty 使用的 `80/443`，也不需要对公网放行 `28461`。

## 第一步：在监控服务器初始化

```bash
git clone https://github.com/Regltim/oci-arm-monitor.git
cd oci-arm-monitor
bash scripts/init-deploy.sh
```

公开访问 Origin 填用户最终访问的地址：

```text
https://monitor.example.com
```

Web 端口直接使用默认值 `28461`，OCI 认证模式选择：

```text
instance_principal
```

脚本会从 Instance Metadata 自动读取：

- 当前监控服务器的 Instance OCID
- Tenancy OCID
- 当前实例所在 Compartment OCID
- Region

随后脚本会输出一条 OCI Cloud Shell 命令，格式如下：

```bash
bash scripts/oci-cloud-shell-setup.sh \
  --tenancy-id 'ocid1.tenancy.oc1..replace-with-your-tenancy-ocid' \
  --instance-id 'ocid1.instance.oc1.region.replace-with-your-instance-ocid' \
  --resource-compartment-id 'ocid1.compartment.oc1..replace-with-your-compartment-ocid'
```

如果被监控资源位于根 Compartment，`--resource-compartment-id` 直接传 Tenancy OCID。脚本会自动使用 `in tenancy` 权限范围。

## 第二步：在 OCI Cloud Shell 创建权限

打开 OCI Console 顶部的 Cloud Shell，执行：

```bash
git clone https://github.com/Regltim/oci-arm-monitor.git
cd oci-arm-monitor
```

然后粘贴第一步输出的 `oci-cloud-shell-setup.sh` 命令。

脚本默认创建：

```text
Dynamic Group: oci-arm-monitor-instances
Policy:        oci-arm-monitor-readonly
```

Dynamic Group 只匹配当前监控服务器实例。Policy 只授予读取 Compute、VNIC、Monitoring 和 Usage 数据的权限。

完成后等待几分钟让 IAM Policy 生效。

## 启动应用

回到监控服务器项目目录：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

本机验证：

```bash
curl -I http://127.0.0.1:28461/
```

## 配置域名反向代理

在已有 Nginx/OpenResty 站点中配置：

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

1Panel 的反向代理地址填写：

```text
http://127.0.0.1:28461
```

域名和 HTTPS 证书完全由 Nginx/OpenResty/1Panel 管理。容器不会申请或保存证书。

## 登录和同步

打开：

```text
https://monitor.example.com
```

使用初始化时设置的管理员账号登录，然后：

1. 打开“系统设置”。
2. 运行 OCI 连接诊断。
3. 诊断通过后执行同步。
4. 在总览、实例、流量和成本页面查看数据。

## 常见问题

### Cloud Shell 提示 Compartment OCID 格式错误

普通 Compartment 使用 `ocid1.compartment...`；根 Compartment 使用 Tenancy OCID。当前脚本两种格式都支持：

```text
ocid1.compartment.oc1..replace-with-your-compartment-ocid
ocid1.tenancy.oc1..replace-with-your-tenancy-ocid
```

### 容器提示端口已被占用

默认端口是 `28461`，不会与 `80/443` 冲突。检查：

```bash
sudo ss -ltnp 'sport = :28461'
```

如果仍被占用，重新运行初始化脚本选择另一个高位端口，并同步修改 Nginx/OpenResty 的代理地址。

### 域名返回 502

```bash
curl -I http://127.0.0.1:28461/
docker compose ps
docker compose logs --tail=200 oci-arm-monitor-web
docker compose logs --tail=300 oci-arm-monitor-server
```

本机访问正常时，问题位于 Nginx/OpenResty 的代理配置；本机访问失败时，先处理容器状态。

### OCI 诊断返回 403

确认：

- Dynamic Group matching rule 使用当前监控服务器的 Instance OCID。
- Policy 位于正确 Tenancy。
- 普通 Compartment 和根 Compartment 使用了对应权限范围。
- 新 Policy 已等待几分钟生效。

### 非 OCI 服务器

非 OCI Compute 实例不能使用 Instance Principal。重新运行初始化脚本选择 `config_file`，再按 [OCI 接入说明](oci-setup.md) 配置 API Key。

## 更新

```bash
git pull --ff-only
bash scripts/init-deploy.sh
docker compose up -d --build
docker compose ps
```
