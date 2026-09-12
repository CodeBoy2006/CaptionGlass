#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Only DSP kernels and QAIC-generated C are cross-built in Linux. Android C/C++
# is compiled by the app's existing NDK, avoiding a second libc++ ABI/toolchain.
image='ghcr.io/snapdragon-toolchain/arm64-android@sha256:91714433626f0d94a926538a1e46ec43756c5b8e3262b91b95df1e812940aed1'
destination="$PWD/artifacts/deps/hexagon"
fingerprint=$(shasum -a 256 scripts/prepare-hexagon.sh scripts/patches/llama-hexagon-session.patch artifacts/deps/llama-source.tar.gz | shasum -a 256 | cut -d ' ' -f 1)
if [[ -f "$destination/verified" && "$(cat "$destination/verified")" == "$fingerprint" ]]; then exit 0; fi
mkdir -p "$destination"
rm -f "$destination/verified"
if [[ -d "$destination/include" ]]; then chmod -R u+w "$destination/include"; fi
docker run --rm --platform linux/amd64 --network none --entrypoint /bin/bash \
  --mount "type=bind,source=$PWD/artifacts/deps/llama-source,target=/source,readonly" \
  --mount "type=bind,source=$destination,target=/output" "$image" -lc '
set -euo pipefail
sdk=/opt/hexagon/6.6.0.0
cmake -S /source -B /tmp/hexagon-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=/opt/android-ndk-r29/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DPREBUILT_LIB_DIR=android_aarch64 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_BACKEND_DL=OFF \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_HEXAGON=ON \
  -DHEXAGON_SDK_ROOT="$sdk" -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF
cmake --build /tmp/hexagon-build --target htp_iface htp-v73 htp-v75 htp-v79 htp-v81 --parallel 4
mkdir -p /output/assets/hexagon /output/generated /output/include
cp /tmp/hexagon-build/ggml/src/ggml-hexagon/libggml-htp-v*.so /output/assets/hexagon/
cp /tmp/hexagon-build/ggml/src/ggml-hexagon/htp_iface.h /tmp/hexagon-build/ggml/src/ggml-hexagon/htp_iface_stub.c /output/generated/
cp -R "$sdk/incs" /output/include/
'
for arch in 73 75 79 81; do test -s "$destination/assets/hexagon/libggml-htp-v$arch.so"; done
printf '%s\n' "$fingerprint" > "$destination/verified"
