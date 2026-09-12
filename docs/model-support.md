# 模型管理与支持

元数据核对日期：2026-09-11。`models/catalog.json` 是唯一下载清单，固定所有文件的 revision、字节数、SHA-256、角色和运行库版本。各型号独立保存；不会因系列合并而一起下载或删除。

## 下载、导入与选用

「设置 → 模型管理」按语音识别、翻译列出系列；进入系列后管理独立型号。每次会话只使用一个 ASR 和一个 MT，停止字幕后才能管理或切换。

- **下载：** 型号行点击「下载」，校验完成后就绪。可取消或通过更多菜单重新下载。下载期间保持应用开启，进程中断后从头重试。
- **导入：** 更多菜单选择「导入模型」，选择直接包含全部所需文件的文件夹。文件名见型号详情；文件平铺，Qwen tokenizer 三文件也在包根目录，VAD 固定名为 `silero-vad.onnx`。
- **选用：** 安装且兼容当前语言方向后可选用，选择本身不会下载。识别与翻译偏好独立保存；语言更改时保留兼容选择，否则自动匹配。
- **维护：** 更多菜单提供校验、来源、详情和删除。删除需确认，只影响当前型号；损坏文件可直接重新下载。

安装前检查空间，替换需额外一份模型暂存空间及少量余量。新文件完整通过尺寸与 SHA-256 校验后才激活；失败、取消或中断不会用半成品覆盖旧模型。安装与字幕会话互斥。

开发机可下载同一份固定清单，再将输出文件夹复制到手机导入：

```sh
./gradlew downloadModels  # 默认 X-ASR 与 Hy-MT2 基础组合
./gradlew downloadModels -Pmodel=nemotron-3.5-560ms-int8
./gradlew downloadModels -Pmodel=qwen3-asr-0.6b-int8
./gradlew downloadModels -Pmodel=milmmt-46-1b-v1-q4-k-m
```

输出为 `artifacts/models/<模型 ID>/`，使用不可变 revision 并检查字节数和 SHA-256。开发环境见[构建说明](architecture.md#6-构建与开发)。

## 已接入型号

| 模型 ID | 型号 | 下载大小 | 适配器 |
| --- | --- | --- | --- |
| `x-asr-zh-en-480ms` | X-ASR · 中英 · 480 ms | 0.61 GB | online-transducer |
| `nemotron-3.5-560ms-int8` | NVIDIA Nemotron 3.5 · 0.6B · INT8 | 0.68 GB | online-transducer |
| `pengcheng-8lang-int8` | PengChengStarling · 八语种 · INT8 | 0.34 GB | online-transducer |
| `hy-mt2-1.8b-q4-k-m` | Hy-MT2 1.8B · Q4_K_M | 1.13 GB | hy-mt2 |
| `hy-mt2-streamrevise-v4-q4-k-m` | Hy-MT2 StreamRevise v4 · 1.8B · Q4_K_M | 1.07 GB | stream-revise |
| `milmmt-46-1b-v1-q4-k-m` | MiLMMT-46 1B · v1.0 · Q4_K_M | 0.81 GB | milmmt |
| `milmmt-46-4b-v1-q4-k-m` | MiLMMT-46 4B · v1.0 · Q4_K_M | 2.49 GB | milmmt |
| `milmmt-46-12b-v1-q4-k-m` | MiLMMT-46 12B · v1.0 · Q4_K_M | 7.30 GB | milmmt |
| `murasaki-4b-v0.3-q4-k-m` | Murasaki v0.3 · 4B · Q4_K_M | 2.72 GB | murasaki |
| `murasaki-8b-v0.2` | Murasaki v0.2 · 8B · Q6_K | 6.73 GB | murasaki |
| `murasaki-14b-v0.2` | Murasaki v0.2 · 14B · IQ4_XS | 8.11 GB | murasaki |
| `qwen3-asr-0.6b-int8` | Qwen3-ASR 0.6B · INT8 | 0.99 GB | qwen3-asr |
| `qwen3-asr-1.7b-int8` | Qwen3-ASR 1.7B · INT8 | 2.41 GB | qwen3-asr |
| `parakeet-tdt-0.6b-v2-int8` | NVIDIA Parakeet TDT · 0.6B · v2 · INT8 | 0.66 GB | offline-transducer |
| `parakeet-tdt-0.6b-v3-int8` | NVIDIA Parakeet TDT · 0.6B · v3 · INT8 | 0.67 GB | offline-transducer |
| `parakeet-ja-ctc-int8` | NVIDIA Parakeet 日语 CTC · 0.6B · INT8 | 0.66 GB | offline-ctc |
| `japanese-zipformer-base-fp16` | Japanese Zipformer Base · 96.5M · FP16 | 0.20 GB | japanese-zipformer |

Qwen3-ASR、Parakeet 和 Japanese Zipformer 为分段识别，CPU 单线程；约 4 秒提供可修订原文，约 8 秒窗口保留 2 秒重叠自动续接，停顿或 EOF 确认剩余尾句。所有 MT 使用 Vulkan GPU，不静默回退 CPU。Murasaki 仅开放日语→简体中文；StreamRevise 仅中英日；MiLMMT 使用其 46 语言表。ASR 语言与 MT 源/目标能力分别相交，不将识别的 30/25 种能力直接等同于所有字幕方向。

大参数、大量化型号是明确的按需选项，不根据设备内存猜测其速度或可持续使用能力。显示的是文件大小，不是峰值 RAM/VRAM 承诺；加载失败沿既有错误路径返回。

## 来源与格式核对

- [Murasaki 官方项目](https://github.com/soundstarrain/Murasaki-project)给出 v0.2 8B/14B、v0.3 4B 矩阵。[官方 short 提示](https://github.com/soundstarrain/Murasaki-Translator/blob/main/middleware/murasaki_translator/core/prompt.py)用于字幕；所选三个 GGUF 的头部均实查为 Qwen3 与 ChatML 模板，关闭思考后仍检查响应是否含未闭合思考。官方 HF 权重端点在核对时返回 401，未推断其原因；v0.3 使用 [mradermacher 转换](https://huggingface.co/mradermacher/Murasaki-4B-v0.3-GGUF)，v0.2 使用 [shoutmon 备份](https://huggingface.co/shoutmon/Murasaki-Backup)。8B Q6_K 的 SHA-256 与[原始文件指针](https://huggingface.co/Murasaki-Project/Murasaki-8B-v0.2-GGUF/blob/cc1fd468bf10dd9f9fbeb2f0f2c5f8e19b772684/Murasaki-8B-v0.2-Q6_K.gguf)一致；14B 仅确认备份自身的固定哈希、架构和模板，未重新获得上游原件比对。权重许可 CC-BY-NC-SA-4.0。
- [febilly StreamRevise v4 GGUF](https://huggingface.co/febilly/Hy-MT2-1.8B-StreamRevise-v4-GGUF)为作者发布，hunyuan-dense，含 EOM=120020。使用 greedy 及作者的 cold-start/Recent source utterances 格式。当前仍只翻译已确认语义片段，不开放每次 ASR 临时修订都重译的模式；译文按完整 UTF-8 字符增量显示，正常 EOS 后才确认为完成。空译文给出失败终态，不丢弃已确认片段。
- [Xiaomi MiLMMT-46](https://github.com/xiaomi-research/gemmax)的 v1.0 1B/4B/12B 采用 Gemma3，使用 [mradermacher GGUF](https://huggingface.co/mradermacher/MiLMMT-46-1B-v1.0-GGUF)。严格使用 `Translate this from … to …:` 的裸 completion 和官方英文名称，不加 BOS、Gemma 聊天包装或额外历史。许可是 Gemma；未将 Pretrain 权重当作翻译模型。
- [Qwen 官方 0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)与[sherpa 导出说明](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html)确定接口。下载使用 [pantinor 0.6B](https://huggingface.co/pantinor/sherpa-onnx-qwen3-asr-0.6b-int8)和[thieunv 1.7B](https://huggingface.co/thieunv/sherpa-onnx-qwen3-asr-1.7B-int8)的匹配 ONNX/tokenizer，均标为社区转换。强制语言使用完整英文名，Filipino 映射为应用 `tl`。本地 sherpa 补丁通过 stream option 暴露正常 EOS；上下文/生成上限、重复坍塌、早退都拒绝作为完整原文。
- [Japanese Zipformer Base](https://huggingface.co/reazon-research/japanese-zipformer-base-k2-rs35kh-bpe)为 96.5M raw-waveform CTC，并非旧版 ReazonSpeech transducer。采用 [LiteRT 社区导出](https://huggingface.co/litert-community/japanese-zipformer-base-LiteRT)，LiteRT 2.2.0 Interpreter CPU；固定 16 秒输入包含两侧各 0.5 秒 padding、四级 mask，blank=0 的 CTC 解码。不会把社区 GPU 测速当作本应用 CPU 表现。
- [NVIDIA Parakeet TDT v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)是 25 种欧洲语言，v2 是英语；[日语 TDT-CTC](https://huggingface.co/nvidia/parakeet-tdt_ctc-0.6b-ja)这里使用 CTC 导出。sherpa 的 `nemo_transducer`、`nemo` 配置分开，不声称 v3 支持日语或中文。三个 matched INT8 导出来自 csukuangfj。
- [Silero VAD 固定 ONNX](https://huggingface.co/onnx-community/silero-vad)作为每个分段包中的辅助数据。概率只决定停顿，不丢弃非零音频；48 kHz 使用固定 sherpa 的有状态 LinearResample，不采用抽样丢点。

## 验证记录

在 vivo V2415A / Android API 36 执行真实推理，输入为仓库固定的 macOS 合成中英日语音，不是广泛语音质量基准。

| 检查 | 结果 |
| --- | --- |
| `:engine:check :app:assembleDebug :app:lintDebug`，以及测试 APK | 通过；最终使用 `--no-watch-fs` 避免宿主 Gradle VFS 留存旧快照 |
| 页面 | 深色主题、浅色 130% 字号的系列列表、独立型号状态及更多菜单已检查；原设备主题与字号已恢复 |
| 管理器与目录 | 系列合并、两个独立模型选择、能力匹配通过；安装、损坏/截断/超长拒绝、取消、旧包保留、重校验与删除通过 |
| Qwen3-ASR 0.6B INT8 | 日语三句完整识别；EOS 标志通过；强制 1 token 预算被明确判定为未完成 |
| Parakeet 日语 CTC INT8 | 日语三句内容识别，无标点 |
| Parakeet TDT v3 INT8 | 英语内容、48 kHz 有状态重采样、停止尾部通过 |
| MiLMMT 1B / StreamRevise v4 / Murasaki v0.3 4B | Vulkan 日→中推理通过；返回完整译文，不显示思考内容 |
| Qwen + StreamRevise，1× 完整字幕链路 | 三句日语及译文完整；原文首显约 12.35 s、译文约 14.95 s；自然结束与 4.5 s 提前停止均为 confirmed=outcomes=1 |
| Parakeet 日语 + MiLMMT，1× 完整字幕链路 | 三句及译文完整；原文首显约 9.81 s、译文约 12.99 s；自然结束与提前停止的终态计数一致 |
| 原有 Hy-MT2 / X-ASR 回归 | 多目标翻译、上下文、取消、超时、输出预算、取消后复用；48 kHz 和停止尾部通过 |
| Japanese Zipformer Base | 运行成功，但相同三句样本仅得到「はいい天気です」，有明显漏句，质量未通过；详情和型号行提示暂不建议连续字幕 |
| 手机 HTTPS 下载 | 开启代理后重试通过：实际固定文件下载与哈希校验成功，HTTP 错误仍保留已安装模型。此前直连检查失败，安装器逻辑未修改 |

Qwen/Parakeet 的数值是从合成音频开始到本应用原文/译文发布的单次观察，不是目标应用物理呈现或持续实时达标。上述时延记录来自此前仅在停顿后识别的版本，不代表当前连续窗口版本。Japanese Zipformer 的 padding、mask、词表和输入类型已与导出说明逐项核对，上游 `preprocessor_config.json` 为 `do_normalize=false`；目前未定位漏句原因，不以接口冒烟通过冒充质量验收。

Murasaki v0.2 8B/14B、MiLMMT 4B/12B、Qwen3-ASR 1.7B、Parakeet v2 未在本次设备上加载实测；仅完成固定工件及对应适配器接入。Murasaki 14B 备份也尚缺独立上游原件比对。所有新型号仍缺多人、口音、噪声、长句和 30–60 分钟热稳态验收。

本地详细日志位于 `artifacts/adapter-*.log`、`pipeline-*-final.log`、`qwen-completeness-final.log`、`model-network-proxy-final.log` 与 `baseline-*-final.log`，未进入 Git。

后续结果以[连续窗口与流式阅读](validation.md#210-连续窗口与流式阅读)和[翻译预填充与过载恢复](validation.md#211-翻译预填充与过载恢复)为准，包含接续错词、长句等待、约 10 分钟回放与已知失败；上述早期单次数据不作为当前性能结论。
