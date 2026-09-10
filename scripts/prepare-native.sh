#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/deps
fetch() {
  local name="$1" hash="$2" url="$3" destination="$4"
  local archive="artifacts/deps/$name"
  if ! echo "$hash  $archive" | shasum -a 256 -c --status 2>/dev/null; then
    curl --fail --location --retry 3 "$url" -o "$archive.part"
    echo "$hash  $archive.part" | shasum -a 256 -c
    mv "$archive.part" "$archive"
  fi
  mkdir -p "$destination"
  tar -xf "$archive" -C "$destination" --strip-components=1
}
fetch sherpa-android.tar.bz2 7583ca385ae7d981e65468455c2ea2c9f2da383921dccfc5658d0dc19d309e6f \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android-static-link-onnxruntime.tar.bz2 artifacts/deps/sherpa-android
fetch sherpa-source.tar.gz 0a8db6c55dd318f4a688faba85f7760b99a6c92e8ef8864479d418531bee1ac2 \
  https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf artifacts/deps/sherpa-source
fetch llama-source.tar.gz 2de0d87eda4696e9f6bbd771d4c623267f4e95856cce6f99793f91522f993e43 \
  https://codeload.github.com/ggml-org/llama.cpp/tar.gz/5266f24da75dc449bd56cbed7addb9c8e4a6a73e artifacts/deps/llama-source
mkdir -p artifacts/deps/sherpa-kotlin
for file in OnlineRecognizer OnlineStream FeatureConfig HomophoneReplacerConfig QnnConfig; do
  cp "artifacts/deps/sherpa-source/sherpa-onnx/kotlin-api/$file.kt" artifacts/deps/sherpa-kotlin/
done
