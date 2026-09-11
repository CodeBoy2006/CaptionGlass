#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source_dir=artifacts/deps/sherpa-source
patch_file="$PWD/scripts/patches/sherpa-qwen-completeness.patch"
if ! patch --dry-run --reverse -p1 -d "$source_dir" < "$patch_file" >/dev/null 2>&1; then
  patch --batch -p1 -d "$source_dir" < "$patch_file"
fi
sdk="${ANDROID_HOME:?Set ANDROID_HOME}"
cmake_command="$sdk/cmake/3.22.1/bin/cmake"
# Match upstream’s CPU-only Android 21 library build; the app still requires API 29.
"$cmake_command" -S "$source_dir" -B artifacts/deps/sherpa-build -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$sdk/cmake/3.22.1/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$sdk/ndk/27.1.12297006/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DSHERPA_ONNX_ENABLE_JNI=ON \
  -DSHERPA_ONNX_ENABLE_BINARY=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
  -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
  -DSHERPA_ONNX_ENABLE_C_API=OFF -DSHERPA_ONNX_ENABLE_TESTS=OFF \
  -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=OFF \
  -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
"$cmake_command" --build artifacts/deps/sherpa-build --target sherpa-onnx-jni --parallel 4
mkdir -p artifacts/deps/sherpa-android/jniLibs/arm64-v8a artifacts/deps/sherpa-kotlin
cp artifacts/deps/sherpa-build/lib/libsherpa-onnx-jni.so artifacts/deps/sherpa-android/jniLibs/arm64-v8a/
for file in OnlineRecognizer OnlineStream OfflineRecognizer OfflineStream Vad FeatureConfig HomophoneReplacerConfig QnnConfig; do
  cp "$source_dir/sherpa-onnx/kotlin-api/$file.kt" artifacts/deps/sherpa-kotlin/
done
