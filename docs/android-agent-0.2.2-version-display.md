# Android Agent 0.2.2：配置页版本标识与 APK

日期：2026-10-29。版本：0.2.2，versionCode：5。

## 修改与范围

- 基于分支 `android/0.2.0-configurable-retry` 最新提交 `4843379` 构建。
- 保留 0.2.1 的任务中心手动运行结束后不弹窗优化及新增回归测试；定时任务继续不弹窗。
- 配置页面顶部显示“版本 0.2.2（构建 5）”，通过 `BuildConfig.VERSION_NAME` 和 `BuildConfig.VERSION_CODE` 自动读取，后续版本无需另行维护界面文案。
- 本轮源码仅修改版本配置和版本号显示。系统完成通知、飞书通知、配置页及 HTTP 入口提醒沿用既有逻辑。

## 验证

- `:app:testDebugUnitTest :app:assembleDebug` 通过，70 项测试，0 失败、0 错误、0 跳过。
- 新增的任务中心手动运行成功后不弹窗测试已通过；未新增针对纯版本文案的重复实现测试。
- APK 元数据确认：applicationId `com.ondeviceagent.android`，versionName `0.2.2`，versionCode `5`。
- APK 签名验证通过，与 0.2.0 使用相同 debug 签名，证书 SHA-256：`25378f4301c0919bb9b455331ed1f86a75a74ea3be5e6f06515959160b65b20b`。
- `git diff --check` 通过。
- lintDebug 仍有原有 4 项错误（RunCompletionNotifier、TaskAlarmScheduler 的 MissingPermission；AppLauncher 的 WrongConstant；Manifest 的 QueryAllPackagesPermission），28 项警告，无新增项。本轮未修复这些问题。
- 未进行本版真机安装、界面观察或系统定时验收，不能将本地测试等同真机验证。

## 文件

- [下载 APK](../releases/android/0.2.2/OnDeviceAgentAndroid-0.2.2.apk)
- [SHA-256 校验值](../releases/android/0.2.2/SHA256SUMS)
- [机器可读验证记录](../releases/android/0.2.2/verification.json)

APK SHA-256：`1a47228a948a3104adbeb978d6be25cac12258ff6632c198d17cffed18b19493`。
