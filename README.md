# CaptionGlass

本地优先的 Android 跨应用双语字幕层。让原文及时出现，让译文稳定成句，让连续观看时的字幕仍然跟得上。

**支持按系列管理、独立下载和切换识别／翻译模型。** 识别包括 X-ASR、Nemotron、PengChengStarling、Qwen3-ASR、Japanese Zipformer Base 与 NVIDIA Parakeet；翻译包括 Hy-MT2、StreamRevise v4、MiLMMT-46、Murasaki v0.2/v0.3。ASR 固定使用 CPU，MT 使用 Vulkan GPU。需要支持 Vulkan 1.2 及所需算子的 arm64 设备，不自动切到云端或纯 CPU 翻译。新增型号、来源、限制和实测范围见 [模型支持表](docs/model-support.md)。当前为实验版本；权重可安装不代表实时性能或广泛质量达标。

## 构建与运行

需要 JDK 17、Android SDK 36、Build Tools 35.0.0、NDK 27.1.12297006、CMake 3.22.1，以及宿主机 Clang（macOS Command Line Tools／Linux clang）和 Python 3。先设置 `JAVA_HOME` 和 `ANDROID_HOME`。使用 checked-in Gradle wrapper。准备脚本下载 SHA-256 固定的 native 源码、ONNX Runtime 和 Khronos 头文件，构建 shaderc v2025.3 及其 matched DEPS，并应用仓库内的 Vulkan 清理与 Qwen 完整性补丁。sherpa JNI 从固定源码构建；LiteRT 2.2.0 通过 Gradle 随 APK 打包。首次准备需要编译宿主 shader 工具；NDK 自带旧版 glslc 不支持所需的协作矩阵 shader。权重与构建产物不入 Git。

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'ndk;27.1.12297006' 'cmake;3.22.1'
bash scripts/prepare-native.sh
./gradlew :engine:check :app:assembleDebug :app:lintDebug
adb -s <测试设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS 可设置 `JAVA_HOME="$(brew --prefix openjdk@17)"`、`ANDROID_HOME="$HOME/Library/Android/sdk"`。也可用不入库的 `local.properties` 配置 SDK。最低 Android 10 / API 29，compile/target API 36；M1 仅打包 arm64-v8a。Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、Compose BOM 2025.12.00 固定版本。

## 准备模型与选择语言

```sh
./gradlew downloadModels  # 默认仅下载 X-ASR 与 Hy-MT2 基础组合
# 其他型号按需下载，完整 ID 见模型支持表：
./gradlew downloadModels -Pmodel=nemotron-3.5-560ms-int8
./gradlew downloadModels -Pmodel=qwen3-asr-0.6b-int8
./gradlew downloadModels -Pmodel=milmmt-46-1b-v1-q4-k-m
```

开发任务按不可变 revision 下载、校验文件大小与 SHA-256，输出到 `artifacts/models/<模型 ID>/`。应用内下载使用同一份固定清单。

在“设置 → 模型管理”按语音识别、翻译浏览系列；同系列只有一行，进入后分别管理每个型号。当前选择的系列优先显示。各型号独立保存文件、校验状态和安装占用，每个会话只加载一个 ASR 和一个 MT。

- **下载：** 型号行点击“下载”。完成固定大小与 SHA-256 校验后才就绪，可取消；外层仍显示进行中的操作。更多菜单可重新下载，损坏文件无需先删除。下载期间保持应用开启，进程中断后从头重试。
- **导入：** 更多菜单选择“导入模型”，选择 `artifacts/models/<模型 ID>/` 复制到手机后的文件夹。所需文件名见详情，全部平铺；Qwen tokenizer 三文件也位于包根目录，VAD 固定名为 `silero-vad.onnx`。
- **选用：** 安装且兼容当前语言方向的型号可选用。识别和翻译各有独立偏好；选用不会自动下载。更多菜单提供校验、来源、详情和删除；删除前确认，其他型号不受影响。

下载／导入先检查可分配空间，并保留原模型直到新文件完整校验、原子激活。替换需额外一份模型暂存空间及少量余量。失败或取消不覆盖旧模型；进程中断会恢复旧模型或保留已经激活的新模型。导入、下载、校验、删除与字幕会话互斥，不会在推理时修改模型文件。

首页选择输入和字幕语言，支持名称或代码搜索。能力按 ASR 输入、MT 源语言和目标语言的交集过滤；不存在可用组合的方向不显示。当前模型不兼容时自动匹配目录中兼容的模型，兼容的既有选择保留。首页模型行进入管理页；交换仅在双向均有模型时可用。两个模型 ID 和语言方向随重启保存，授权、安装、运行和回收期间锁定，每次会话使用固定快照。

字幕保留为可滚动的双语记录，译文逐步显示并在完成后稳定保留；进行中的译文与对应原文使用轻微色差。回看时保持阅读位置，回到底部继续跟随。悬浮框默认不透明且可滚动，把手可切换穿透。分段 ASR 使用重叠窗口自动接续长语音，并提供可修订原文；流式 ASR 不再因上游默认 20 秒时长规则强行结束句子。日语仍采用稳定句末标点或声学 endpoint 提交，避免截断句尾否定；无标点、无停顿长句仍会增加翻译等待。新增型号的实验性和质量限制位于模型详情，实测范围见 [模型支持表](docs/model-support.md)。

点击中央圆形按钮开启字幕，按系统提示允许音频、悬浮窗和本次捕获（Android 14+ 只提供共享整个屏幕），再切换到允许音频捕获的播放器。拖动字幕上方的把手移动位置；轻点把手在触摸穿透与滚动（绿色描边）之间切换。通过同一按钮或前台通知停止；回收完成后才能重新开始。

目标应用可能禁止捕获；静音不证明 DRM。字幕记录只在内存保留最近 200 条，开启新会话会清空。失败、超时、积压和源修订都有明确状态，不隐式转云端。

## 真机验收

核心检查不需要模型或设备。真实推理检查使用另外的测试 APK，包含 macOS 合成语音，没有用户媒体；不能将它当成广泛准确率基准。

```sh
./gradlew downloadModels -Pmodel=nemotron-3.5-560ms-int8
./gradlew downloadModels -Pmodel=pengcheng-8lang-int8
bash scripts/prepare-fixtures.sh  # macOS say + ffmpeg
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
bash scripts/device-check.sh <已明确选择且解锁的测试设备序列号>
```

基础验收脚本会安装两个调试 APK、向应用私有目录部署约 2.77 GB 的四套独立模型文件与中英日合成音频，并运行目录规则、Nemotron 与原有中英日识别、法韩阿目标翻译、1× 语速回放、取消/停止、输出预算、48 kHz 重采样与尾部冲刷检查。厂商系统可能逐次要求确认 USB 安装；验收页面会保持亮屏，结束后解除。跨应用捕获和悬浮窗还需要系统授权后的实际 UI 验收，步骤与实测结果见 [验收文档](docs/validation.md)。

单型号验收先将对应 `artifacts/models/<ID>` 平铺目录部署到应用的 `files/models/<ID>`，并部署固定语音夹具，再运行：

```sh
adb -s <测试设备序列号> shell am instrument -w -r -e mode adapter -e model japanese-zipformer-base-fp16 com.captionglass.app.test/com.captionglass.app.DeviceChecks
```

模型管理的轻量回归可独立运行：

```sh
adb -s <测试设备序列号> shell am instrument -w -r -e mode models com.captionglass.app.test/com.captionglass.app.DeviceChecks
# 增加真实的小文件 HTTPS 下载和 HTTP 错误检查：
adb -s <测试设备序列号> shell am instrument -w -r -e mode model-network com.captionglass.app.test/com.captionglass.app.DeviceChecks
```

这些检查只使用独立的 `manager-check` 测试目录，覆盖完整安装、损坏／截断／超长文件拒绝、取消、旧模型保留、重新校验失效、激活恢复和删除。

完成上述部署并允许应用显示悬浮窗后，可运行阅读和连续语音检查：

```sh
adb -s <测试设备序列号> shell am instrument -w -r -e mode reading com.captionglass.app.test/com.captionglass.app.DeviceChecks
adb -s <测试设备序列号> shell am instrument -w -r -e mode continuity -e model qwen3-asr-0.6b-int8 -e mt hy-mt2-streamrevise-v4-q4-k-m com.captionglass.app.test/com.captionglass.app.DeviceChecks
```

`reading` 使用实际英译中输出检查滚动回看、恢复跟随、无障碍滚动和 30 秒保留。`continuity` 将固定语音重复四次，以 1× 速度进入所选模型，核对重复内容和自然结束／24 秒提前停止的结果完整性；需事先部署对应型号。两者报告的首次原文／增量译文时间均为状态发布时间，不是屏幕物理呈现、自然语音质量或长时性能结论。

## 工程地图

```text
app/                 Compose、采集服务、会话 owner、原生悬浮窗、模型下载与 SAF 导入
engine/              纯 Kotlin 原文/队列/阅读规则与可执行回归检查
native/              固定 sherpa CPU JNI + llama.cpp Vulkan 翻译绑定
models/catalog.json 固定模型目录：能力 / 文件角色 / revision / size / SHA-256 / runtime
scripts/             native 准备、合成音频和真机验收
third_party/         依赖许可与说明
PRODUCT.md           产品边界与阶段
docs/architecture.md 实际数据流、所有权、取消、时钟与接入约束
docs/validation.md   验收步骤、实测记录和后续门槛
```

先读 [产品定义](PRODUCT.md) 与 [架构](docs/architecture.md)。保持单路识别、单路翻译、有界队列，确认片段必须得到终态。提交前运行构建与核心检查，不提交 SDK 路径、密钥、权重、音频、APK 或本地 `statusquo.md`。

项目目前为私有开发仓库，尚未选择公开分发的软件许可证。组件遵循各自许可证；公开分发前需完成传递依赖审计。M2 再做持久记录与导出、跨进程断点续传和长时多机型验收。
