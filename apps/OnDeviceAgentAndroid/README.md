# OnDevice Agent (Android)

端上 GUI Agent 的 Android 移植骨架。目标与 iOS WDA Runner 一致：

```text
截图 → 调 LLM → 解析动作 → 执行 tap/swipe/type
```

第一版把 **管理端 + 执行端** 合并为一个 APK，并保持现有 `/agent/*` JSON 契约，便于复用 Manager 逻辑与 Python 工具。

## 0.2.2 下载与验证

- [下载 APK](../../releases/android/0.2.2/OnDeviceAgentAndroid-0.2.2.apk)
- [校验值](../../releases/android/0.2.2/SHA256SUMS)
- [开发与验证记录](../../docs/android-agent-0.2.2-version-display.md)

配置页顶部显示 APK 版本号和构建号。本版包含 0.2.1 的任务中心手动运行完成后不弹窗优化；定时运行继续不弹窗，系统通知和其他入口沿用原逻辑。保留按任务配置的重试及执行保障功能。70 项本地测试通过；未进行本版真机验收或安装。APK 为 debug 签名构建。

## 当前功能

已实现：

- 三 Tab：配置 / 任务中心 / 历史
- 全量 LLM 配置字段（api_mode、tokens、timeout、reasoning、自定义 prompt 等）
- 任务中心：模板 CRUD、计划自动触发（AlarmManager 亮屏启动）、手动运行/停止
- Accessibility 截图 + 动作执行
- 真 Agent 循环：截图 → LLM → 解析 → 执行 → Trace 落盘
- HTTP：`/agent/*` + `POST /agent/action` + `/agent/traces`
- 历史：成功/失败筛选、步骤详情与截图

尚未实现（后置）：

- SSE `/agent/events`
- Trace Chat 导出
- 有锁屏密码时的自动解锁（当前面向无锁屏/点按亮屏场景）

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
