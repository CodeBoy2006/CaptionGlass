# CaptionGlass

本地优先的 Android 跨应用双语字幕层。让原文及时出现，让译文稳定成句，让连续观看时的字幕仍然跟得上。

**已接入模型选择器、日语识别与多语种翻译。** 中英可使用 X-ASR；日语等输入使用 PengChengStarling 流式识别，两者共用 Hy-MT2，在本机 CPU 上运行。支持播放音频捕获、双语悬浮窗与独立模型离线导入。应用没有网络权限，不保存原始音频。当前是 arm64 手机实验版本；短样本验证不代表准确率、所有机型或长时性能已达标。

## 构建与运行

需要 JDK 17、Android SDK 36、Build Tools 35.0.0、NDK 27.1.12297006、CMake 3.22.1。使用 checked-in Gradle wrapper。首次构建下载的 native 源码与运行库都固定 SHA-256；权重与构建产物不入 Git。

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'ndk;27.1.12297006' 'cmake;3.22.1'
bash scripts/prepare-native.sh
./gradlew :engine:check :app:assembleDebug :app:lintDebug
adb -s <测试设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS 可设置 `JAVA_HOME="$(brew --prefix openjdk@17)"`、`ANDROID_HOME="$HOME/Library/Android/sdk"`。也可用不入库的 `local.properties` 配置 SDK。最低 Android 10 / API 29，compile/target API 36；M1 仅打包 arm64-v8a。Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、Compose BOM 2025.12.00 固定版本。

## 准备模型与选择语言

```sh
./gradlew downloadModels
# 也可只下载一项：
./gradlew downloadModels -Pmodel=pengcheng-8lang-int8
./gradlew downloadModels -Pmodel=hy-mt2-1.8b-q4-k-m
```

开发任务按不可变 revision 下载、校验文件大小与 SHA-256，输出到 `artifacts/models/<模型 ID>/`。应用保持无网络权限。

| 模型 ID | 输入或输出能力 | 文件与大小 |
| --- | --- | --- |
| `x-asr-zh-en-480ms` | 识别中文、英语 | 4 文件，约 615 MB |
| `pengcheng-8lang-int8` | 识别日语、中文、英语、俄语、越南语、泰语、印尼语、阿拉伯语 | 4 文件，约 339 MB |
| `hy-mt2-1.8b-q4-k-m` | 多语翻译，共用一份权重 | 1 文件，约 1.13 GB |

将所需模型的子文件夹复制到手机。在首页按提示依次导入识别模型与翻译模型，或进入“设置 → 离线模型”分别导入。每次选择直接含该模型文件的子文件夹；模型 ID、文件名与固定哈希见 [清单](models/catalog.json)。导入会暂存、完整校验后原子替换，失败保留该模型旧版本；替换期间需额外暂存空间。模型按 ID 分开保存，两个 `tokens.txt` 不会混用。旧版合并语言包需按新界面重新导入原文件；本次没有保留旧存储格式的兼容路径。

首页左侧选择“听到的语言”，右侧选择“字幕语言”，支持中文名称、原文名称、英文名称或代码搜索。源语言只列出识别模型支持的 8 项；目标语言按 Hy-MT2 官方清单列出 38 个语言／文字变体选项（包含繁体中文和粤语，不表示已逐一验收）。选择日语输入会自动匹配多语识别模型。下方模型行可手动选择兼容模型；切换不会自动下载文件。⇄ 仅在目标语言也支持识别时可用。选择会在重启后保留；授权、导入、运行和回收期间锁定选择，每次会话使用固定快照。

日语采用句末标点或声学 endpoint 提交，避免按英文等待阈值截断句尾否定；无停顿长句会增加等待。汉字、假名和韩文采用同一保守阅读计时，仍需独立校准。新增多语种在界面标为实验支持，模型作者的质量或速度报告不等同于本应用真机验收，证据边界见 [验收文档](docs/validation.md)。

点击中央圆形按钮开启字幕，按系统提示允许音频、悬浮窗和本次捕获（Android 14+ 只提供共享整个屏幕），再切换到允许音频捕获的播放器。拖动字幕上方的把手移动位置；轻点把手在触摸穿透与拦截（绿色描边）之间切换。通过同一按钮或前台通知停止；回收完成后才能重新开始。

目标应用可能禁止捕获；静音不证明 DRM。字幕记录只在内存保留最近 200 条，开启新会话会清空。失败、超时、积压和源修订都有明确状态，不隐式转云端。

## 真机验收

核心检查不需要模型或设备。真实推理检查使用另外的测试 APK，包含 macOS 合成语音，没有用户媒体；不能将它当成广泛准确率基准。

```sh
bash scripts/prepare-fixtures.sh  # macOS say + ffmpeg
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
bash scripts/device-check.sh <已明确选择且解锁的测试设备序列号>
```

脚本会安装两个调试 APK、向应用私有目录部署约 2.1 GB 的三套独立模型文件与中英日合成音频，并运行中英日及法韩阿目标语言翻译、1× 语速回放、取消/停止、输出预算、48 kHz 重采样与尾部冲刷检查。厂商系统可能逐次要求确认 USB 安装；验收页面会保持亮屏，结束后解除。跨应用捕获和悬浮窗还需要系统授权后的实际 UI 验收，步骤与实测结果见 [验收文档](docs/validation.md)。

完成上述部署并允许应用显示悬浮窗后，可运行 `adb -s <测试设备序列号> shell am instrument -w -r -e mode latency com.captionglass.app.test/com.captionglass.app.DeviceChecks`。此模式将固定中英音频各重复四次，以 1× 速度进入真实推理，并在末尾加入明确的测试静音让字幕读完；输出语义提交、译文完成、页面发布与阅读等待时间，同时检查所有译文和页面完整性。页面发布时刻不等同于屏幕物理呈现时刻，模拟器成绩不能作为手机性能或人工阅读质量结论。

## 工程地图

```text
app/                 Compose、采集服务、会话 owner、原生悬浮窗、SAF 导入
engine/              纯 Kotlin 原文/队列/阅读规则与可执行回归检查
native/              固定 sherpa JNI + llama.cpp CPU 绑定
models/catalog.json 固定模型目录：能力 / 文件角色 / revision / size / SHA-256 / runtime
scripts/             native 准备、合成音频和真机验收
third_party/         依赖许可与说明
PRODUCT.md           产品边界与阶段
docs/architecture.md 实际数据流、所有权、取消、时钟与接入约束
docs/validation.md   验收步骤、实测记录和后续门槛
```

先读 [产品定义](PRODUCT.md) 与 [架构](docs/architecture.md)。保持单路识别、单路翻译、有界队列，确认片段必须得到终态。提交前运行构建与核心检查，不提交 SDK 路径、密钥、权重、音频、APK 或本地 `statusquo.md`。

项目目前为私有开发仓库，尚未选择公开分发的软件许可证。组件遵循各自许可证；公开分发前需完成传递依赖审计。M2 再做持久记录、回看/导出、完整下载管理和长时多机型验收。
