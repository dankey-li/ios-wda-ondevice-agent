# OnDevice Agent (Android)

Android 端 GUI Agent 的实验实现。目标与 iOS WDA Runner 一致：

```text
截图 → 调 LLM → 解析动作 → 执行 tap/swipe/type
```

第一版把 **管理端 + 执行端** 合并为一个 APK，并保持现有 `/agent/*` JSON 契约，便于复用 Manager 逻辑与 Python 工具。

## 0.3.1 下载与验证

- [统一交付 ZIP（Android 源码＋APK＋说明）](../../releases/android/0.3.1/OnDeviceAgentAndroid-0.3.1-bundle.zip)
- [单独下载 APK](../../releases/android/0.3.1/OnDeviceAgentAndroid-0.3.1.apk)
- [ZIP 校验值](../../releases/android/0.3.1/BUNDLE-SHA256SUMS)
- [校验值](../../releases/android/0.3.1/SHA256SUMS)
- [失败通知说明和验证记录](../../docs/android-agent-0.3.1-failure-notify.md)
- [飞书接入及命令](../../docs/android-agent-0.3.0-feishu-remote.md)

保留任务结束后返回／划卡设置和飞书 `/启动` 已有任务、`/执行` 一次性任务等命令。新增按任务配置的失败 Webhook 通知，默认开启，明确区分等待重试、失败和需要人工核对；主动停止／取消不作为失败推送。一次性任务沿用原会话结果回执。

120 项本地测试通过；尚未进行本版真机安装和真实飞书联调。静态检查仍有原有 4 项错误，无新增项。APK 为 debug 签名构建。其他后续模式与 Harness 改造暂缓。

## 当前已实现的范围

- [x] 配置／任务中心／历史三页，配置页顶部显示 APK 版本号与构建号。
- [x] 任务创建、编辑、手动运行、停止及单次／每天／每周定时计划。
- [x] 按任务设置失败重试次数、间隔、有效期、执行时限和可选成功文案。
- [x] 结束后可选保持页面、返回 One Agent 任务页，或划掉指定 App 的最近任务卡片后返回；可选成功后／成功或失败后执行及划动方向。
- [x] 飞书长连接接收 `/帮助`、`/任务`、`/启动`、`/执行`、`/状态`、`/停止`、`/结果`，包含用户／群白名单、消息去重和时效检查。
- [x] `/启动` 使用已有任务配置；`/执行` 下发一次性任务，不保存模板、不定时、不自动重试，结束后操作使用远程设置中的独立默认值。
- [x] 成功通知、默认开启的失败 Webhook 通知，区分等待重试、失败和人工核对；主动停止／取消不发失败通知。
- [x] 无障碍截图与动作执行、指定按钮提交保护、执行历史、截图、耗时和模型解析诊断。
- [x] 本地 `/agent/*` HTTP 接口，默认绑定 127.0.0.1，可通过 ADB 转发访问。

划掉卡片不保证强制结束后台进程。划卡结构不支持、卡片锁定或后台拉起被系统拦截时，历史会记录派发或跳过情况。手机锁定时需要人工解锁。

## 后续功能记录（暂缓，尚未实现）

以下仅记录讨论方向，本轮不开发、不作为当前 APK 能力或交付承诺：

| 状态 | 方向 | 预期行为 |
|---|---|---|
| 暂缓 | 逐步交互模式 | 每次短步骤执行后截图发飞书，暂停等待确认／下一步；补充会话超时、断线和新画面校验 |
| 暂缓 | 参考模式 | 用户提供有序步骤图片、说明和成功标志；Agent 对照当前页面执行，明显不符时暂停；不是永久训练模型 |
| 暂缓 | 标准任务输入 | 将目标、前置检查、步骤、成功证据、禁止动作、异常处理和收尾设置整理为明确字段；当前仍主要使用自由文本 |
| 暂缓 | 业务成功检查点 | 验证并保存日期／账号／任务类型相关证据，进入只收尾阶段，避免完成后重新提交；不只依靠模型计划 |
| 暂缓 | 多页面循环检测 | 补充工作台→打卡页→返回等跨页面重复路径识别；当前同页重复动作保护不能等同此能力 |
| 暂缓 | Harness 对比 | 比较 Pi、Mobilerun、Midscene、AppAgent 等机制，验证后再决定是否接入；当前未迁移 |
| 无排期 | 其他接口与导出 | SSE `/agent/events`、Trace Chat 导出等，不属于当前交付范围 |

## 真机验证清单（由用户后续测试）

以下均未标为通过。120 项本地测试和签名校验不能替代真机验收。

- [ ] 覆盖安装后配置、任务和历史保留，顶部显示 0.3.1（构建 8）。
- [ ] 开启无障碍、截图与通知权限后，手动任务可执行，结束不出现任务中心完成弹窗。
- [ ] “返回 One Agent”打开任务 Tab；“保持页面”不额外导航。
- [ ] 划卡只针对选定 App，方向与本机一致，其他 App 未被清理；锁定卡片／结构不支持时可查到跳过或未确认说明。
- [ ] 单次、每天、每周计划和熄屏唤醒按本机系统权限正常触发。
- [ ] 失败重试的次数、间隔、有效期及停止／取消行为符合设置。
- [ ] Webhook 成功／失败通知及可用截图正常；等待重试与最终失败区分清楚，关闭失败开关生效。
- [ ] 飞书自建应用权限、长连接及消息事件配置完成，`/帮助`、`/状态`、`/任务` 有回复。
- [ ] `/启动 <任务ID>` 执行已有任务，并沿用其结束后和重试设置。
- [ ] `/执行 <内容>` 完成一次后保留历史，不新增模板或计划，结束后操作按远程默认设置执行。
- [ ] 忙碌时第二个启动请求被拒绝且不排队，`/停止` 可停止当前任务或取消最近一轮待重试。
- [ ] 非授权用户、未授权群、群内未 @机器人、过期和重复消息不会重复执行手机动作。
- [ ] 断网重连、应用重启及系统后台限制下的连接／回执表现符合说明，记录异常供后续定位。

建议先用查看信息类测试任务验证通路，再测试实际业务。反馈问题时附版本号、触发方式、任务设置、历史 Run ID 和对应截图即可。

## 本地验证与已知问题

- 0.3.1 已通过 120 项本地测试、APK 构建、签名及 SHA-256 校验。
- 尚未进行本版真机安装、真实飞书／Webhook 联调和厂商系统后台验收。
- lintDebug 仍有原有 4 项错误、28 项警告：两处通知发送权限检查、应用查询参数常量、QUERY_ALL_PACKAGES 权限检查。本轮未修复或屏蔽，详见版本验证记录。

## 环境要求

- JDK 17+（可用 `conda install -c conda-forge openjdk=17`，并 `export JAVA_HOME=$CONDA_PREFIX/lib/jvm`）
- Android SDK（API 35 / build-tools；本机常见路径 `~/Library/Android/sdk`）
- `adb`（`brew install --cask android-platform-tools`）
- 已开启 USB 调试的 Android 手机

首次构建前确认：

```bash
export JAVA_HOME="${JAVA_HOME:-$CONDA_PREFIX/lib/jvm}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

## 构建与安装

```bash
cd apps/OnDeviceAgentAndroid

# 若尚未配置 SDK
# export ANDROID_HOME=$HOME/Library/Android/sdk

./gradlew :app:installDebug
adb shell am start -n com.ondeviceagent.android/.ui.MainActivity
```

安装后：

1. 打开 App，允许通知（前台服务）
2. 系统设置 → 无障碍 → 开启 **OnDevice Agent**（执行端下一步需要）
3. 本机验证 API：

```bash
adb forward tcp:18100 tcp:8100
curl -s http://127.0.0.1:18100/agent/status | python3 -m json.tool
```

> 注意：从电脑访问手机上的 `:8100` 用 `adb forward`；`adb reverse` 是反方向（手机访问电脑）。

## 协议说明

`POST /agent/start` body 与 iOS 相同（snake_case），例如：

```json
{
  "task": "打开设置并截图确认",
  "base_url": "https://your-openai-compatible/v1",
  "model": "your-model",
  "api_key": "...",
  "api_mode": "responses",
  "max_steps": 120
}
```

`GET /agent/status` 额外返回 `"platform":"android"`，其余字段与 iOS 对齐。

## 目录

```text
app/src/main/java/com/ondeviceagent/android/
  model/          # /agent JSON 模型
  runtime/        # AgentRuntime + HTTP + ForegroundService
  automation/     # AccessibilityService（执行端）
  ui/             # Compose 管理端
```
