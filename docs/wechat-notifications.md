# 微信公众号通知配置

OCI ARM Monitor 通过微信官方公众号模板消息发送告警、恢复和每日汇总。系统使用两种模板：

- 运行状态模板：即时告警、恢复、运行汇总、实例明细和告警明细；
- 费用与流量模板：OCI 费用、手工费用、本月总费用、月底预测、入站/出站流量和免费额度。

微信模板卡片不能在卡片内部展开详情。因此运行信息会按每张四行拆分：一张汇总卡、实例每 3 台一张卡、告警每 3 项一张卡；费用与流量固定为一张卡。根据数据量，每次日报每位接收人会收到 2 至 8 张消息。

发送前，系统会根据 Template ID 从微信读取模板内容，提取四个真实的 `.DATA` 字段，并按字段在模板中的出现顺序填入消息。字段名不需要固定为 `first`、`item1`、`item2`、`item3`。

微信请求不携带 `url` 或 `miniprogram`，点击消息不会打开监控网站。所有可用明细都直接显示在微信消息中。

当前只接入微信官方公众号接口，不需要 WxPusher、邮件服务或额外推送容器。

## 适用账号

推荐先使用[微信公众平台测试账号](https://mp.weixin.qq.com/debug/cgi-bin/sandbox?t=sandbox/login)。测试账号可以直接创建模板，并让个人微信扫码关注，适合验证完整推送链路。

正式公众号必须具备模板消息接口权限。未认证个人公众号通常没有该权限；这种情况下可以继续使用测试账号，不需要购买第三方服务。

## 一、准备测试账号

1. 打开微信公众平台测试账号页面并扫码登录。
2. 在“测试号信息”中找到 `appID` 和 `appsecret`。
3. 使用需要接收通知的个人微信扫描测试号二维码。
4. 在“用户列表”中复制该微信对应的 OpenID。
5. 在“模板消息接口”中分别新增下面两个模板。每个模板必须正好包含四个不同的 `.DATA` 字段。旧版七字段模板不兼容，升级时也必须重新创建。

## 二、创建运行状态模板

模板标题：

```text
OCI ARM Monitor 运行通知
```

模板内容：

```text
{{first.DATA}}
{{item1.DATA}}
{{item2.DATA}}
{{item3.DATA}}
```

上面的字段名可以直接复制。若测试号自动生成了其他字段名，不要手工改名；系统会按 Template ID 自动识别，字段顺序保持为“标题、第一行、第二行、第三行”即可。

保存后复制第一份 Template ID。它用于：

- 即时告警；
- 告警恢复；
- 每日运行状态；
- 运行模板测试。

运行汇总卡示例：

```text
OCI ARM Monitor 每日运行状态
实例：共 3 台｜运行 2｜停止 1｜其他 0
主机：CPU 18.20%｜内存 62.50%｜磁盘 71.30%
告警：1 项｜同步：成功 2026-07-27 08:02:16（同步完成）
```

实例明细卡示例：

```text
实例明细 1/1
1. arm-app-01：运行中｜CPU 12.30%｜内存 41.20%
2. arm-app-02：运行中｜CPU 26.80%｜内存 53.10%
3. arm-backup：已停止
```

告警明细卡示例：

```text
告警明细 1/1
1. [警告] 磁盘使用率｜磁盘使用率当前 71.30%，阈值 70.00%。
2. [严重] CPU 使用率｜CPU 使用率当前 96.20%，阈值 90.00%。
3. [警告] 内存使用率｜内存使用率当前 86.40%，阈值 85.00%。
```

实例最多展示 10 台，活动告警最多展示 5 项，超出的数量会在最后一张明细卡中说明。缺少实例指标时显示“暂无数据”，不会把缺失数据显示为 `0%`。消息不包含实例 OCID 或 IP 地址。

## 三、创建费用与流量模板

模板标题：

```text
OCI ARM Monitor 费用与流量
```

模板内容：

```text
{{first.DATA}}
{{item1.DATA}}
{{item2.DATA}}
{{item3.DATA}}
```

这份模板同样只要求正好四个不同字段。运行状态和费用流量可以使用不同字段名，系统会分别识别。

保存后复制第二份 Template ID。它用于：

- 每日费用与流量摘要；
- 费用与流量模板测试。

费用与流量卡示例：

```text
OCI ARM Monitor 费用与流量
费用：OCI ¥12.34｜手工 ¥8.00｜总计 ¥20.34｜预测 ¥28.62
流量：入站 123.45 GB｜出站 67.89 GB｜额度 10,000.00 GB
额度：已用 0.68%｜剩余 9,932.11 GB｜同步 2026-07-27 08:02:16
```

出站流量超过免费额度时会显示“超出”。免费额度为 `0` 时显示“未配置”，不会计算使用率和剩余额度。没有成功 OCI 同步时，OCI 费用和流量显示“暂无同步数据”，手工费用仍按本地真实记录显示。

系统按字段在模板内容中的出现顺序映射四行消息，因此不要在同一个模板中增加第五个字段、重复使用同一字段，或继续使用旧版七字段模板。模板字段与当前公众号不匹配时，系统会在发送前停止，并在测试结果中显示脱敏后的原因。

正式服务号的模板字段通常由微信生成为 `thing01`、`time02` 等类型化名称，并有字符数和格式限制。不要把测试号的字段名强行覆盖到正式模板；应选择恰好四个字段且内容类型适合当前消息的模板，再通过设置页测试。测试号自定义模板更适合本项目的详细四行内容。

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
- 同时测试两个模板并分别显示结果，失败时显示脱敏后的具体原因；
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

每日汇总默认关闭。开启后，系统在所选时区每天到达指定时间后先发送运行信息卡片，再发送一张费用与流量卡片。运行信息至少包含一张汇总卡，并根据数据量追加实例和告警明细卡。两种模板独立去重和记录；运行信息发送失败不会阻止费用与流量消息。投递结果中的成功、失败数量按“卡片数 × 接收人数”的实际请求次数统计。日报使用现有本地数据，不会额外触发 OCI 同步。

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

## 八、从旧七字段模板升级

旧版模板使用 `first`、`level`、`metric`、`status`、`content`、`time`、`remark` 七个字段，无法显示新版四行卡片。升级后即使原 Template ID 仍存在，也必须重建模板并替换 ID：

1. 在测试公众号或正式公众号中按本文内容新建“OCI ARM Monitor 运行通知”模板，确认模板中正好有四个不同字段。
2. 再新建“OCI ARM Monitor 费用与流量”模板。
3. 把两份新的 Template ID 填入初始化脚本、`.env` 或设置页面。
4. 重建容器，并在设置页点击“测试两个模板”。
5. 确认两项测试都成功后再开启即时推送和每日汇总。

旧版 SQLite 配置和投递记录会保留；只需要覆盖两份 Template ID，不需要删除数据库。

## 九、域名与微信消息的边界

`MONITOR_PUBLIC_URL` 只用于浏览器访问、CORS、Cookie 和服务器反向代理配置。微信公众号消息不会读取该值，也不会跳转到该地址。

容器仍然不管理域名或证书。Nginx、OpenResty 或 1Panel 可以继续把域名反向代理到：

```text
http://127.0.0.1:28461
```

## 十、常见错误

如果微信卡片只有模板标题而没有四行内容，通常表示发送数据字段与该 Template ID 的真实字段不一致，或服务器仍在运行旧镜像。先更新并重建容器：

```bash
git pull --ff-only
docker compose up -d --build
```

然后在“系统设置 → 通知设置”中重新保存属于当前公众号的两份 Template ID，点击“测试两个模板”。新版会自动读取模板字段；模板不存在或字段数不是四个时，页面会直接显示原因。

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
