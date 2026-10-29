# One Agent Android 0.3.1 统一交付包

版本：0.3.1（构建 8），包名：com.ondeviceagent.android。

代码来源提交：dbd85590a70cd77ff9b402521a1a42f243dbf810。该提交的 APK 与源码一并交付；本次只补充文档和打包，不修改执行逻辑。

## 内容

- apps/OnDeviceAgentAndroid/：Android 源码、Gradle Wrapper 和测试，不含本机配置和构建缓存。
- releases/android/0.3.1/OnDeviceAgentAndroid-0.3.1.apk：已构建 APK。
- releases/android/0.3.1/verification.json、SHA256SUMS：本地验证记录和 APK 校验值。
- docs/：飞书接入、结束后操作和失败通知说明。
- BUNDLE-MANIFEST.json、CONTENTS-SHA256SUMS：ZIP 内文件清单和校验值。

APK 的 SHA-256：8135862b617609702952712587562aec5a530d965c71e8c67cd38f74b13fbd03。

ZIP 本身的校验值见仓库同目录 BUNDLE-SHA256SUMS。

## 安装

解压后进入交付包根目录。如使用已授权 USB 调试的电脑，执行：

```bash
adb install -r releases/android/0.3.1/OnDeviceAgentAndroid-0.3.1.apk
adb shell am start -n com.ondeviceagent.android/.ui.MainActivity
```

也可将 APK 传到手机上安装。当前是 debug 签名，与之前交付证书一致；不要先卸载旧版，卸载会清除应用数据。顶部应显示版本 0.3.1（构建 8）。

## 验证与后续

本地 120 项测试、构建和签名校验通过；尚未真机安装及真实飞书联调。原有 4 项静态检查错误和 28 项警告仍保留。

已实现功能、后续暂缓方向和逐项真机验收清单，统一见 apps/OnDeviceAgentAndroid/README.md。飞书首次配置见 docs/android-agent-0.3.0-feishu-remote.md。

本轮不开发逐步交互、图片参考流程、标准工作流输入、业务成功检查点或 Harness 迁移。
