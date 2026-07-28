# 微信公众号通知配置

OCI ARM Monitor 通过微信官方公众号模板消息发送告警、恢复和每日摘要。每天的摘要分为两条独立消息：

- 运行状态：实例状态、实例 CPU/内存、监控主机 CPU/内存/磁盘、活动告警和 OCI 同步状态；
- 费用与流量：OCI 费用、手工费用、本月总费用、月底预测、入站/出站流量和免费额度。

微信请求不携带 `url` 或 `miniprogram`，消息不会打开监控网站。所有明细都直接显示在微信消息中。

当前只接入微信官方公众号接口，不需要 WxPusher、邮件服务或额外推送容器。

## 适用账号

推荐先使用[微信公众平台测试账号](https://mp.weixin.qq.com/debug/cgi-bin/sandbox?t=sandbox/login)。测试账号可以直接创建模板，并让个人微信扫码关注，适合验证完整推送链路。

正式公众号必须具备模板消息接口权限。未认证个人公众号通常没有该权限；这种情况下可以继续使用测试账号，不需要购买第三方服务。

## 一、准备测试账号

1. 打开微信公众平台测试账号页面并扫码登录。
2. 在“测试号信息”中找到 `appID` 和 `appsecret`。
3. 使用需要接收通知的个人微信扫描测试号二维码。
4. 在“用户列表”中复制该微信对应的 OpenID。
5. 在“模板消息接口”中分别新增下面两个模板。

## 二、创建运行状态模板

模板标题：

```text
OCI ARM Monitor 运行通知
```

模板内容：

```text
{{first.DATA}}
通知级别：{{level.DATA}}
监控范围：{{metric.DATA}}
当前状态：{{status.DATA}}
运行详情：
{{content.DATA}}
通知时间：{{time.DATA}}
{{remark.DATA}}
```

保存后复制第一份 Template ID。它用于：

- 即时告警；
- 告警恢复；
- 每日运行状态；
- 运行模板测试。

运行状态日报示例：

```text
OCI ARM Monitor 每日运行状态
通知级别：警告
监控范围：实例与监控主机
当前状态：3 台实例，1 项活动告警
运行详情：
实例汇总：运行 2 台，停止 1 台，其他 0 台
- arm-app-01：运行中，CPU 12.30%，内存 41.20%
- arm-app-02：运行中，CPU 26.80%，内存 53.10%
- arm-backup：已停止
监控主机：CPU 18.20%，内存 62.50%，磁盘 71.30%
活动告警：磁盘使用率（磁盘使用率当前 71.30%，阈值 70.00%。）
OCI 同步：成功，2026-07-27 08:02:16（同步完成）
通知时间：2026-07-27 09:00:00
数据来自最近一次主机采样和 OCI 同步
```

实例最多展开 10 台，活动告警最多展开 5 项。缺少实例指标时显示“暂无数据”，不会把缺失数据显示为 `0%`。消息不包含实例 OCID 或 IP 地址。

## 三、创建费用与流量模板

模板标题：

```text
OCI ARM Monitor 费用与流量日报
```

模板内容：

```text
{{first.DATA}}
通知级别：{{level.DATA}}
统计范围：{{metric.DATA}}
当前状态：{{status.DATA}}
费用与流量：
{{content.DATA}}
统计时间：{{time.DATA}}
{{remark.DATA}}
```

保存后复制第二份 Template ID。它用于：

- 每日费用与流量摘要；
- 费用与流量模板测试。

费用与流量日报示例：

```text
OCI ARM Monitor 费用与流量日报
通知级别：信息
统计范围：本月累计
当前状态：费用与流量正常
费用与流量：
OCI 费用：¥12.34
手工费用：¥8.00
本月总费用：¥20.34
月底费用预测：¥28.62
入站流量：123.45 GB
出站流量：67.89 GB
出站免费额度：10,000.00 GB
额度使用率：0.68%
剩余额度：9,932.11 GB
数据同步：2026-07-27 08:02:16
统计时间：2026-07-27 09:00:00
费用以 OCI Usage API 和手工记录为准
```

出站流量超过免费额度时会显示“超出额度”。免费额度为 `0` 时显示“出站免费额度：未配置”，不会计算使用率和剩余额度。没有成功 OCI 同步时，OCI 费用和流量显示“暂无同步数据”，手工费用仍按本地真实记录显示。

两个模板的字段名都必须保持为 `first`、`level`、`metric`、`status`、`content`、`time` 和 `remark`，否则微信会拒绝消息。

## 四、初始化时配置

在项目目录执行：

```bash
bash scripts/init-deploy.sh
```

选择启用微信公众号通知后，脚本依次询问：

- AppID；
- AppSecret；
- 运行状态 Template ID；
- 费用与流量 Template ID；
- 接收人 OpenID，多个使用英文逗号分隔；
- 是否在告警状态变化时立即推送；
- 是否启用每日双模板摘要；
- 每日推送时间和时区。

关闭每日摘要时，费用与流量 Template ID 可以暂时留空；开启每日摘要时必须填写。已有凭据和标识只显示“已设置，回车保留”，脚本不会回显原值。

完成后重新构建容器：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

## 五、在监控页面配置

登录监控面板，进入“系统设置 → 通知设置”。页面支持：

- 启用或停用微信公众号通知；
- 分别配置运行状态和费用与流量 Template ID；
- 覆盖 AppID、AppSecret 和接收人；
- 独立控制即时告警和每日双模板摘要；
- 同时测试两个模板并分别显示结果；
- 查看最近 20 条脱敏推送结果。

凭据输入框是只写字段。页面只显示 AppID 和两份 Template ID 的掩码、AppSecret 是否已设置以及接收人数，不会返回 AppSecret、OpenID 或完整 Template ID。留空保存表示保留当前值。

基础通知配置完整但费用模板缺失时，手动测试仍会发送运行模板，并把费用模板显示为“未配置”。

如果页面提示“后台保存暂不可用”，重新运行初始化脚本生成加密密钥，然后重建容器：

```bash
bash scripts/init-deploy.sh
docker compose up -d --build
```

## 六、推送规则

即时推送开启时：

- 指标从正常变为告警时发送一次；
- 告警持续期间不重复发送；
- 指标恢复正常时发送一次；
- 服务重启不会重新发送仍处于活动状态的告警；
- 单个接收人失败不影响其他接收人。

关闭即时推送后，系统仍会记录告警状态变化。重新开启不会补发关闭期间已经发生的旧变化。

每日摘要默认关闭。开启后，系统在所选时区每天到达指定时间后依次发送“运行状态”和“费用与流量”两条消息。两条消息独立去重和记录；第一条失败不会阻止第二条。日报使用现有本地数据，不会额外触发 OCI 同步。

## 七、环境变量

也可以直接编辑服务器 `.env`：

```env
MONITOR_WECHAT_ENABLED=true
MONITOR_WECHAT_APP_ID=wx_example_app
MONITOR_WECHAT_APP_SECRET=replace-with-your-app-secret
MONITOR_WECHAT_TEMPLATE_ID=template_example_status
MONITOR_WECHAT_COST_TEMPLATE_ID=template_example_cost
MONITOR_WECHAT_OPEN_IDS=openid_example_1,openid_example_2
MONITOR_WECHAT_IMMEDIATE_PUSH_ENABLED=true
MONITOR_WECHAT_DAILY_SUMMARY_ENABLED=true
MONITOR_WECHAT_DAILY_SUMMARY_TIME=09:00
MONITOR_WECHAT_ZONE_ID=Asia/Shanghai
MONITOR_SETTINGS_ENCRYPTION_KEY=replace-with-base64-32-byte-key
```

`MONITOR_WECHAT_TEMPLATE_ID` 表示运行状态模板，保留该变量名是为了兼容旧版本。`MONITOR_WECHAT_COST_TEMPLATE_ID` 表示费用与流量模板。

`.env` 已被 Git 忽略。不要把真实 AppID、AppSecret、Template ID、OpenID 或加密密钥写入 README、Issue、日志和公开仓库。

修改后执行：

```bash
docker compose up -d --build
```

## 八、从旧单模板配置升级

旧版本只配置 `MONITOR_WECHAT_TEMPLATE_ID` 时，即时告警和恢复仍然可用，不需要更换原 Template ID。升级步骤：

1. 在测试公众号或正式公众号中新增“费用与流量日报”模板。
2. 把第二份 Template ID 填入初始化脚本、`.env` 或设置页面。
3. 开启每日双模板摘要。
4. 在设置页点击“测试两个模板”，确认两项结果都成功。

旧版投递记录会继续显示为“历史测试”或“历史每日摘要”。

## 九、域名与微信消息的边界

`MONITOR_PUBLIC_URL` 只用于浏览器访问、CORS、Cookie 和服务器反向代理配置。微信公众号消息不会读取该值，也不会跳转到该地址。

容器仍然不管理域名或证书。Nginx、OpenResty 或 1Panel 可以继续把域名反向代理到：

```text
http://127.0.0.1:28461
```

## 十、常见错误

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

日志只记录脱敏后的聚合结果，不保存 AppSecret、access token、OpenID 或模板消息正文。
