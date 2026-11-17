#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_VERSION="29.0.14206865"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/$NDK_VERSION}"
export ANDROID_API=30
export ANDROID_ABI=arm64-v8a
export BUILD_JOBS="${BUILD_JOBS:-2}"

missing=()
for tool in python3 cmake ninja git make perl curl unzip tar gcc g++ pkg-config; do
    command -v "$tool" >/dev/null || missing+=("$tool")
done
if [ "${#missing[@]}" -ne 0 ]; then
    echo "[build] Missing WSL tools: ${missing[*]}" >&2
    echo "Install the missing packages in Ubuntu (build-essential provides gcc, g++, and make)." >&2
    exit 1
fi

toolchain="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -x "$toolchain/bin/aarch64-linux-android30-clang" ]; then
    echo "[build] Linux Android NDK r29 not found at $ANDROID_NDK_ROOT" >&2
    echo "Install ndk;$NDK_VERSION in WSL under ~/android-sdk, or set ANDROID_NDK_ROOT in WSL." >&2
    echo "The Windows NDK cannot be used for this native build." >&2
    exit 1
fi
if [ "${1:-}" = "--check" ]; then
    echo "[build] WSL native prerequisites ready."
    exit 0
fi
if [ "$#" -ne 0 ]; then
    echo "Usage: build-local-native.sh [--check]" >&2
    exit 1
fi

# Native builds stay on WSL's filesystem; only packaged output goes to the workspace.
workspace_key="$(printf '%s' "$REPO_DIR" | sha256sum | cut -c1-16)"
dependency_key="$(sha256sum "$REPO_DIR/scripts/build-android-deps.sh" "$ANDROID_NDK_ROOT/source.properties" | sha256sum | cut -d' ' -f1)"
cache="$HOME/.cache/bygdok-eternal/$workspace_key/$dependency_key"
export ANDROID_DEPS_PREFIX="$cache/android-deps"
export ANDROID_DEPS_SOURCE_DIR="$cache/android-deps-src"
export CORE_DIR="$cache/azerothcore"
export BUILD_DIR="$cache/server-build"
export INSTALL_DIR="$cache/server-install"
export OUTPUT_DIR="$REPO_DIR/runtime/build/bygdok-runtime-arm64"
export BYGDOK_MARIADB_RUNTIME_DIR="$REPO_DIR/runtime/build/mariadb-arm64"
export BYGDOK_ANDROID_MYSQL_ROOT="$ANDROID_DEPS_PREFIX/mysql"
export BYGDOK_ANDROID_RUNTIME_LIB_DIR="$ANDROID_DEPS_PREFIX/lib"
export CMAKE_BUILD_PARALLEL_LEVEL="$BUILD_JOBS"

if [ ! -f "$ANDROID_DEPS_PREFIX/.build-complete" ]; then
    bash "$REPO_DIR/scripts/build-android-deps.sh"
else
    echo "[build] Reusing local native dependencies."
fi

bash "$REPO_DIR/scripts/build-server-runtime.sh"
