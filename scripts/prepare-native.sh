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
fetch sherpa-source.tar.gz 0a8db6c55dd318f4a688faba85f7760b99a6c92e8ef8864479d418531bee1ac2 \
  https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf artifacts/deps/sherpa-source
fetch llama-source.tar.gz 2de0d87eda4696e9f6bbd771d4c623267f4e95856cce6f99793f91522f993e43 \
  https://codeload.github.com/ggml-org/llama.cpp/tar.gz/5266f24da75dc449bd56cbed7addb9c8e4a6a73e artifacts/deps/llama-source
patch --batch -p1 -d artifacts/deps/llama-source < scripts/patches/llama-vulkan-cleanup.patch
patch --batch -p1 -d artifacts/deps/llama-source < scripts/patches/llama-hexagon-session.patch
fetch opencl-headers.tar.gz fa754f59be79a59e2870712c21619803259b03008c1d82e5eaaa74a3e36b0b72 \
  https://codeload.github.com/KhronosGroup/OpenCL-Headers/tar.gz/4ea6df132107e3b4b9407f903204b5522fdffcd6 artifacts/deps/opencl-headers
fetch opencl-loader.tar.gz 1def7925feee07af69c0babe65a350a5ce0c59e70e781c4a4a1a562810212f13 \
  https://codeload.github.com/KhronosGroup/OpenCL-ICD-Loader/tar.gz/5907ac1114079de4383cecddf1c8640e3f52f92b artifacts/deps/opencl-loader
patch --batch -p1 -d artifacts/deps/opencl-loader < scripts/patches/opencl-android-driver.patch
fetch vulkan-headers.tar.gz 17f8ff30fd79fb7531efcb7c78c02c17a595208d482a150f06836b0ca97ef8f2 \
  https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/refs/tags/vulkan-sdk-1.4.321.0 artifacts/deps/vulkan-headers
fetch spirv-headers-shaderc.tar.gz c2225a49c3d7efa5c4f4ce4a6b42081e6ea3daca376f3353d9d7c2722d77a28a \
  https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/2a611a970fdbc41ac2e3e328802aed9985352dca artifacts/deps/spirv-headers
fetch shaderc-source.tar.gz a8e4a25e5c2686fd36981e527ed05e451fcfc226bddf350f4e76181371190937 \
  https://codeload.github.com/google/shaderc/tar.gz/refs/tags/v2025.3 artifacts/deps/shaderc-source
fetch glslang-source.tar.gz 9427deccbdf4bde6a269938df38c6bd75247493786a310d8d733a2c82065ef47 \
  https://codeload.github.com/KhronosGroup/glslang/tar.gz/efd24d75bcbc55620e759f6bf42c45a32abac5f8 artifacts/deps/shaderc-source/third_party/glslang
fetch spirv-tools-source.tar.gz 44d1005880c583fc00a0fb41c839214c68214b000ea8dcb54d352732fee600ff \
  https://codeload.github.com/KhronosGroup/SPIRV-Tools/tar.gz/33e02568181e3312f49a3cf33df470bf96ef293a artifacts/deps/shaderc-source/third_party/spirv-tools
# Install only header metadata locally so upstream find_package works when cross-compiling.
cmake_command="${ANDROID_HOME:?Set ANDROID_HOME}/cmake/3.22.1/bin/cmake"
"$cmake_command" -S artifacts/deps/spirv-headers -B artifacts/deps/spirv-headers-build \
  -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DCMAKE_INSTALL_PREFIX="$PWD/artifacts/deps/spirv-headers-install"
"$cmake_command" --install artifacts/deps/spirv-headers-build
# The NDK's older glslc cannot compile cooperative matrices used by mobile GPUs.
# These are shaderc v2025.3's matched DEPS, without its test-only dependencies.
"$cmake_command" -S artifacts/deps/shaderc-source -B artifacts/deps/shaderc-build -G Ninja \
  -DCMAKE_MAKE_PROGRAM="${ANDROID_HOME}/cmake/3.22.1/bin/ninja" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
  -DSHADERC_SPIRV_HEADERS_DIR="$PWD/artifacts/deps/spirv-headers" \
  -DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
  -DSHADERC_SKIP_INSTALL=OFF
"$cmake_command" --build artifacts/deps/shaderc-build --target glslc_exe --parallel 4
bash scripts/prepare-sherpa.sh
bash scripts/prepare-hexagon.sh
