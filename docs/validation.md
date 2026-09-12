# 验收与已知限制

本文保留可重复检查和按日期记录的证据。第 2 节包含旧版本行为与历史失败，不代表当前全部功能或性能；最近的连续字幕与翻译检查见 [2.10](#210-连续窗口与流式阅读)、[2.11](#211-翻译预填充与过载恢复) 和 [2.12](#212-可选翻译处理器)。

## 1. M1 可重复检查

### 1.1 不依赖模型的构建检查

环境准备和 `:engine:check :app:assembleDebug :app:lintDebug` 命令统一见[构建说明](architecture.md#6-构建与开发)。`PipelineCheck.kt` 是单个可执行检查，覆盖稳定前缀、重复 revision、缩写/小数/否定尾部、源修订恢复、上下文预算、队列上限、错 session/revision、超时与停止、取消后保留 active 槽及 1,000 个确认片段完整性。连续窗口检查覆盖样本完整、重复语句接缝、500 窗口状态退役与跨窗口修正；阅读检查覆盖流式结果身份、终态、撤回和未完成预览保留。原生滚动和字体重排在设备上检查。

### 1.2 真实推理与实时回放

```sh
./gradlew downloadModels
./gradlew downloadModels -Pmodel=nemotron-3.5-560ms-int8
./gradlew downloadModels -Pmodel=pengcheng-8lang-int8
bash scripts/prepare-fixtures.sh  # macOS say + ffmpeg
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
bash scripts/device-check.sh <明确选择且解锁的测试设备序列号>
```

系统合成语音使用 macOS Daniel / Tingting / Kyoko，文本固定在脚本中；16 kHz 单声道 PCM16，末尾补 1.6 秒静音。文件与日志均在 ignored `artifacts/`，不包含用户媒体或麦克风录音。测试 APK 使用平台 Instrumentation，无额外测试框架。

基础脚本安装两个调试 APK，向应用私有目录部署约 2.77 GB 的四套模型与中英日夹具。检查覆盖目录与语言兼容、哈希与损坏拒绝、多目标 MT、增量回调、取消与截止时间、输出预算、1× 回放、停止再开、48 kHz 重采样及尾部冲刷。断言失败输出 `FAIL`，脚本只接受 `PASS: all`。需要支持 Vulkan 1.2 及所需计算能力的 arm64 GPU；日志中的设备名、卸载层数和耗时才是 GPU 参与证据。

#### 1.2.1 独立检查

以下均使用已安装的测试 APK。`models` 与 `model-network` 仅操作独立的 `manager-check` 目录，无需推理权重；前者检查安装、取消、校验、恢复和删除，后者增加实际 HTTPS 下载及 HTTP 错误。

```sh
adb -s <测试设备序列号> shell am instrument -w -r -e mode models com.captionglass.app.test/com.captionglass.app.DeviceChecks
adb -s <测试设备序列号> shell am instrument -w -r -e mode model-network com.captionglass.app.test/com.captionglass.app.DeviceChecks
```

单型号检查先把 `artifacts/models/<ID>` 的平铺文件部署到应用私有 `files/models/<ID>`，并部署语音夹具。阅读检查还需允许悬浮窗；连续检查需部署命令指定的识别和翻译模型。

```sh
adb -s <测试设备序列号> shell am instrument -w -r -e mode adapter -e model japanese-zipformer-base-fp16 com.captionglass.app.test/com.captionglass.app.DeviceChecks
adb -s <测试设备序列号> shell am instrument -w -r -e mode reading com.captionglass.app.test/com.captionglass.app.DeviceChecks
adb -s <测试设备序列号> shell am instrument -w -r -e mode continuity -e model qwen3-asr-0.6b-int8 -e mt hy-mt2-streamrevise-v4-q4-k-m com.captionglass.app.test/com.captionglass.app.DeviceChecks
```

`reading` 使用真实英译中输出检查回看、恢复跟随、无障碍滚动和 30 秒保留。`continuity` 默认将固定语音重复四次，以 1× 速度检查自然结束与 24 秒提前停止；可加 `-e repetitions 260` 做长回放。时间为状态发布时间，不能当作屏幕物理呈现或自然语音质量。

翻译成本对照使用 `-e mode mt-profile -e model <翻译型号 ID>`，同样四个日语短句连续调用 12 次，报告准备、首字、总耗时与 token 数。`adapter` 和 `native` 还检查固定前缀复用、跨句隔离、取消与失败后的恢复。开发诊断可用 `adb logcat -s CaptionGlassMT CaptionGlassNative`，不记录字幕内容。重复合成语音与短句成本不能替代视频并行的自然语音验收。

模型已部署后，可在独立 Instrumentation 进程运行缺少 GPU 的回归检查；它在 native 注册前禁用 Vulkan，并断言明确报错而非改用纯 CPU：

```sh
adb -s <测试设备序列号> shell am instrument -w -r -e mode no-vulkan \
  com.captionglass.app.test/com.captionglass.app.DeviceChecks
```

vivo 的后台冻结可能在 Instrumentation 首个 Activity 启动前暂停进程。脚本在派发后显式打开目标 Activity；验收期间页面使用 `FLAG_KEEP_SCREEN_ON`，结束后解除。冻结等待不能混入模型性能。USB 安装仍遵守厂商逐次确认。

#### 1.2.2 翻译处理器独立验收

`backend` 只需部署指定翻译模型，不依赖 ASR 或音频夹具。默认型号是 Hy-MT2 Q4_K_M；Hexagon 成功路径使用目录中固定版本的 Q8_0。

```sh
adb -s <测试设备序列号> shell am instrument -w -r -e mode backend -e backend cpu com.captionglass.app.test/com.captionglass.app.DeviceChecks
adb -s <测试设备序列号> shell am instrument -w -r -e mode backend -e backend vulkan com.captionglass.app.test/com.captionglass.app.DeviceChecks
adb -s <测试设备序列号> shell am instrument -w -r -e mode backend -e backend hexagon com.captionglass.app.test/com.captionglass.app.DeviceChecks
adb -s <测试设备序列号> shell am instrument -w -r -e mode backend -e backend hexagon -e model hy-mt2-1.8b-q8-0 com.captionglass.app.test/com.captionglass.app.DeviceChecks
```

CPU 检查先禁用 Vulkan，验证明确的 GPU 不可用错误后执行真实 CPU 翻译。可用后端执行两次加载／增量翻译／卸载，并检查数字、主动取消、固定前缀复用及跨句隔离。Hexagon + Q4_K_M 必须明确拒绝量化格式；无兼容驱动的设备使用 Q8_0 必须明确拒绝设备。`PASS: backend hexagon` 可能表示预期拒绝，必须同时检查具体 PASS 行及原生日志，不能把它当作 NPU 成功推理。NPU 成功证据需要 HTP 设备、模型卸载层数和实际输出；固定短句不能替代实时音频质量或持续功耗验收。

### 1.3 实际跨应用与悬浮窗

1. 安装 debug/test APK，在主应用导入包或先运行上述开发验收写入校验后的私有语言包。
2. 拒绝一次录音权限，取消一次 MediaProjection，确认应用不崩溃且可以重新发起。
3. 允许悬浮窗，选择英语到中文，开启字幕；系统授权选择整个屏幕。
4. 启动独立测试 APK 的 `com.captionglass.app.FixturePlayer`，点击播放。它有独立 UID，明确允许播放捕获，只播放生成的 WAV。
5. 观察真实原文、增量译文和完成后的保留。拖动把手；在穿透模式点击正文下的播放器，播放器点击计数应增加；切到滚动模式后可在正文回看，新内容不应把阅读位置拉走。
6. 检查横竖屏、字体放大、安全区和长字幕；正文应可滚动到全部保留内容，回到底部恢复跟随，静音不清空字幕。
7. 通过通知或应用停止，确认读取结束、overlay/projection/前台通知退出，重新授权可开始；系统撤销 consent 也必须收敛到停止状态。
8. 切换中文到英语重复播放。静音与限制捕获只能反馈观测事实，不推断 DRM。

测试播放器启动示例：

```sh
adb -s <serial> shell am start -n com.captionglass.app.test/com.captionglass.app.FixturePlayer --es language en
```

## 2. 首台真机的基础结果

### 2.1 条件与可解释范围

2026-09-10，vivo V2415A / MT6991 / Android API 36，arm64-v8a。模型、runtime、编译配置均对应本次 M1 提交的 `models/zh-en.json` 与 `scripts/prepare-native.sh`。CPU：ASR 1 线程，MT 3 线程。应用无 INTERNET 权限；只有开发机在准备依赖时联网。

真机数据来自本轮开发中首个成功版本；随后修复的标点尾巴、安装恢复与显示细节在核心检查和模拟器验证。手机后来断开连接，最终 APK 尚未再次部署至该真机。

下面是第一轮成功的前台运行，之前被 OEM 冻结导致 deadline 失效的尝试已排除。样本很少，不能据此计算有意义的 P50/P95 或声称 30–60 分钟稳态达标。

### 2.2 双向真实推理

| 检查 | 观测 |
| --- | --- |
| MT 加载（文件缓存条件未清空） | 387 ms |
| 英译中：Good subtitles give you time to read. | 1,758 ms；“好的字幕能让你有时间阅读。” |
| 中译英：今天天气很好，我们去公园散步。 | 2,056 ms；“The weather is great today, so we went for a walk in the park.” |
| 200 ms native deadline | 209 ms 返回失败，未显示截断结果 |
| 派发前取消、1 token 预算、取消后重用 | 通过 |

中译英例句选择了过去时，并不完全保留中文原句的时态开放性；这里只验收完整执行，不将非空译文等同于准确翻译。

### 2.3 1× 音频闭环

| 输入 | 音频长度（含尾静音） | 首次稳定原文 | 首次完整译文（从回放开始） | 确认 / 终态 |
| --- | --- | --- | --- | --- |
| 英语 | 8,056 ms | 1,691 ms | 5,939 ms | 2 / 2，均完成翻译 |
| 中文 | 9,950 ms | 1,682 ms | 7,866 ms | 2 / 2，均完成翻译 |

英语两段从语义确认到译文分别约 1,939 / 2,953 ms；中文约 2,366 / 3,125 ms。这里的确认位置来自样本帧计数，包含 gate / endpoint 等待，不能冒充人工标注的语音结束点。首次原文约 1.7 秒，尚未达到产品的 0.8/1.2 秒目标。

已知质量错误：中文第二句的“字幕”被 ASR 识别为“字母”，MT 据此译为 “letters”。不对测试文本加特例修补；准确率与术语改进应在独立多样本验证中进行。

### 2.4 最终实现的补充验证

2026-09-11，独立临时数据分区的 Pixel 10 Pro AVD，Android API 37、arm64、16 KB 页、8 GB RAM。保存的 AVD 数据未修改。此环境只补充功能与兼容性证据，不替代手机性能。

| 项目 | 结果 |
| --- | --- |
| 最终 build / engine check / lint / test APK | 全部通过 |
| APK 16 KB zipalign、两个 native 库 ELF LOAD | 通过，LOAD alignment `0x4000` |
| 模型同尺寸损坏拒绝、还原后复核、中断切换恢复旧包 | 通过 |
| SAF 文件夹授权 → 五文件约 1.8 GB 导入 → 校验与替换 | UI 全流程通过，显示已就绪，staging / backup 已清理 |
| 双向 MT、带历史的短句、deadline、输出预算、取消后复用 | 通过 |
| 1× 中英回放 | 各 2 个确认片段、2 个终态 |
| 4.5 秒时停止并再开 | 2 个 `STOPPED`，无字幕恢复；约 204 ms 完成回收 |
| 48 kHz 输入与 stop-only 尾部冲刷 | 通过，保留最后的 “translation” |
| 拒绝录音/悬浮窗、取消 consent、重新请求 | 可恢复，未崩溃 |
| 独立 UID 播放器 → PlaybackCapture → ASR → MT → 悬浮窗 | 中英均出现真实双语字幕 |
| 触摸穿透 / 交互、把手拖动、横竖屏位置、150% 字号 | 已目视与窗口属性检查；每语种每页最多两行 |
| 运行中退出并重开页面 | 保持当前中译英方向 |
| 应用内停止后 projection / service / notification | 均已退出 |

实时循环捕获发现并修复了“迟到的孤立句号成为独立翻译任务”：SourceGate 现在消费纯标点尾部但不生成新的语义片段，native 边界同样拒绝无文字/数字的输入，并补了可执行回归。悬浮窗还避免对未改变的文字反复调用 `setText`。

模拟器加载后的 PSS 抽样约 2,036,112–2,052,635 KB，实际捕获抽样约 2,047,785 KB。这不是峰值，更不是手机 PSS 或热稳态结论。

未完成：最终 APK 的 vivo 跨应用悬浮窗复验、通知按钮停止与系统界面撤销授权的端到端检查、长时与多机型质量验证。API 37 的纯音频 projection 在运行时执行 `dumpsys media_projection` 存在系统侧空 recording-session 的 NPE；应用内停止后该命令正常返回 `null`。不将修改 `PROJECT_MEDIA` app-op 当作已验证的系统撤销。

### 2.5 本机字幕调度延迟对照

2026-09-11，macOS 上独立创建的 `CaptionGlassLatency` AVD，API 37、arm64、16 KB 页、8 GB RAM、12 GB 测试数据分区。基线 APK 来自改动前代码，优化版仅修改阅读停留、MT 完成后的派发和重复 KV 清零；模型、分句、采样和分页布局一致。没有使用或修改保存的个人 AVD 数据。

在相同模拟器中顺序运行 `mode latency`：固定中英合成音频各重复四次，以 1× 速度进入实际 ASR/MT，并各追加 45 秒明确的测试静音，供较慢基线排空阅读页。额外静音不用于证明持续吞吐。英语各 8 个确认片段、8 页，中文各 8 个确认片段、11 页；两版均通过全部片段翻译、全部页面发布、零阅读溢出及最后一页完成停留的断言。逐条比较 16 段原文、译文和提交样本位置完全一致。

| 指标 | 基线 | 优化后 |
| --- | --- | --- |
| 英语：译文完成 → 第一页发布，平均 / 最大 | 4,608 / 9,284 ms | 0 / 0 ms |
| 中文：译文完成 → 第一页发布，平均 / 最大 | 6,355 / 17,392 ms | 1,245 / 4,009 ms |
| 中文：译文完成 → 每页发布，平均 / 最大（含必要分页停留） | 7,644 / 17,415 ms | 2,019 / 4,259 ms |
| 英语：回放开始 → 首个稳定原文 / 首个完整译文 | 1,647 / 5,298 ms | 1,654 / 5,348 ms |
| 中文：回放开始 → 首个稳定原文 / 首个完整译文 | 1,647 / 6,896 ms | 1,661 / 7,067 ms |

表中“译文完成”以完整结果首次进入会话状态的时刻观测，不是 JNI 返回的精确时刻。`0 ms` 表示同一次 Main 状态发布中已包含刚完成的译文与第一页，受毫秒时钟精度约束，不是屏幕物理显示零延迟。这里记录的是页面发布而不是实际扫描呈现；只进行了一轮配对实验，不据此估计广泛的 P50/P95、热稳态或手机性能。首次识别/翻译没有显示出提速，主要改善是连续内容的额外阅读等待。汉字 100 ms、其他码点 50 ms、双语取较慢侧的阅读规则仍需要人工理解测试校准。

基线和优化版均保留已知的“字幕→字母”错误；重复中文样本还暴露出原有最长等待分句将“手机”等内容拆开的情况。本次未修改 ASR 或分句，不声称翻译质量已达标。原始对照在 ignored `artifacts/latency-before.log`、`latency-after.log` 和 `latency-summary.json`。

优化版 `:engine:check :app:assembleDebug :app:lintDebug` 与 test APK 构建通过；同一 AVD 上 `mode all` 通过，覆盖模型损坏拒绝/恢复、双向 MT、取消/截止时间/输出预算、停止再开、48 kHz 重采样和尾部保留。日志在 `artifacts/latency-optimized-build.log`、`latency-test-build.log` 和 `latency-final-all.log`。

测试准备曾遇到旧临时分区空间不足导致模型复制不全，校验正确拒绝；改用全新独立 AVD 后完成对照。最初 15 秒收尾静音不足以让基线最后一页读完，测试断言正确失败，随后两版统一改为 45 秒重新测量；该失败尝试未混入上表。

### 2.6 界面与悬浮窗重设计

2026-09-11，同一 `CaptionGlassLatency` AVD 以 `-read-only` 启动（API 37、arm64、16 KB 页、8 GB RAM），退出后不保存数据分区。本节只验证界面、交互与状态映射，不作推理性能或阅读质量结论。

| 项目 | 结果 |
| --- | --- |
| `:engine:check :app:assembleDebug :app:lintDebug` 与 test APK | 通过 |
| `mode replay` | 通过；英语 2/2、4.5 秒停止 2/2、中文 2/2 终态 |
| 首页主按钮状态 | 未导入、就绪、授权中、准备中、运行、停止依次显示导入、开启、进度环、停止与会话计时；忙碌时方向切换禁用 |
| 拒绝录音、取消 consent | 分别出现带“去设置”的提示与“已取消捕获授权”，均可重新发起 |
| Android 14+ 授权界面 | 只提供共享整个屏幕 |
| 开启后无播放 | 先显示等待声音，约 3 秒后应用内与悬浮胶囊显示“未收到声音”及提示；不推断 DRM |
| 独立 UID 播放器 → 捕获 → ASR → MT → 悬浮窗 | 临时原文（未稳定部分较暗）与脉动占位，随后译文在上、原文在下；记录 37 段均有终态 |
| 触摸穿透 / 拦截 | 穿透时点击卡片使播放器计数 1→2；轻点把手切到拦截（不透明、绿色描边）后点击卡片计数不变并回到穿透 |
| 拖动、横屏、130% 字号、深色模式 | 把手拖动与水平居中吸附正常；横屏改用侧边导航，悬浮窗旋转后重新定位；各页无截断 |
| 前台通知 | 显示方向、运行计时与停止操作 |

未验证：SAF 导入过程中的进度环与失败提示（导入流程只增加进度上报与错误文案筛选）、真机与 OEM 通知样式、TalkBack 全流程。

### 2.7 模型选择与多语种实验扩展

2026-09-11，独立临时 `CaptionGlassMultilingual` AVD，API 37、arm64、16 KB 页、8 GB RAM、12 GB 数据分区；未使用个人 AVD 的数据。连接的 vivo V2415A 拒绝本次 USB 安装（`INSTALL_FAILED_ABORTED: User rejected permissions`），所以本节不是手机实测。固定模型见 `models/catalog.json`；应用继续无网络权限。

| 检查 | 观测 |
| --- | --- |
| 模型准备 | 三项共 2,086,550,513 字节；不可变 revision、尺寸、SHA-256 全部通过；ASR 分目录，MT 一份 |
| 核心与构建 | `:engine:check :app:assembleDebug :app:lintDebug` 与测试 APK 通过；独立代码审查发现的标签参数遗漏已修复 |
| 目录与选择规则 | 输入／目标分离、日语自动匹配、拒绝不兼容模型和未知输入、交换约束、共用 MT、独立 tokens 路径通过 |
| 模型安装边界 | 同尺寸损坏拒绝、还原复核、进程中断后的 backup 恢复通过；真实 SAF 文件夹授权→339 MB 多语模型替换→已就绪通过，staging/backup 清理，X-ASR 与 MT 保持就绪 |
| 原生翻译 | 日中、日英、中日、英日、英法、英韩、英阿；中英原有方向继续通过；这几条固定短句不代表完整质量评估 |
| 取消与预算 | 派发前取消、200 ms deadline、1 token 输出预算、取消后复用、带背景短句通过 |
| 1× 真推理 | 中英、日中、日英、英日各 2 个确认片段／2 个终态；日语 8.5 秒停止后完整收敛，另有中英停止再开检查 |
| 音频与显示 | 48 kHz 重采样和 stop-only 尾部保留通过；分页文本拼接完整；没有用离线批量吞吐冒充实时回放 |
| 选择器界面 | 真实 AVD 截图检查：输入／目标搜索、日语自动匹配、禁用不支持方向的交换、进程重启后保留日→法、深色与 130% 字号、横屏设置布局；独立原生 UI 审查通过（不含 TalkBack、平板或 200% 字号） |

原生 MT 示例：“今日はいい天気です。”→“今天天气很好。”／“It’s nice weather today.”（具体用词见日志）；“Thank you very much.” 的法、韩、阿输出分别包含“Merci beaucoup.”、“정말 감사합니다.”、“شكرًا جزيلًا.”。这些是权重的实际输出，不是预设字幕。

日语诊断使用同一段 Kyoko 合成语音，以 1× 速度直接进入 LocalRecognizer，绕过 SourceGate、MT 和 UI。greedy 输出“日本はいい天気です”和“音声を翻訳します”；中间区域为原始 ASR 空假设，确认遗漏并非队列或分页丢段。同条件下 sherpa 现有 modified beam search（4 paths）输出“きょうはいい天気です”和“しましょうこのアプリは日本語の音声を翻訳します”。因此多语模型固定使用 beam，中英 X-ASR 保持 greedy；不添加新依赖或用户调参。

最终日语首个稳定原文约 1.2 秒，第一条译文约 4.3–4.5 秒（含语义等待）；后半段在 9.499 秒语音输入结束后完成。这是模拟器短样本观测，不是手机延迟目标达标、P50/P95 或热稳态结果。

**已知限制：** beam 仍漏掉“公園を散歩”等词，翻译可能据不完整原文补成泛指表达；中英旧样本仍有“字幕→字母”错误。上游定制服务使用语言 tag 初始化；本次固定通用 Zipformer JNI 接口未开放该接口，语言选择不会强制 ASR 输出指定语言。八语种模型中的俄、越、泰、印尼、阿语音输入，以及其余 Hy-MT2 目标代码，尚未逐一做本应用的真实样本验收。所有新增语种保留实验提示；自然语音、否定／数字／专名、多说话人、手机持续负载与 30–60 分钟验收仍未完成。

证据保存在 ignored `artifacts/multilingual-build.log`、`multilingual-final-all.log`、`multilingual-asr-diagnostic.log` 与 `multilingual-asr-beam.log`。`mode asr-ja` 可重跑当前目录所固定的日语解码配置；`mode verify` 只重新校验模型文件，不执行质量测试。

### 2.8 ASR CPU 与 Hy-MT2 Vulkan GPU

2026-09-11，用户解锁并允许 USB 安装后，在 vivo V2415A（API 36、MT6991／天玑 9400、Mali-G925-Immortalis MC12、Vulkan 1.3）完成本节验证。最终 debug APK 已安装。ASR 仍是 sherpa CPU 单线程；模型文件、采样规则、上下文上限、队列和阅读规则保持原配置。

运行日志确认 Hy-MT2 使用 `Vulkan0`，`offloaded 33/33 layers to GPU`；GPU 模型缓冲 1,075.74 MiB、KV 128 MiB、计算缓冲 3.81 MiB。每次提交、batch 和 ubatch 均为 8；剩余 CPU 算子沿用 3 线程。使用固定 shaderc v2025.3 构建含协作矩阵支持的 shader，系统提供 Vulkan loader／驱动，不集成 NPU/QNN。

| 验证 | 结果 |
| --- | --- |
| 核心、debug APK、lint、测试 APK | `:engine:check :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 全部通过 |
| 原生打包 | APK 16 KB zipalign 通过；最终 `libcaptionglass.so` 的全部 LOAD 对齐为 `0x4000`，Vulkan 动态依赖为系统 `libvulkan.so` |
| 手机 `mode all` | 通过目录规则、模型损坏拒绝／恢复、多方向真实 MT、预算、背景上下文、实时回放、停止再开、48 kHz 重采样与尾部保留 |
| 取消 | 200 ms deadline 在两轮最终配置检查中分别于 209 / 398 ms 返回；运行 150 ms 后主动取消分别于调用开始后 219 / 218 ms 返回；随后模型复用通过 |
| 1× 实时回放 | 中英、日中、日英、英日及停止样本共 14 个确认片段、14 个终态：11 条译文、3 个 `STOPPED`，无 `TIMED_OUT` |
| 无 GPU 路径 | 独立只读 API 37 AVD 的 `mode no-vulkan` 通过，精确返回 `vulkan_device_unavailable`；该 AVD 的软件 Vulkan 不作为手机 GPU 性能证据 |
| 资源抽样 | 加载后 PSS 为 2,152,449–2,355,808 KB；debug APK 约 112 MiB，CPU 基线约 61 MiB，增加部分主要来自内嵌 shader |

同一手机顺序运行改动前 CPU 基线与最终 GPU 版本，原生短句调用总耗时如下。表中 GPU 数据统一取最终 `mode all`，不是从多次结果中挑最快值。

| 原生输入／目标 | CPU 基线 | Vulkan 最终版本 |
| --- | --- | --- |
| 模型加载 | 296 ms | 1,101 ms |
| Good subtitles give you time to read. → 中 | 1,541 ms | 1,920 ms |
| 今天天气很好，我们去公园散步。 → 英 | 1,963 ms | 1,490 ms |
| 今日はいい天気です。 → 中 | 1,387 ms | 988 ms |
| 今日はいい天気です。 → 英 | 1,550 ms | 1,094 ms |
| Thank you very much. → 日 | 1,733 ms | 1,368 ms |
| 非常感谢。 → 日 | 1,655 ms | 1,351 ms |
| Thank you very much. → 法 | 1,286 ms | 907 ms |
| Thank you very much. → 韩 | 1,399 ms | 969 ms |
| Thank you very much. → 阿 | 1,514 ms | 1,054 ms |
| Thank you. → 中 | 1,110 ms | 770 ms |

这组短句多数耗时下降约 18–31%，首次英译中却增加约 25%，模型加载也更慢。CPU 与 GPU 的浮点计算并非逐位一致，英日样例分别输出“本当にありがとうございます。”和“どうもありがとうございます。”；不能把这些小样本当作翻译质量等价证明。未清空文件／shader 缓存，也未严格控制温度；这不是 P50/P95、冷启动、耗电或持续吞吐结论。

最终 1× 回放中，从开始到首个稳定原文／完整译文：英语到中文 1,709 / 5,362 ms，中文到英语 1,690 / 7,216 ms，日语到中文 1,265 / 4,767 ms，日语到英语 1,268 / 4,941 ms，多语 ASR 英语到日语 867 / 8,407 ms。以上包含语义确认等待；自然完成的样本均得到两条译文，不能用原生短句耗时替代这个闭环指标。

调优时先验证了 64-token 批次：旧 NDK glslc 缺少协作矩阵支持，更新编译器后短句预填充仍达约 2–3.5 秒。8-token 批次触发上游 Vulkan 的向量计算路径，将预填充降至约 0.68–1.39 秒，并缩短取消等待。KV 清零仅约 4–6 ms，因此保留成功／失败后的原始缓存清零。没有放宽超时或改变 ASR／分句来获得通过结果。

**限制：** GPU 在途工作不可抢占，驱动挂起时不保证硬实时 deadline。清理异常隔离与析构补丁已完成代码审查，但未做真实驱动丢失／挂起注入。原有“字幕→字母”和日语漏词仍存在。此次未复验跨应用播放捕获与悬浮窗全流程，也未完成 30–60 分钟热稳态、电量、自然语音质量或其他 GPU 的验收。

证据位于 ignored `artifacts/vulkan-batch8-build.log`、`vulkan-phone-cpu.log`、`vulkan-phone-batch8-native.log`、`vulkan-phone-final-all.log`、`vulkan-phone-final-native.log` 和 `vulkan-emulator-unavailable.log`。前期 64-token 试验日志单独保留，不混入最终数据。

### 2.9 模型管理与 Nemotron 3.5

2026-09-11，Nemotron 推理与原有模型回归在 vivo V2415A（API 36、天玑 9400、Mali-G925）执行；模型管理与界面使用 `-read-only` 的独立 `CaptionGlassLatency` AVD（API 37、arm64、16 KB 页、8 GB RAM）。本次新增 INTERNET 权限，仅用于用户主动下载固定模型；此前章节的无网络权限描述对应当时版本。

接入 Nemotron 3.5 ASR Streaming 0.6B 的 560 ms INT8 sherpa 导出：revision `ab43d895f5985b1bbab8b6eac8607fcdc05343f3`，四文件共 682,215,356 字节。完整下载后逐文件验证尺寸与 SHA-256，并实际读取 ONNX metadata，确认 128 维特征、65 帧窗口、56 帧步长及语言 prompt 映射。沿用 sherpa 1.13.8 / ORT 1.28.2、CPU 单线程与现有流接口；源语言通过 `setOption("language", code)` 指定，翻译继续使用 Hy-MT2 Vulkan。

| 检查 | 结果 |
| --- | --- |
| 构建与核心 | `:engine:check :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 通过；最终 APK 安装至手机后 `mode verify` 通过，16 KB zipalign 通过 |
| 模型准备与匹配 | 四套固定文件校验通过；18 种 Nemotron 输入、20 种合并输入能力；日语自动匹配、保留已有兼容选择、泰语回到 PengChengStarling、拒绝不兼容源语言通过 |
| 安装边界 | 小文件实际安装；同尺寸损坏、截断、超长输入拒绝并保留旧模型；取消清理 staging；拒绝并发删除；取消／失败的重新校验撤销标记；backup 恢复；删除仅影响指定目录，全部通过 |
| 网络 | `mode model-network` 实际下载固定 tokens 并核对哈希；HTTP 404 不覆盖已装模型；应用 UI 下载完整 Nemotron → 校验 → 已就绪通过 |
| 本地导入 | Android 系统文件夹选择：缺少 encoder 时明确报错且旧模型可用；补齐四文件后完整 683 MB 替换成功，staging／backup 清理 |
| 手机 `mode all` | Nemotron 日中、日英停止、英日，加上原有 ASR／MT 回归，共 20 个确认片段、20 个终态：16 条译文、4 个 `STOPPED`，无 `TIMED_OUT` |
| 音频与停止 | Nemotron 与 X-ASR 的 48 kHz 状态重采样和 stop-only 尾部冲刷均通过，保留最后的 “translation”；未修改会话队列、分句或超时来获得通过 |
| 界面 | 原生截图及实际操作覆盖未安装、下载、就绪、选用、详情、删除确认、导入失败／成功；浅色、深色、130% 字号与横屏检查；独立原生 UI 审查结论为 ship，无必修问题；不包含 TalkBack 全流程 |

本轮 Nemotron 的 1× 合成音频回放如下。时间从回放开始计算，包含识别稳定和语义等待；日英样本在 8.5 秒主动停止，第二段得到 `STOPPED`。

| 方向 | 首个稳定原文 | 首个完整译文 | 确认／终态 |
| --- | --- | --- | --- |
| 日语 → 中文 | 1,933 ms | 5,019 ms | 2 / 2 |
| 日语 → 英语（停止样本） | 1,933 ms | 4,459 ms | 2 / 2 |
| 英语 → 日语 | 1,937 ms | 5,383 ms | 2 / 2 |

Nemotron 与 MT 加载后 PSS 抽样为 2,316,481–2,521,592 KB，并非峰值或热稳态。同轮原 PengChengStarling 日中首个稳定原文／译文为 1,265 / 4,446 ms，因此不能据此宣称 Nemotron 更快。

**质量限制：** 直接 ASR 诊断得到“今日はいい天気です。”和“このアプリは日本語の音声を翻訳します”，仍遗漏“公園を散歩しましょう”部分。这是 Kyoko 合成语音的局部观察，不代表日语准确率提升；确认／终态完整也不代表没有识别漏词。英语到日语仍可能将 “local speech recognition” 误译为“現地言語の音声認識”。其他 Nemotron 语种未逐一做真实音频验收；自然语音、噪声、否定／数字／专名、跨应用持续捕获、其他机型与 30–60 分钟热稳态未在本轮验证。

模型管理使用现有协程与 Android API，无后台服务、断点续传或自定义模型市场。下载时需保持应用开启；取消或进程被系统关闭后重试从头开始。设备低空间分配失败与 OEM 杀进程未做专门注入；安装中断的目录恢复由可执行检查覆盖。

证据在 ignored `artifacts/model-manager-final-build.log`、`model-manager-phone-all.log`、`model-manager-network.log`、`nemotron-onnx-metadata.json` 与 `model-manager-ui/`。固定来源、许可和哈希见 `models/catalog.json` 与 `third_party/NOTICE.md`。

### 2.10 连续窗口与流式阅读

2026-09-12，vivo V2415A / API 36，实际 CPU ASR 与 Vulkan MT，使用已有 macOS 合成语音。固定时间分页已删除，以下检查针对当前的滚动记录；上文旧版分页数据仅作为历史记录。

| 检查 | 结果 |
| --- | --- |
| JDK 17 构建 | `--no-watch-fs :engine:check :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 通过 |
| 实际 MT 流式回调 | Hy-MT2 在正常结束前产生 3 次有效 UTF-8 预览；多语目标、流式中取消、超时和取消后复用通过 |
| 阅读 | 4 个真实翻译片段静置 30 秒仍保留；触摸回滚和无障碍后滚保持位置，回到底部恢复跟随 |
| 界面 | 默认不透明、可滚动；竖屏和横屏 130% 字号检查通过；字体与自动旋转设置已恢复 |
| Qwen 0.6B + StreamRevise | 27.76 秒连续英语的四次重复锚点全部保留，正常结束 5/5、24 秒停止 4/4 个确认／终态；首个原文／增量译文约 6.08/12.46 秒 |
| Parakeet 日语 + MiLMMT 1B | 33.53 秒连续日语的四次重复锚点全部保留，正常结束和 24 秒停止均为 1/1；首个原文／增量译文约 4.45/37.10 秒 |
| X-ASR + Hy-MT2 | 27.76 秒连续英语、正常结束和 24 秒停止均为 8/8；首个原文／增量译文约 1.39/5.93 秒 |

以上计数验证接续和结果处理，不代表逐字正确或完整翻译。Qwen 存在窗口右缘改写、重复与错词；Parakeet 日语仍有局部漏词且缺少标点，语义层要等停顿才能提交，因此译文等待明显偏长。MiLMMT 在重复长段上压缩了部分重复译文。当前未增加逐假设重译或针对样本文本的切句规则，也未以强制切断句末谓语、否定来降低延迟。Japanese Zipformer 的既有漏句和其他未实测型号限制见 [模型支持表](model-support.md)。

日志为 ignored `artifacts/reading-native-final.log`、`reading-reading-accepted.log`、`reading-qwen-accepted.log`、`reading-parakeet-accepted.log`、`reading-streaming-accepted.log`；截图在 `artifacts/reading-ui/`。这轮覆盖真实推理的功能和阅读状态，不包含自然语音质量、30–60 分钟热稳态或跨应用持续播放质量验收。

### 2.11 翻译预填充与过载恢复

2026-09-12，在同一 vivo V2415A／天玑 9400 上验证。权重、Vulkan 后端、8-token batch、2,048-token context 和包含排队的 8 秒总预算不变；ASR 仍在 CPU。不能把所有停字归因于热降频：本次分别观察到重复预填充、等待队列挤占预算、Murasaki 在译文后继续分析，以及日语缺标点导致的超长确认片段。

最终等待槽只保留最新一个片段，被替换的旧片段明确结算 `BACKLOG`，不打断 active 来追赶新输入。背景限最近一段完整原文，超过 64 tokens 整段省略。模型准备阶段预计算 APK 固定前缀；成功和正常取消只移除本句位置，其他推理失败清空缓存，清理失败则停止会话并解除 AudioRecord 阻塞。没有加入重试、动态热控框架或自动切换后端。

Hy-MT2 保留已验证的提示顺序和采样，仅复用两个固定角色 tokens。Murasaki 使用简短的“仅输出完整译文”系统提示、已闭合思考块、`译文：` 答案起始，并通过现有 logit-bias sampler 屏蔽两个思考控制 token；仍必须正常 EOG 才成功。Murasaki 的固定前缀从 89 缩短到 33 tokens，原文始终完整，不以标点、换行或输出上限提前截断。

`mt-profile` 对同样四个日语短句连续调用三轮，每组均计入全部 12 次调用。首字指翻译调用到首个非空 UTF-8 文本回调，不含模型准备、ASR、语义等待、排队或屏幕呈现。最终检查同时核对当前源句的主题、否定和数字，拒绝把背景译文当作性能成绩。

| 型号／指标 | 改动前 | 保留版本／最终复验 |
| --- | --- | --- |
| Hy-MT2 1.8B 首字中位数 | 1,418.5 ms | 1,405.5 ms；最终复验 912 ms |
| Hy-MT2 整句中位数／最大值 | 1,705 / 2,370 ms | 1,708 / 1,893 ms；复验 1,151.5 / 1,308 ms |
| Murasaki v0.3 4B 首字中位数 | 6,091.5 ms | 1,473 ms |
| Murasaki 整句中位数／最大值 | 6,470 / 6,859 ms | 1,919.5 / 2,159 ms |
| 最终模型准备 | 未记录可比值 | Hy-MT2 1,341 ms；Murasaki 7,212 ms |

相同的 Hy 保留推理路径两轮耗时差异很大，不能将较快一轮直接解释为稳定提速。系统拒绝读取 GPU 实时频率，温度、DVFS 和文件／shader 缓存没有严格控制；`Thermal Status=0` 也不证明没有降频，未测量每句能耗。Murasaki 的一次性前缀成本移到准备阶段，并未消失；上表不能证明“点击开启到第一条字幕”同比改善。

Hy-MT2 搭配 Nemotron 560 ms CPU ASR，以 `continuity -e repetitions 96` 进行 1× 英语合成语音回放。音频 620,228 ms、总历时 622,188 ms；191 个确认片段全部 `TRANSLATED`，没有超时、积压或推理失败。首次原文／增量译文为 1,453 / 5,488 ms。前 20／后 20 次推理耗时中位数为 1,690 / 2,111 ms，输入片段不完全相同；后期仍有变慢，不能声称消除了降频。随后 24 秒停止检查得到 7/7 个终态：5 条译文、2 个 `STOPPED`。热状态从 NONE 升至 LIGHT，约 8 分钟后 headroom 在 0.90 附近；两次 PSS 抽样为 2,531,879 / 2,726,341 KB，不是峰值或泄漏判定。

Murasaki 的最终约 10 分钟日语回放中，74 个确认片段都有终态：70 条译文、4 个超长片段 `TIMED_OUT`，没有 `BACKLOG` 或运行库 `FAILED`，超时后短句继续恢复。但 ASR 重复内容覆盖只有 74/76，完整 `continuity` 验收明确失败，不能记作全链路通过。热状态不高于 LIGHT，运行中 PSS 单次抽样为 3,801,239 KB。另行完成短回放和停止检查：自然结束 2/2 条译文；4.5 秒停止得到 1/1 个 `STOPPED`，会话约 5.0 秒回收完成。

原始故障输入 `公園を散歩しましょう。` 曾先输出 `去公园散步吧。`，再生成多余的 `</think>` 和 `[Style & Persona]` 分析，现有预览过滤器隐藏了后续内容。固定测试句的原始输出确认了这一机制；临时原始输出探针已移除，生产日志不记录字幕。最终十个输入均在 8 秒内正常结束：覆盖有／无标点天气句、公园句、三句连写、两组重复句、否定、时间和姓名。对应天气句约 1.76 秒、公园句 1.62 秒、三句连写 4.04 秒、两组重复句 6.24 秒；重复原文没有压缩为一组。这组回归不能替代自然语音翻译质量验收。

未保留的对照包括：Murasaki [作者生成参数](https://github.com/soundstarrain/Murasaki-Translator/blob/main/middleware/murasaki_translator/core/engine.py)、`/no_think`、单独预填答案和小幅重复惩罚，均未可靠解决失败；Hy 将翻译指令或输出规则移到背景之前虽变快，却误译或带出背景，因此全部撤回。仅通过最初七个短句的 Murasaki 中间版本也未被当作最终结果。原始失败与被拒绝的速度记录均保留。

JDK 17 的 `:engine:check :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 和 APK 16 KB 对齐通过。原生／adapter 检查覆盖固定前缀跨句隔离、取消后保留固定前缀、输出预算失败后清空重建、多语言、背景隔离、deadline 和 active cancellation。真实 GPU 丢失／清理失败没有注入；异常到服务停止路径经代码复核，不能将 session 回放当作 MediaProjection／AudioRecord 故障实测。与视频、悬浮窗并行的自然语音及 30–60 分钟热稳态仍未验收；日语缺标点造成的语义等待和超长片段仍是已知限制。

证据位于 ignored `artifacts/mt-profile-*-before.log`、`mt-profile-hy-after.log`、`mt-profile-hy-retained-check.log`、`mt-profile-murasaki-final.log`、`mt-retained-summary.json`、`mt-endurance-hy.log`、`mt-endurance-murasaki-mask.log`、`mt-endurance-*-summary.json`、`mt-endurance-native.log`、`mt-murasaki-mask-regression.log`、`mt-murasaki-stop-check.log`、`mt-native-retained-check.log` 和 `mt-final-build.log`。测试文本来自固定合成语音或明确的短句夹具；生产诊断仅记录身份、计数、耗时、热抽样和终态。

### 2.12 可选翻译处理器

2026-09-12：设置新增 GPU (Vulkan)、CPU、实验性的 NPU (Hexagon)，默认 Vulkan；选择跨进程保存，会话中锁定。ASR 仍为 CPU。NPU 使用固定 llama.cpp 的 Hexagon 后端、APK 自带 v73/v75/v79/v81 DSP 内核和系统 FastRPC 驱动；K-quants/IQ4_XS 明确拒绝，无静默整模型 CPU 回退。新增官方 Hy-MT2 Q8_0，完整下载的 SHA-256 和实际 GGUF 架构、354 个张量类型均已核对。

JDK 17 下 `:engine:check :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 通过；APK 的 ARM64 JNI ELF 16 KB 对齐及四份 DSP assets 打包检查通过。两份 llama.cpp 补丁在干净固定源码上重新应用，结果与实际编译源文件一致。静态复核覆盖显式设备选择、CPU 不初始化 Vulkan、取消与卸载次序、DSP 错误传播及失败会话退役；实际 DSP 卡死／驱动终止失败未注入。

已在用户指定的 Xiaomi Pad 8 Pro（SM8750P，Android API 36）安装应用和 Instrumentation APK。实测设置三项正常显示，Hexagon 驱动／架构检测可用，CPU 选择在强制停止并重启应用后恢复，Hexagon 选择成功保存；设备 `catalog` 检查通过。USB 大文件传输反复中断，曾改用临时本地 Wi-Fi 调试连接。用户随后明确反馈“我已验证成功，现在提交吧”，因此按用户验收结束后续自动化并提交。该反馈是用户真机确认；本次代理没有取得完整 CPU／Vulkan／Hexagon `backend` 自动化成功日志，不据此声称 NPU 性能、功耗或持续字幕质量达标。复测命令见 1.2.2。

证据在 ignored `artifacts/backend-final-build.log`、`backend-tablet-catalog.log`、`backend-settings-tablet.png`、`backend-settings-cpu-restored.xml`；模型传输失败记录保留在 `backend-tablet-deploy.log`。

## 3. M2：可持续体验

实现用户可选的 Room 记录、保留周期与删除，DataStore 设置，TXT/SRT/VTT 导出，相关术语，跨进程断点续传与后台任务恢复。会话时间轴与源视频时间轴明确区分，存储失败不能导致无限内存缓存。

使用有授权的同一批样本，覆盖讲课、技术专名、中英混说、快语速、口音、背景音乐、数字和否定。至少一台主流骁龙中端、一台天玑中端和一台旗舰，同时播放视频，运行 30–60 分钟。

| 指标 | 记录方式 | 初始目标 |
| --- | --- | --- |
| 首次可读原文 | 语音开始 → 可读假设，P50/P95 | 0.8/1.2 秒 |
| 稳定译文延迟 | 人工标注片段结束 → 完整译文，P50/P95 | 1.5/2.5 秒 |
| 完整体验延迟 | 片段开始 → 完整译文 | 包含语义等待，单独报告 |
| MT 吞吐余量 | 平均 MT 耗时 / 平均片段到达间隔 | ≤0.6 |
| 持续积压 | 队列长度、最老年龄、热稳态趋势 | 不持续增长 |
| 完整性 | confirmed / translated / 各未翻译原因 | 每条可核对 |
| 阅读稳定 | 修订、滚动锚点、字号重排、回到底部跟随 | 人工检查与计数 |
| 资源 | 峰值 PSS、热状态、电量、冷/热启动 | 8 GB 机 PSS 初始预算 2.5–3 GB |

这些数字是目标，不是已达标声明。英语片段以 2–4 秒、最长等待约 5–6 秒起测，日语需独立校准；不按内存容量猜测持续性能。原始音频默认不保存；质量数据集需明确授权并放在 Git 外，生产诊断不记录识别文本。

## 4. M3：经验证的扩展

日语、多语种与 Vulkan MT 已提供实验性接入，Hexagon NPU 为可选实验后端，均仍需独立的长时与质量验收。长时基线通过后再评估 STQ、GPU 设备白名单、QNN／其他 NPU 和轻量配置。一次只改变一个变量，保留数字、否定、专名的准确性比较。热策略在片段边界切换并设冷却；没有已验证的替代配置时保留原文与未翻译状态，绝不隐式转云端。
