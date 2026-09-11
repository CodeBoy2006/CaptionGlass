# CaptionGlass

本地优先的 Android 跨应用双语字幕层。让原文及时出现，让译文稳定成句，让连续观看时的字幕仍然跟得上。

**M1 已接入真实的单路中英识别与翻译。** X-ASR + Hy-MT2 在手机 CPU 上运行，支持播放音频捕获、双语悬浮窗与离线语言包导入。应用没有网络权限，不保存原始音频。当前是 arm64 手机实验版本；短样本验证不代表准确率、所有机型或长时性能已达标。

## 构建与运行

需要 JDK 17、Android SDK 36、Build Tools 35.0.0、NDK 27.1.12297006、CMake 3.22.1。使用 checked-in Gradle wrapper。首次构建下载的 native 源码与运行库都固定 SHA-256；权重与构建产物不入 Git。

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'ndk;27.1.12297006' 'cmake;3.22.1'
bash scripts/prepare-native.sh
./gradlew :engine:check :app:assembleDebug :app:lintDebug
adb -s <测试设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS 可设置 `JAVA_HOME="$(brew --prefix openjdk@17)"`、`ANDROID_HOME="$HOME/Library/Android/sdk"`。也可用不入库的 `local.properties` 配置 SDK。最低 Android 10 / API 29，compile/target API 36；M1 仅打包 arm64-v8a。Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、Compose BOM 2025.12.00 固定版本。

## 准备语言包

```sh
./gradlew downloadModels
```

该开发任务从不可变 revision 下载并校验五个文件到 `artifacts/models/`，总计 1,747,677,166 字节。将这个文件夹复制到手机可由系统文件选择器访问的位置，在应用的“设置 → 导入 / 替换语言包”选择它。文件夹应直接包含三个 `*-480ms.onnx`、`tokens.txt` 与 `Hy-MT2-1.8B-Q4_K_M.gguf`。安装会校验文件大小与 SHA-256，并保留旧包直到新包完整就绪。替换已有语言包时需要额外暂存空间。

选择“英 → 中”或“中 → 英”，点击“开启字幕”，按系统提示允许音频、悬浮窗和本次捕获。捕获时选择整个屏幕，再切换到允许音频捕获的播放器。拖动字幕把手改变位置，点击把手切换触摸穿透。通过应用或前台通知停止；回收完成后才能重新开始。

目标应用可能禁止捕获；静音不证明 DRM。字幕记录只在内存保留最近 200 条，开启新会话会清空。失败、超时、积压和源修订都有明确状态，不隐式转云端。

## 真机验收

核心检查不需要模型或设备。真实推理检查使用另外的测试 APK，包含 macOS 合成语音，没有用户媒体；不能将它当成广泛准确率基准。

```sh
bash scripts/prepare-fixtures.sh  # macOS say + ffmpeg
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
bash scripts/device-check.sh <已明确选择且解锁的测试设备序列号>
```

脚本会安装两个调试 APK、向应用私有目录部署约 1.8 GB 权重与合成音频，并运行双向翻译、1× 语速回放、取消/停止、输出预算、48 kHz 重采样与尾部冲刷检查。厂商系统可能逐次要求确认 USB 安装；验收页面会保持亮屏，结束后解除。跨应用捕获和悬浮窗还需要系统授权后的实际 UI 验收，步骤与实测结果见 [验收文档](docs/validation.md)。

完成上述部署并允许应用显示悬浮窗后，可运行 `adb -s <测试设备序列号> shell am instrument -w -r -e mode latency com.captionglass.app.test/com.captionglass.app.DeviceChecks`。此模式将固定中英音频各重复四次，以 1× 速度进入真实推理，并在末尾加入明确的测试静音让字幕读完；输出语义提交、译文完成、页面发布与阅读等待时间，同时检查所有译文和页面完整性。页面发布时刻不等同于屏幕物理呈现时刻，模拟器成绩不能作为手机性能或人工阅读质量结论。

## 工程地图

```text
app/                 Compose、采集服务、会话 owner、原生悬浮窗、SAF 导入
engine/              纯 Kotlin 原文/队列/阅读规则与可执行回归检查
native/              固定 sherpa JNI + llama.cpp CPU 绑定
models/zh-en.json    M1 实验性语言包：revision / size / SHA-256 / runtime
scripts/             native 准备、合成音频和真机验收
third_party/         依赖许可与说明
PRODUCT.md           产品边界与阶段
docs/architecture.md 实际数据流、所有权、取消、时钟与接入约束
docs/validation.md   验收步骤、实测记录和后续门槛
```

先读 [产品定义](PRODUCT.md) 与 [架构](docs/architecture.md)。保持单路识别、单路翻译、有界队列，确认片段必须得到终态。提交前运行构建与核心检查，不提交 SDK 路径、密钥、权重、音频、APK 或本地 `statusquo.md`。

项目目前为私有开发仓库，尚未选择公开分发的软件许可证。组件遵循各自许可证；公开分发前需完成传递依赖审计。M2 再做持久记录、回看/导出、完整下载管理和长时多机型验收。
