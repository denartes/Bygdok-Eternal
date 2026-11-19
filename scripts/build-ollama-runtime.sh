#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT must point to Android NDK r29}"
OLLAMA_COMMIT=8c8f8f3450d39735355fc6cd7f2e436c8aa42ab1
OLLAMA_VERSION=0.20.3
CACHE="${OLLAMA_BUILD_ROOT:-${HOME}/.cache/bygdok-ollama/${OLLAMA_COMMIT}}"
OUTPUT="${OLLAMA_OUTPUT_DIR:-${REPO_DIR}/runtime/build/ollama-arm64}"
TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
CC="${TOOLCHAIN}/bin/aarch64-linux-android30-clang"
CXX="${TOOLCHAIN}/bin/aarch64-linux-android30-clang++"
test -x "$CC"
mkdir -p "$CACHE" "$OUTPUT/bin" "$OUTPUT/lib" "$OUTPUT/licenses"

# Keep the build independent of an installed Go version.
GO_VERSION=1.25.0
GO_SHA256=2852af0cb20a13139b3448992e69b868e50ed0f8a1e5940ee1de9e19a123b613
if [ ! -x "$CACHE/go/bin/go" ]; then
    curl --fail --location --retry 3 "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" -o "$CACHE/go.tar.gz"
    printf '%s  %s\n' "$GO_SHA256" "$CACHE/go.tar.gz" | sha256sum --check
    tar -xzf "$CACHE/go.tar.gz" -C "$CACHE"
fi
export PATH="$CACHE/go/bin:$PATH"
export GOTOOLCHAIN=local
export GOCACHE="$CACHE/go-cache"
export GOMODCACHE="$CACHE/go-modules"

SOURCE="$CACHE/source"
if [ ! -d "$SOURCE/.git" ]; then
    git clone --depth 1 --branch "v${OLLAMA_VERSION}" https://github.com/ollama/ollama.git "$SOURCE"
fi
test "$(git -C "$SOURCE" rev-parse HEAD)" = "$OLLAMA_COMMIT"
for patch in "$REPO_DIR"/patches/ollama/*.patch; do
    if git -C "$SOURCE" apply --check "$patch" 2>/dev/null; then
        git -C "$SOURCE" apply "$patch"
    elif ! git -C "$SOURCE" apply --reverse --check "$patch"; then
        echo "[ollama] Patch no longer applies: $patch" >&2
        exit 1
    fi
done

HEADERS="$CACHE/vulkan-headers"
ADRENOTOOLS_COMMIT=8fae8ce254dfc1344527e05301e43f37dea2df80
ADRENOTOOLS="$CACHE/adrenotools"
if [ ! -d "$ADRENOTOOLS/.git" ]; then
    git clone https://github.com/bylaws/libadrenotools.git "$ADRENOTOOLS"
    git -C "$ADRENOTOOLS" checkout --detach "$ADRENOTOOLS_COMMIT"
    git -C "$ADRENOTOOLS" submodule update --init --recursive
fi
test "$(git -C "$ADRENOTOOLS" rev-parse HEAD)" = "$ADRENOTOOLS_COMMIT"
test "$(git -C "$ADRENOTOOLS/lib/linkernsbypass" rev-parse HEAD)" = aa3975893d83ef1bc84c321ec60c65fbf1287887
cmake -S "$ADRENOTOOLS" -B "$CACHE/adrenotools-build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON
cmake --build "$CACHE/adrenotools-build" --parallel "${BUILD_JOBS:-2}"
cp "$REPO_DIR/scripts/ollama-vulkan-driver.h" \
    "$SOURCE/ml/backend/ggml/ggml/src/ggml-vulkan/bygdok-vulkan-driver.h"
if [ ! -d "$HEADERS/.git" ]; then
    git clone --depth 1 --branch v1.4.309 https://github.com/KhronosGroup/Vulkan-Headers.git "$HEADERS"
fi
test "$(git -C "$HEADERS" rev-parse HEAD)" = 952f776f6573aafbb62ea717d871cd1d6816c387
export PATH="$ANDROID_NDK_ROOT/shader-tools/linux-x86_64:$PATH"
cmake -S "$SOURCE" -B "$CACHE/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_shared \
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_VULKAN=ON \
    "-DCMAKE_CXX_FLAGS=-I$ADRENOTOOLS/include" \
    -DVulkan_INCLUDE_DIR="$HEADERS/include" \
    -DVulkan_GLSLC_EXECUTABLE="$ANDROID_NDK_ROOT/shader-tools/linux-x86_64/glslc" \
    -DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN="$REPO_DIR/scripts/ollama-host-toolchain.cmake" \
    -DCMAKE_DISABLE_FIND_PACKAGE_Vulkan=OFF \
    -DCMAKE_DISABLE_FIND_PACKAGE_CUDAToolkit=ON \
    -DCMAKE_CUDA_COMPILER=NOTFOUND -DCMAKE_HIP_COMPILER=NOTFOUND
cmake --build "$CACHE/build" --parallel "${BUILD_JOBS:-2}"

(
    cd "$SOURCE"
    export GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC CXX
    export CGO_LDFLAGS="-lc++ -ldl -lm"
    go build -p "${BUILD_JOBS:-2}" -trimpath \
        -ldflags="-w -s -X=github.com/ollama/ollama/version.Version=${OLLAMA_VERSION} -X=github.com/ollama/ollama/server.mode=release" \
        -o "$OUTPUT/bin/ollama" .
)
# The Go executable already includes CPU support. Loading a second CPU backend
# on Android produces incorrect results, so do not package that plugin.
rm -f "$OUTPUT/lib/libggml-cpu.so" "$OUTPUT/lib/libggml.so"
cp "$CACHE/build/lib/ollama/libggml-base.so" "$CACHE/build/lib/ollama/libggml-vulkan.so" "$OUTPUT/lib/"
cp "$CACHE/adrenotools-build/libadrenotools.so" "$OUTPUT/lib/"
cp "$CACHE/adrenotools-build/src/hook/"*.so "$OUTPUT/lib/"
cp "$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$OUTPUT/lib/"
cp "$SOURCE/LICENSE" "$OUTPUT/licenses/ollama-LICENSE"
cp "$SOURCE/ml/backend/ggml/ggml/LICENSE" "$OUTPUT/licenses/ggml-LICENSE"
cp "$HEADERS/LICENSE.md" "$OUTPUT/licenses/vulkan-headers-LICENSE"
cp "$ADRENOTOOLS/LICENSE" "$OUTPUT/licenses/adrenotools-LICENSE"
cp "$ADRENOTOOLS/lib/linkernsbypass/LICENSE" "$OUTPUT/licenses/linkernsbypass-LICENSE"
"$TOOLCHAIN/bin/llvm-readelf" -h "$OUTPUT/bin/ollama" | grep -q AArch64
printf '{"version":"%s","commit":"%s","backend":"vulkan,cpu","abi":"arm64-v8a"}\n' \
    "$OLLAMA_VERSION" "$OLLAMA_COMMIT" > "$OUTPUT/manifest.json"
echo "[ollama] Android runtime ready: $OUTPUT"
