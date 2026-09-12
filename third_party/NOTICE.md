# CaptionGlass M1 third-party components

CaptionGlass application source is licensed under the MIT License; see [LICENSE](../LICENSE).
The following upstream components keep their own licenses.

| Component | Pinned source | License |
| --- | --- | --- |
| sherpa-onnx Android JNI and Kotlin API | `k2-fsa/sherpa-onnx` commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf` (v1.13.8) | Apache-2.0 |
| ONNX Runtime, linked into sherpa JNI | v1.28.2, static archive SHA-256 pinned by the fixed sherpa source | MIT and bundled third-party notices |
| llama.cpp / ggml CPU, Vulkan, OpenCL and Hexagon | `ggml-org/llama.cpp` commit `5266f24da75dc449bd56cbed7addb9c8e4a6a73e` (v0.4.0), plus `scripts/patches/llama-vulkan-cleanup.patch` and `scripts/patches/llama-hexagon-session.patch` | MIT |
| OpenCL-Headers | Khronos commit `4ea6df132107e3b4b9407f903204b5522fdffcd6` (v2024.10.24), archive hash in prepare-native.sh | Apache-2.0 |
| OpenCL-ICD-Loader (static, layers disabled) | Khronos commit `5907ac1114079de4383cecddf1c8640e3f52f92b` (v2024.10.24), plus `scripts/patches/opencl-android-driver.patch` | Apache-2.0 |
| Vulkan-Headers (including Vulkan-Hpp) | Khronos `vulkan-sdk-1.4.321.0`, archive hash in prepare-native.sh | Apache-2.0 OR MIT for compiled headers |
| SPIRV-Headers | Khronos commit `2a611a970fdbc41ac2e3e328802aed9985352dca`, shared with shaderc | MIT; source exceptions listed in adjacent license |
| shaderc / SPIRV-Tools / glslang (host build tools only) | shaderc v2025.3 and its matched DEPS, all archive hashes in prepare-native.sh | Apache-2.0 (shaderc and SPIRV-Tools); glslang's BSD/MIT/Apache terms in upstream LICENSE.txt |
| X-ASR Chinese/English model | `GilgameshWind/X-ASR-zh-en` revision in `models/catalog.json` | Apache-2.0 |
| PengChengStarling streaming 8-language ASR (sherpa int8 conversion) | `stdo/PengChengStarling`; pinned `csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10` revision `c6726c1147387ad2a11148b33973135d92a55e6c` | Apache-2.0 (upstream model card) |
| Nemotron 3.5 ASR Streaming 0.6B (sherpa INT8 conversion, 560 ms) | `nvidia/nemotron-3.5-asr-streaming-0.6b`; upstream revision `ea30d66debe3740a08b573244286791d423d6b3e`; `csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11` revision `ab43d895f5985b1bbab8b6eac8607fcdc05343f3` | OpenMDW-1.1 (NVIDIA model; full agreement in `OpenMDW-1.1.txt`); Apache-2.0 sherpa export code |
| Hy-MT2 1.8B Q4_K_M / Q8_0 models | `tencent/Hy-MT2-1.8B-GGUF` revision in `models/catalog.json` | Apache-2.0 |
| Material Symbols Rounded icons | Google Material Symbols release SVGs, converted to `app/src/main/res/drawable/ic_*.xml` | Apache-2.0 |

Full primary license texts and ONNX Runtime's third-party notices are adjacent.
Model files are downloaded separately and never committed or bundled in the APK.
The three speech fixtures are generated locally with macOS system voices for device
acceptance; the repository contains their generation script, not audio recordings.

Reproducible native archive URLs and SHA-256 values are in `scripts/prepare-native.sh`.
The selected sherpa Kotlin files are copied unchanged. The tracked llama.cpp patch
contains synchronization exceptions during context and Vulkan backend teardown;
the Qwen patch exposes EOS completion through an existing stream option. Other integration behavior resides in CaptionGlass's bindings. Android provides
the Vulkan loader/driver; the pinned shader compiler is built locally and is not
bundled in the APK.

OpenCL headers and ICD loader are Copyright The Khronos Group Inc.; their
Apache-2.0 license is adjacent. The Android discovery patch loads the optional
system `libOpenCL.so` without setting an ICD environment variable that can recurse
inside Qualcomm's system loader. Static loader symbols are hidden. OpenCL kernels
come from the pinned ggml source and are embedded in the APK; the device supplies
the proprietary GPU driver, which is not copied into the APK.

Before public redistribution, audit the complete transitive license inventory of
the upstream sherpa all-feature JNI distribution and the Gradle dependency graph.
This internal M1 build does not claim that a public-release license audit is complete.

Nemotron license source: https://openmdw.ai/license/1-1/ (retrieved 2026-09-11).
The converted weights retain NVIDIA model origin; official sherpa export workflow
publishes the matched files under csukuangfj2. Catalog file URLs, hashes, and
revisions identify the exact separately downloaded artifacts.

## Optional model families and runtime

All exact artifact revisions, names and hashes are in `models/catalog.json`.

| Component | Source / terms |
| --- | --- |
| LiteRT 2.2.0 (bundled CPU runtime) | Google `com.google.ai.edge.litert:litert:2.2.0`; Apache-2.0; https://github.com/google-ai-edge/LiteRT |
| Silero VAD | `onnx-community/silero-vad` pinned ONNX conversion of snakers4/silero-vad; MIT |
| Qwen3-ASR 0.6B / 1.7B | Qwen upstream, pantinor/thieunv sherpa conversions; Apache-2.0 |
| Japanese Zipformer Base | reazon-research upstream, litert-community conversion; Apache-2.0 |
| NVIDIA Parakeet v2/v3 / Japanese | NVIDIA upstream, csukuangfj sherpa exports; CC-BY-4.0: https://creativecommons.org/licenses/by/4.0/ |
| Hy-MT2 StreamRevise v4 | febilly fine-tune and GGUF; Apache-2.0 |
| MiLMMT-46 | Xiaomi Research upstream; mradermacher GGUF conversions; Gemma terms: https://ai.google.dev/gemma/terms |
| Murasaki v0.2/v0.3 | Murasaki-Project upstream; shoutmon backup and mradermacher conversion; CC-BY-NC-SA-4.0: https://creativecommons.org/licenses/by-nc-sa/4.0/ |

Murasaki is noncommercial under its weight license. Community conversions and backups
are identified as such in the manager; they are not described as official releases.
The v0.2 8B Q6_K backup hash matches the upstream original LFS pointer. Exact source
attribution and verification limits are recorded in `docs/model-support.md`.

## Hexagon build tools and runtime

`scripts/prepare-hexagon.sh` pins the upstream Snapdragon toolchain container by
SHA-256 digest. It contains Qualcomm Hexagon SDK 6.6.0.0 and Hexagon Tools 19.0.07.
These tools and SDK headers retain Qualcomm terms; they are not relicensed under
llama.cpp MIT. SDK headers include Qualcomm proprietary notices. The SDK itself
is kept in local build artifacts, not committed or distributed with this source.
The APK includes QAIC-generated host code and compiled ggml DSP kernels (including
the toolchain runtime code linked into those kernels). Verify applicable Qualcomm
redistribution terms before public APK distribution; this internal build does not
claim that the SDK or its generated artifacts carry an unrestricted MIT license.
The phone supplies libcdsprpc.so; no vendor driver is copied into the APK.
