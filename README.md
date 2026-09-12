<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_foreground.png" width="180" height="180" alt="CaptionGlass：翡翠玻璃质感的双语字幕图标">
</p>

<h1 align="center">CaptionGlass</h1>

<p align="center"><strong>让外语内容，多一层理解。</strong></p>
<p align="center">Android 跨应用双语字幕 · 本机识别与翻译 · 悬浮阅读与回看</p>

<p align="center">
  <picture>
    <source srcset="docs/assets/captionglass-overview.webp" type="image/webp">
    <img src="docs/assets/captionglass-overview.gif" width="700" alt="演示动画：选择语言与模型、开启字幕后切回播放器，悬浮字幕在画面上逐句显示译文与原文，可拖动、切换穿透、上滑回看，并可切换显示方式与外观；全程本机运行">
  </picture>
</p>

CaptionGlass 为允许音频捕获的播放器叠加双语字幕。看外语课程、技术分享或长视频时，继续使用熟悉的播放应用，在同一画面里对照原文与译文。

> 当前为 **0.1.0-dev 实验版本**，需从源码构建。中英及部分多语方向已完成指定设备上的功能检查；自然语音质量、长时间观看和更多机型仍在验证中。

## 为连续观看设计

| 功能 | 使用体验 |
| --- | --- |
| **跨应用悬浮字幕** | 字幕叠加在播放器上方，拖动把手调整位置，横竖屏分别记住位置。 |
| **原文与译文对照** | 原文随识别出现，译文逐步显示；译文在上、原文在下，完成后保留供阅读。 |
| **随时回看，继续跟随** | 滚动查看本次会话最近 200 条记录，回看时保持阅读位置，回到底部继续跟随新字幕。 |
| **按画面选择显示** | 滚动、两段、一段三种方式；黑曜底板或描边外观，支持系统字号缩放与深浅主题。 |
| **触摸穿透** | 轻点把手切换穿透与交互；穿透时操作下方播放器，交互时滚动字幕。 |
| **多语言与模型选择** | 搜索输入和字幕语言，自动匹配兼容组合；识别、翻译模型分别下载、导入和选用。 |
| **本机处理** | 准备好模型后，识别与翻译可离线运行。语音和字幕不上传，不默认保存原始音频。 |

## 开始使用

1. **安装应用。** 按[构建说明](docs/architecture.md#6-构建与开发)生成并安装调试 APK。
2. **选择语言、准备模型。** 首页选择输入语言与字幕语言；进入「设置 → 模型管理」，分别下载兼容的识别和翻译模型，也可[从文件夹导入](docs/model-support.md#下载导入与选用)。下载时保持应用开启。
3. **开启字幕。** 点击首页中央按钮，按系统提示允许音频、悬浮窗和本次捕获。Android 14+ 的捕获授权需共享整个屏幕；应用仅处理播放音频。
4. **切回播放器。** 播放允许捕获音频的内容；拖动把手调整位置，轻点把手切换穿透与滚动交互。
5. **结束观看。** 在应用或前台通知中停止。已显示的记录保留在内存中，开启新会话会清空。

## 设备与语言

需要 **Android 10+、arm64 设备**。翻译默认使用 GPU (Vulkan)，要求 Vulkan 1.2 及所需算子；也可在「设置 → 翻译处理器」选择 GPU (OpenCL)、CPU 或 NPU (Hexagon)。实验性的 OpenCL 适用于提供兼容系统驱动的骁龙 Adreno GPU。实验性的 Hexagon 需兼容骁龙驱动及量化模型（目前提供 Hy-MT2 Q8_0）；联发科 NPU 不在此后端支持范围。模型体积和运行内存需求差异较大，下载前可在型号详情查看占用；设备有 GPU 不等于所有模型都能流畅运行。

| 用途 | 已接入的模型系列 |
| --- | --- |
| 语音识别 | X-ASR、NVIDIA Nemotron、PengChengStarling、Qwen3-ASR、Japanese Zipformer Base、NVIDIA Parakeet |
| 翻译 | Hy-MT2、Hy-MT2 StreamRevise、MiLMMT-46、Murasaki |

可选语言取决于识别与翻译模型的共同能力，首页只展示存在兼容组合的方向。模型可安装与质量达标是两回事；各型号的大小、来源与实测限制见[模型支持表](docs/model-support.md)。

## 开发与许可

项目由 `app`（Android 界面与采集）、`engine`（纯 Kotlin 字幕规则）和 `native`（本地推理）三个模块组成。开发入口：

- [架构与构建](docs/architecture.md)：产品约束、数据流、环境准备与构建命令。
- [模型支持](docs/model-support.md)：独立模型管理、固定文件与验证范围。
- [验收与已知限制](docs/validation.md)：可重复检查、真机证据与后续门槛。

项目采用 [MIT 许可证](LICENSE)。第三方运行库和模型遵循各自许可证，详见[第三方声明](third_party/NOTICE.md)。
