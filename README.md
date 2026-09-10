# CaptionGlass

本地优先的 Android 跨应用双语字幕层。让原文及时出现，让译文稳定成句，让连续观看时的字幕仍然跟得上。

**当前为 M0 开发骨架，尚不能识别或翻译真实音频。** 应用可运行固定文本字幕预览，并提供独立的播放音频捕获诊断。没有下载模型、没有假装成功的推理实现、没有网络权限；原始音频不落盘。

## 构建与运行

需要 JDK 17、Android SDK Platform 36、Build Tools 35.0.0。使用 Android Studio 打开项目，或配置 `JAVA_HOME`、`ANDROID_HOME`（也可在不入库的 `local.properties` 设置 `sdk.dir`）。首次构建需要联网获取 Gradle/Maven 依赖，与应用运行时离线无关。

```sh
./gradlew :engine:check :app:assembleDebug :app:lintDebug
adb -s <测试设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS Homebrew JDK 示例：

```sh
export JAVA_HOME="$(brew --prefix openjdk@17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :engine:check :app:assembleDebug :app:lintDebug
```

最低 Android 10 / API 29，compile/target API 36。Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21 与 Compose BOM 2025.12.00 均固定版本。Wrapper jar 和 Gradle 发行包使用官方 SHA-256 验证；[AGP 兼容性](https://developer.android.com/build/releases/agp-8-13-0-release-notes) 说明该版本使用 Gradle 8.13/JDK 17。

应用内：

1. **字幕**：选中英方向并预览。固定文本通过真实的稳定门槛、翻译任务队列和阅读调度；译文本身来自固定样本。
2. **记录**：查看本次演示的内存记录。停止时保留未译完的已确认内容；重新预览会清空，进程结束不持久化。
3. **设置**：查看当前能力，主动开始音频诊断后再请求权限。切换到播放应用检查信号，使用通知或返回应用停止。静音不证明 DRM。

不会操作任意播放器、永久保留系统授权或绕过目标应用的捕获限制。当前没有跨应用悬浮窗，预览显示在应用内部。

## 工程地图

```text
app/                         Compose 页面、回放 ViewModel、播放捕获诊断服务
engine/                      无 Android 依赖的提交/队列/阅读规则与回归检查
native/                      sherpa-onnx 与 llama.cpp 的 Android 接入边界
models/candidates.json       不可安装的候选模型清单，固定 revision/size/SHA-256
PRODUCT.md                   产品理解、边界与未验证假设
docs/architecture.md         数据流、所有权、取消、时间与模型接入约束
docs/validation.md           M0–M3 顺序与验收标准
.github/workflows/android.yml 构建、核心回归、lint、debug APK artifact
```

先读 [产品定义](PRODUCT.md)，再读 [架构](docs/architecture.md) 和 [验收计划](docs/validation.md)。当前基线候选是 X-ASR 中英 480 ms + Hy-MT2-1.8B Q4_K_M；尚未验证完整的模型/原生运行库/手机组合。

## 贡献约定

变更应保持单路识别、单路翻译、有界队列；已确认内容不能静默丢弃，异步结果必须验证完整身份。注释使用清晰英文，面向产品的文案不暴露推理参数。

核心逻辑至少补充一个能失败的回归检查；提交前运行上面的验证命令。不要提交 SDK 路径、密钥、模型权重、用户音频或签名文件。`statusquo.md` 是本地工作日志，必须保持忽略。原生库、模型包、持久化和悬浮窗按真实接入需要逐项加入，不提前引入多后端框架。

项目目前为私有开发仓库，尚未选择对外分发的软件许可证。模型和未来集成的原生依赖各自遵循其许可证；候选清单中的许可标识不替代发布前的完整许可文件检查。
