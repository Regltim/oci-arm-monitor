# 微信公众号通知配置

OCI ARM Monitor 可以通过微信公众号模板消息发送：

- CPU、内存、磁盘和 OCI 同步延迟告警；
- 告警恢复通知；
- 手动测试通知；
- 可选的每日服务器状态摘要。

当前只接入微信官方公众号接口，不需要 WxPusher、邮件服务或额外推送容器。

## 适用账号

推荐先使用[微信公众平台测试账号](https://mp.weixin.qq.com/debug/cgi-bin/sandbox?t=sandbox/login)。测试账号可以直接创建模板并让个人微信扫码关注，适合验证完整推送链路。

正式公众号必须具备模板消息接口权限。未认证个人公众号通常没有该权限；这种情况下继续使用测试账号即可，不需要购买第三方服务。

## 一、创建测试模板

1. 打开微信公众平台测试账号页面并扫码登录。
2. 在“测试号信息”中找到 `appID` 和 `appsecret`。
3. 使用需要接收通知的个人微信扫描测试号二维码。
4. 在“用户列表”中复制该微信对应的 OpenID。
5. 在“模板消息接口”中新增测试模板。

模板标题：

```text
OCI ARM Monitor 通知
```

模板内容：

```text
{{first.DATA}}
告警级别：{{level.DATA}}
告警项目：{{metric.DATA}}
当前状态：{{status.DATA}}
详细内容：{{content.DATA}}
发生时间：{{time.DATA}}
{{remark.DATA}}
```

保存后复制 Template ID。字段名必须保持为 `first`、`level`、`metric`、`status`、`content`、`time` 和 `remark`，否则微信会拒绝消息。

## 二、初始化时配置

在项目目录执行：

```bash
bash scripts/init-deploy.sh
```

脚本询问：

```text
是否启用微信公众号通知 [y/N]
```

选择 `y` 后依次填写：

- AppID；
- AppSecret；
- Template ID；
- 接收人 OpenID，多个使用英文逗号分隔；
- 是否在告警状态变化时立即推送；
- 是否启用每日状态摘要；
- 每日推送时间和时区。

已有 AppSecret、OpenID 和其他标识不会显示在提示默认值中，直接回车可以保留。脚本还会生成 `MONITOR_SETTINGS_ENCRYPTION_KEY`，二次初始化时会继续使用原密钥。

完成后重新构建容器：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

## 三、在监控页面配置

登录监控面板，进入“系统设置 → 通知设置”。页面支持：

- 启用或停用微信公众号通知；
- 覆盖 AppID、AppSecret、Template ID 和接收人；
- 设置监控面板访问地址；
- 独立控制即时告警和每日摘要；
- 发送测试通知；
- 查看最近 20 条脱敏推送结果。

凭据输入框是只写字段。页面只显示 AppID、Template ID 的掩码、AppSecret 是否已设置以及接收人数，不会从后端读取 AppSecret 或 OpenID 明文。留空保存表示保留当前值。

如果页面提示“后台保存暂不可用”，重新运行初始化脚本生成加密密钥，然后重建后端容器：

```bash
bash scripts/init-deploy.sh
docker compose up -d --build
```

## 四、面板地址与反向代理

模板消息点击后会打开 `MONITOR_PUBLIC_URL`。该值应填写用户实际访问的完整 Origin，例如：

```text
https://monitor.example.com
```

容器不管理域名或证书。服务器已有的 Nginx、OpenResty 或 1Panel 继续把域名反向代理到默认 Web 地址：

```text
http://127.0.0.1:28461
```

不要填写容器内部后端端口 `9090`，也不要在面板地址后添加路径、查询参数或末尾斜杠。

## 五、推送规则

即时推送开启时：

- 指标从正常变为告警时发送一次；
- 告警持续期间不重复发送；
- 指标恢复正常时发送一次；
- 服务重启不会重新发送仍处于活动状态的告警；
- 微信发送失败也不会每 15 秒重复重试。

关闭即时推送后，系统仍会记录告警状态变化。重新开启不会补发关闭期间已经发生的旧变化。

每日摘要默认关闭。开启后，系统会在所选时区每天到达指定时间后发送一次，内容包含 CPU、内存、磁盘、OCI 最近同步时间和当前告警数量。每日摘要使用最近一次服务器采样，不会额外触发 OCI 同步。

## 六、环境变量

也可以直接编辑服务器 `.env`：

```env
MONITOR_WECHAT_ENABLED=true
MONITOR_WECHAT_APP_ID=wx_example_app
MONITOR_WECHAT_APP_SECRET=replace-with-your-app-secret
MONITOR_WECHAT_TEMPLATE_ID=template_example_01
MONITOR_WECHAT_OPEN_IDS=openid_example_1,openid_example_2
MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED=true
MONITOR_WECHAT_DAILY_SUMMARY_ENABLED=false
MONITOR_WECHAT_DAILY_SUMMARY_TIME=09:00
MONITOR_WECHAT_ZONE_ID=Asia/Shanghai
MONITOR_SETTINGS_ENCRYPTION_KEY=replace-with-base64-32-byte-key
```

`.env` 已被 Git 忽略。不要把真实 AppID、AppSecret、Template ID、OpenID 或加密密钥写入 README、Issue、日志和公开仓库。

修改后执行：

```bash
docker compose up -d --build
```

## 七、常见错误

| 错误码 | 常见原因 | 处理方式 |
| --- | --- | --- |
| `40003` | OpenID 无效或不属于当前公众号 | 重新从当前测试号用户列表复制 OpenID |
| `40013` | AppID 无效 | 检查 AppID 是否来自当前测试号或正式公众号 |
| `40125` | AppSecret 无效 | 重置 AppSecret 后重新保存配置 |
| `40014` / `42001` | access token 无效或过期 | 系统会自动刷新并重试一次；持续出现时检查 AppID 和 AppSecret |
| `43004` | 用户未关注公众号 | 让接收人重新扫码关注测试号或正式公众号 |

排查命令：

```bash
docker compose ps
docker compose logs --tail=200 oci-arm-monitor-server
```

日志只记录脱敏结果，不会输出 AppSecret、access token 或接收人 OpenID。
