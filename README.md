# CaptionGlass

本地优先的 Android 跨应用双语字幕层。让原文及时出现，让译文稳定成句，让连续观看时的字幕仍然跟得上。

**已接入模型管理、Nemotron 日语流式识别与多语种翻译。** 中英可使用 X-ASR；Nemotron 3.5 支持日语等 18 种输入，PengChengStarling 保留八语种支持。识别模型均使用 CPU，共用的 Hy-MT2 使用 Vulkan GPU 翻译。需要支持 Vulkan 1.2 及所需计算能力的 arm64 设备；不支持时明确报告模型加载失败，不自动改用纯 CPU 翻译。支持播放音频捕获、双语悬浮窗、应用内模型下载与文件夹导入。仅下载模型时联网，语音与文字不上传，不保存原始音频。当前为实验版本，设备兼容性、加速收益和长时表现以 [验收记录](docs/validation.md) 为准。

## 构建与运行

需要 JDK 17、Android SDK 36、Build Tools 35.0.0、NDK 27.1.12297006、CMake 3.22.1，以及宿主机 Clang（macOS Command Line Tools／Linux clang）和 Python 3。先设置 `JAVA_HOME` 和 `ANDROID_HOME`。使用 checked-in Gradle wrapper。准备脚本下载 SHA-256 固定的 native 源码、运行库和 Khronos 头文件，构建 shaderc v2025.3 及其 matched DEPS，并应用仓库内的 Vulkan 清理补丁。首次准备需要编译宿主 shader 工具；NDK 自带旧版 glslc 不支持所需的协作矩阵 shader。权重与构建产物不入 Git。

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
./gradlew downloadModels -Pmodel=nemotron-3.5-560ms-int8
./gradlew downloadModels -Pmodel=hy-mt2-1.8b-q4-k-m
```

开发任务按不可变 revision 下载、校验文件大小与 SHA-256，输出到 `artifacts/models/<模型 ID>/`。应用内下载使用同一份固定清单。

| 模型 ID | 输入或输出能力 | 文件与大小 |
| --- | --- | --- |
| `x-asr-zh-en-480ms` | 识别中文、英语 | 4 文件，约 615 MB |
| `nemotron-3.5-560ms-int8` | 识别日语等 18 种语言；CPU、固定输入语言、560 ms 流式分块 | 4 文件，约 683 MB |
| `pengcheng-8lang-int8` | 识别日语、中文、英语、俄语、越南语、泰语、印尼语、阿拉伯语 | 4 文件，约 339 MB |
| `hy-mt2-1.8b-q4-k-m` | 多语翻译，共用一份权重 | 1 文件，约 1.13 GB |

在“设置 → 模型管理”准备模型；首页的“准备识别模型／准备翻译模型”也会进入这里。当前字幕所需的识别模型与共用翻译模型排在最前，下面列出其他识别模型。每项显示用途、语言数量、大小、安装状态；已安装且兼容的识别模型可直接选择。

- **下载：** 点击“下载模型”，从固定的 Hugging Face 文件地址获取。显示下载／校验进度，可取消；完成大小与 SHA-256 校验后才启用。下载期间保持应用开启；本版不保证进程被系统关闭后的后台续传，重开会清理未完成的临时文件，重试从头下载。
- **导入：** 将开发任务下载的子文件夹复制到手机，点击“导入模型”，查看所需文件名后选择直接包含这些文件的文件夹。文件名、大小与哈希必须匹配 [清单](models/catalog.json)。
- **校验与删除：** 每项的更多菜单提供详情、重新校验和删除。重新校验失败或被取消后，需要再次校验或重新安装；删除当前必需模型前会提示无法开启字幕，并要求确认。共用的 Hy-MT2 只保存一份。

下载／导入先检查可分配空间，并保留原模型直到新文件完整校验、原子激活。替换需额外一份模型暂存空间及少量余量。失败或取消不覆盖旧模型；进程中断会恢复旧模型或保留已经激活的新模型。导入、下载、校验、删除与字幕会话互斥，不会在推理时修改模型文件。

首页左侧选择“听到的语言”，右侧选择“字幕语言”，支持中文名称、原文名称、英文名称或代码搜索。源语言只列出识别模型支持的 20 项；目标语言按 Hy-MT2 官方清单列出 38 个语言／文字变体选项（包含繁体中文和粤语，不表示已逐一验收）。当前模型不支持新输入语言时自动匹配：从中英模型切到日语会匹配 Nemotron；已有兼容模型选择会保留。下方模型行可手动选择兼容模型；切换不会自动下载文件。⇄ 仅在目标语言也支持识别时可用。选择会在重启后保留；授权、模型管理操作、运行和回收期间锁定选择，每次会话使用固定快照。

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

脚本会安装两个调试 APK、向应用私有目录部署约 2.77 GB 的四套独立模型文件与中英日合成音频，并运行目录规则、Nemotron 与原有中英日识别、法韩阿目标翻译、1× 语速回放、取消/停止、输出预算、48 kHz 重采样与尾部冲刷检查。厂商系统可能逐次要求确认 USB 安装；验收页面会保持亮屏，结束后解除。跨应用捕获和悬浮窗还需要系统授权后的实际 UI 验收，步骤与实测结果见 [验收文档](docs/validation.md)。

模型管理的轻量回归可独立运行：

```sh
adb -s <测试设备序列号> shell am instrument -w -r -e mode models com.captionglass.app.test/com.captionglass.app.DeviceChecks
# 增加真实的小文件 HTTPS 下载和 HTTP 错误检查：
adb -s <测试设备序列号> shell am instrument -w -r -e mode model-network com.captionglass.app.test/com.captionglass.app.DeviceChecks
```

这些检查只使用独立的 `manager-check` 测试目录，覆盖完整安装、损坏／截断／超长文件拒绝、取消、旧模型保留、重新校验失效、激活恢复和删除。

完成上述部署并允许应用显示悬浮窗后，可运行 `adb -s <测试设备序列号> shell am instrument -w -r -e mode latency com.captionglass.app.test/com.captionglass.app.DeviceChecks`。此模式将固定中英音频各重复四次，以 1× 速度进入真实推理，并在末尾加入明确的测试静音让字幕读完；输出语义提交、译文完成、页面发布与阅读等待时间，同时检查所有译文和页面完整性。页面发布时刻不等同于屏幕物理呈现时刻，模拟器成绩不能作为手机性能或人工阅读质量结论。

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

项目目前为私有开发仓库，尚未选择公开分发的软件许可证。组件遵循各自许可证；公开分发前需完成传递依赖审计。M2 再做持久记录、回看/导出、跨进程断点续传和长时多机型验收。
