# CaptionGlass M1 third-party components

The application source is private and has no public distribution license yet.
The following upstream components keep their own licenses.

| Component | Pinned source | License |
| --- | --- | --- |
| sherpa-onnx Android JNI and Kotlin API | `k2-fsa/sherpa-onnx` commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf` (v1.13.8) | Apache-2.0 |
| ONNX Runtime, linked into sherpa JNI | v1.28.2, included in the hash-pinned sherpa Android archive | MIT and bundled third-party notices |
| llama.cpp / ggml CPU and Vulkan | `ggml-org/llama.cpp` commit `5266f24da75dc449bd56cbed7addb9c8e4a6a73e` (v0.4.0), plus `scripts/patches/llama-vulkan-cleanup.patch` | MIT |
| Vulkan-Headers (including Vulkan-Hpp) | Khronos `vulkan-sdk-1.4.321.0`, archive hash in prepare-native.sh | Apache-2.0 OR MIT for compiled headers |
| SPIRV-Headers | Khronos commit `2a611a970fdbc41ac2e3e328802aed9985352dca`, shared with shaderc | MIT; source exceptions listed in adjacent license |
| shaderc / SPIRV-Tools / glslang (host build tools only) | shaderc v2025.3 and its matched DEPS, all archive hashes in prepare-native.sh | Apache-2.0 (shaderc and SPIRV-Tools); glslang's BSD/MIT/Apache terms in upstream LICENSE.txt |
| X-ASR Chinese/English model | `GilgameshWind/X-ASR-zh-en` revision in `models/catalog.json` | Apache-2.0 |
| PengChengStarling streaming 8-language ASR (sherpa int8 conversion) | `stdo/PengChengStarling`; pinned `csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10` revision `c6726c1147387ad2a11148b33973135d92a55e6c` | Apache-2.0 (upstream model card) |
| Nemotron 3.5 ASR Streaming 0.6B (sherpa INT8 conversion, 560 ms) | `nvidia/nemotron-3.5-asr-streaming-0.6b`; upstream revision `ea30d66debe3740a08b573244286791d423d6b3e`; `csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11` revision `ab43d895f5985b1bbab8b6eac8607fcdc05343f3` | OpenMDW-1.1 (NVIDIA model; full agreement in `OpenMDW-1.1.txt`); Apache-2.0 sherpa export code |
| Hy-MT2 1.8B Q4_K_M model | `tencent/Hy-MT2-1.8B-GGUF` revision in `models/catalog.json` | Apache-2.0 |
| Material Symbols Rounded icons | Google Material Symbols release SVGs, converted to `app/src/main/res/drawable/ic_*.xml` | Apache-2.0 |

Full primary license texts and ONNX Runtime's third-party notices are adjacent.
Model files are downloaded separately and never committed or bundled in the APK.
The three speech fixtures are generated locally with macOS system voices for device
acceptance; the repository contains their generation script, not audio recordings.

Reproducible native archive URLs and SHA-256 values are in `scripts/prepare-native.sh`.
The selected sherpa Kotlin files are copied unchanged. The tracked llama.cpp patch
contains synchronization exceptions during context and Vulkan backend teardown;
all other integration behavior resides in CaptionGlass's bindings. Android provides
the Vulkan loader/driver; the pinned shader compiler is built locally and is not
bundled in the APK.

Before public redistribution, audit the complete transitive license inventory of
the upstream sherpa all-feature JNI distribution and the Gradle dependency graph.
This internal M1 build does not claim that a public-release license audit is complete.

Nemotron license source: https://openmdw.ai/license/1-1/ (retrieved 2026-09-11).
The converted weights retain NVIDIA model origin; official sherpa export workflow
publishes the matched files under csukuangfj2. Catalog file URLs, hashes, and
revisions identify the exact separately downloaded artifacts.
