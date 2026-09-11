# CaptionGlass M1 third-party components

The application source is private and has no public distribution license yet.
The following upstream components keep their own licenses.

| Component | Pinned source | License |
| --- | --- | --- |
| sherpa-onnx Android JNI and Kotlin API | `k2-fsa/sherpa-onnx` commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf` (v1.13.8) | Apache-2.0 |
| ONNX Runtime, linked into sherpa JNI | v1.28.2, included in the hash-pinned sherpa Android archive | MIT and bundled third-party notices |
| llama.cpp / ggml CPU | `ggml-org/llama.cpp` commit `5266f24da75dc449bd56cbed7addb9c8e4a6a73e` (v0.4.0) | MIT |
| X-ASR Chinese/English model | `GilgameshWind/X-ASR-zh-en` revision in `models/catalog.json` | Apache-2.0 |
| PengChengStarling streaming 8-language ASR (sherpa int8 conversion) | `stdo/PengChengStarling`; pinned `csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10` revision `c6726c1147387ad2a11148b33973135d92a55e6c` | Apache-2.0 (upstream model card) |
| Hy-MT2 1.8B Q4_K_M model | `tencent/Hy-MT2-1.8B-GGUF` revision in `models/catalog.json` | Apache-2.0 |
| Material Symbols Rounded icons | Google Material Symbols release SVGs, converted to `app/src/main/res/drawable/ic_*.xml` | Apache-2.0 |

Full primary license texts and ONNX Runtime's third-party notices are adjacent.
Model files are downloaded separately and never committed or bundled in the APK.
The three speech fixtures are generated locally with macOS system voices for device
acceptance; the repository contains their generation script, not audio recordings.

Reproducible native archive URLs and SHA-256 values are in `scripts/prepare-native.sh`.
All modifications to integration behavior reside in CaptionGlass's bindings;
the selected sherpa Kotlin files and native runtime sources are copied unchanged.

Before public redistribution, audit the complete transitive license inventory of
the upstream sherpa all-feature JNI distribution and the Gradle dependency graph.
This internal M1 build does not claim that a public-release license audit is complete.
