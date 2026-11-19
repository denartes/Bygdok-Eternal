#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="${CORE_DIR:-${RUNNER_TEMP:-/tmp}/azerothcore-android}"
BUILD_DIR="${BUILD_DIR:-${RUNNER_TEMP:-/tmp}/azerothcore-build}"
INSTALL_DIR="${INSTALL_DIR:-${REPO_DIR}/runtime/build/server-install}"
OUTPUT_DIR="${OUTPUT_DIR:-${REPO_DIR}/runtime/build/bygdok-runtime-arm64}"
CORE_COMMIT="abc884520173084d5cd37b72b57b3822230dcb32"
CORE_REPOSITORY="https://github.com/duall/azerothcore-android.git"
ANDROID_API="${ANDROID_API:-30}"

: "${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT must point to Android NDK r29}"
ANDROID_MYSQL_ROOT="${ANDROID_MYSQL_ROOT:-${BYGDOK_ANDROID_MYSQL_ROOT:-}}"
ANDROID_RUNTIME_LIB_DIR="${ANDROID_RUNTIME_LIB_DIR:-${BYGDOK_ANDROID_RUNTIME_LIB_DIR:-}}"
MARIADB_RUNTIME_DIR="${MARIADB_RUNTIME_DIR:-${BYGDOK_MARIADB_RUNTIME_DIR:-}}"
: "${ANDROID_MYSQL_ROOT:?BYGDOK_ANDROID_MYSQL_ROOT must point to an Android ARM64 MariaDB/MySQL client toolchain}"
: "${ANDROID_RUNTIME_LIB_DIR:?BYGDOK_ANDROID_RUNTIME_LIB_DIR must contain Android ARM64 runtime libraries}"
: "${MARIADB_RUNTIME_DIR:?BYGDOK_MARIADB_RUNTIME_DIR must point to the validated MariaDB runtime artifact}"

TOOLCHAIN_DIR="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
ANDROID_CLANG="${TOOLCHAIN_DIR}/bin/aarch64-linux-android${ANDROID_API}-clang"
ANDROID_CLANGXX="${TOOLCHAIN_DIR}/bin/aarch64-linux-android${ANDROID_API}-clang++"
ANDROID_READELF="${TOOLCHAIN_DIR}/bin/llvm-readelf"

for required in "$ANDROID_CLANG" "$ANDROID_CLANGXX" "$ANDROID_READELF" "$ANDROID_MYSQL_ROOT/bin/mysql_config"; do
    test -x "$required" || { echo "Missing required Android build tool: $required" >&2; exit 1; }
done
for required in \
    "$ANDROID_MYSQL_ROOT/../include" \
    "$ANDROID_MYSQL_ROOT/../lib/libmariadb.so" \
    "$ANDROID_MYSQL_ROOT/../lib/libssl.so" \
    "$ANDROID_MYSQL_ROOT/../lib/libcrypto.so" \
    "$ANDROID_MYSQL_ROOT/../lib/ossl-modules/legacy.so" \
    "$ANDROID_MYSQL_ROOT/../lib/libreadline.so" \
    "$ANDROID_MYSQL_ROOT/../lib/libncurses.so" \
    "$ANDROID_RUNTIME_LIB_DIR"; do
    test -e "$required" || { echo "Missing staged Android dependency: $required" >&2; exit 1; }
done

rm -rf "$INSTALL_DIR" "$OUTPUT_DIR"
# actions/cache only reports cache-hit=true on an exact key match; a
# restore-keys prefix match still restores these directories but reports
# cache-hit=false, so reuse is decided by directory validity, not the flag.
if [ -d "$CORE_DIR/.git" ] && [ -f "$BUILD_DIR/CMakeCache.txt" ]; then
    echo "[server] Reusing restored AzerothCore source and build directory"
else
    echo "[server] No compatible AzerothCore build cache; performing clean checkout"
    rm -rf "$CORE_DIR" "$BUILD_DIR"
    git clone "$CORE_REPOSITORY" "$CORE_DIR"
    git -C "$CORE_DIR" fetch --depth=1 origin "$CORE_COMMIT"
    git -C "$CORE_DIR" checkout --detach "$CORE_COMMIT"
fi

for patch_file in "$REPO_DIR"/patches/azerothcore-android/*.patch; do
    test -f "$patch_file" || continue
    if git -C "$CORE_DIR" apply --check --ignore-space-change "$patch_file"; then
        git -C "$CORE_DIR" apply --ignore-space-change "$patch_file"
    elif ! git -C "$CORE_DIR" apply --reverse --check --ignore-space-change "$patch_file"; then
        echo "AzerothCore patch cannot be applied cleanly: $patch_file" >&2
        exit 1
    fi
done

# Always replace generated module copies so local edits and removals also
# take effect when reusing an existing server build directory.
python3 "$REPO_DIR/scripts/stage-server-modules.py" "$CORE_DIR/modules"

# Match the Boost compatibility patch used by wowsp_cutoff.sh.
BOOST_CMAKE="$CORE_DIR/deps/boost/CMakeLists.txt"
sed -i -E 's/ system / /g; s/ system$//g; s/^system //g' "$BOOST_CMAKE"
cmake_version="$(cmake --version | sed -n '1s/.*version \([0-9]*\.[0-9]*\).*/\1/p')"
cmake_major="${cmake_version%%.*}"
cmake_minor="${cmake_version#*.}"
if [ "$cmake_major" -gt 3 ] || { [ "$cmake_major" -eq 3 ] && [ "$cmake_minor" -ge 30 ]; }; then
    grep -q 'CMP0167' "$BOOST_CMAKE" || sed -i '1i cmake_policy(SET CMP0167 OLD)' "$BOOST_CMAKE"
else
    sed -i '/cmake_policy(SET CMP0167 OLD)/d' "$BOOST_CMAKE"
fi

export PATH="$ANDROID_MYSQL_ROOT/bin:$PATH"
export MYSQL_HOME="$ANDROID_MYSQL_ROOT"
export CMAKE_PREFIX_PATH="$ANDROID_MYSQL_ROOT/..:$ANDROID_MYSQL_ROOT:$ANDROID_RUNTIME_LIB_DIR"
BOOST_ROOT="$(cd "$ANDROID_MYSQL_ROOT/.." && pwd)"
BOOST_INCLUDEDIR="$BOOST_ROOT/include"
BOOST_LIBRARYDIR="$BOOST_ROOT/lib"
OPENSSL_ROOT="$BOOST_ROOT"
OPENSSL_INCLUDE_DIR="$OPENSSL_ROOT/include"
OPENSSL_CRYPTO_LIBRARY="$OPENSSL_ROOT/lib/libcrypto.so"
OPENSSL_SSL_LIBRARY="$OPENSSL_ROOT/lib/libssl.so"
PTHREAD_LIBRARY="$BOOST_ROOT/lib/libpthread.so"
test -f "$BOOST_INCLUDEDIR/boost/version.hpp" || { echo "Missing staged Boost headers: $BOOST_INCLUDEDIR/boost/version.hpp" >&2; exit 1; }
test -n "$(find "$BOOST_LIBRARYDIR" -maxdepth 1 -name 'libboost*' -print -quit)" || { echo "Missing staged Boost libraries: $BOOST_LIBRARYDIR" >&2; exit 1; }
test -f "$OPENSSL_INCLUDE_DIR/openssl/ssl.h" || { echo "Missing staged OpenSSL headers: $OPENSSL_INCLUDE_DIR/openssl/ssl.h" >&2; exit 1; }
test -f "$OPENSSL_CRYPTO_LIBRARY" || { echo "Missing staged OpenSSL crypto library: $OPENSSL_CRYPTO_LIBRARY" >&2; exit 1; }
test -f "$OPENSSL_SSL_LIBRARY" || { echo "Missing staged OpenSSL SSL library: $OPENSSL_SSL_LIBRARY" >&2; exit 1; }
test -e "$PTHREAD_LIBRARY" || { echo "Missing staged Android pthread linker alias: $PTHREAD_LIBRARY" >&2; exit 1; }
echo "[server] Staged Boost headers:"
find "$BOOST_INCLUDEDIR" -path '*boost/version.hpp' -print
echo "[server] Staged Boost libraries:"
find "$BOOST_LIBRARYDIR" -maxdepth 1 -name 'libboost*' -print
mkdir -p "$BUILD_DIR" "$INSTALL_DIR"
cmake -S "$CORE_DIR" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${ANDROID_ABI:-arm64-v8a}" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
    -DCMAKE_C_COMPILER="$ANDROID_CLANG" \
    -DCMAKE_CXX_COMPILER="$ANDROID_CLANGXX" \
    -DANDROID_STL=c++_shared \
    -DBoost_ROOT="$BOOST_ROOT" \
    -DBoost_INCLUDE_DIR="$BOOST_INCLUDEDIR" \
    -DBoost_LIBRARY_DIR="$BOOST_LIBRARYDIR" \
    -DBOOST_ROOT="$BOOST_ROOT" \
    -DBOOST_INCLUDEDIR="$BOOST_INCLUDEDIR" \
    -DBOOST_LIBRARYDIR="$BOOST_LIBRARYDIR" \
    -DBoost_NO_SYSTEM_PATHS=ON \
    -DBoost_USE_STATIC_LIBS=ON \
    -DBoost_USE_MULTITHREADED=ON \
    -DMYSQL_CONFIG="$ANDROID_MYSQL_ROOT/bin/mysql_config" \
    -DMYSQL_INCLUDE_DIR="$ANDROID_MYSQL_ROOT/../include" \
    -DMYSQL_LIBRARY="$ANDROID_MYSQL_ROOT/../lib/libmariadb.so" \
    -DOPENSSL_ROOT_DIR="$OPENSSL_ROOT" \
    -DOPENSSL_INCLUDE_DIR="$OPENSSL_INCLUDE_DIR" \
    -DOPENSSL_CRYPTO_LIBRARY="$OPENSSL_CRYPTO_LIBRARY" \
    -DOPENSSL_SSL_LIBRARY="$OPENSSL_SSL_LIBRARY" \
    -DREADLINE_INCLUDE_DIR="$ANDROID_MYSQL_ROOT/../include" \
    -DREADLINE_LIBRARY="$ANDROID_MYSQL_ROOT/../lib/libreadline.so" \
    -DCURSES_LIBRARY="$ANDROID_MYSQL_ROOT/../lib/libncurses.so" \
    -DCMAKE_LIBRARY_PATH="$ANDROID_MYSQL_ROOT/../lib" \
    -DWITH_WARNINGS=1 -DTOOLS_BUILD=none -DSCRIPTS=static -DNOJEM=ON \
    -DCMAKE_CXX_FLAGS="-D__ANDROID__ -DANDROID -Wno-deprecated-literal-operator" \
    -DCMAKE_EXE_LINKER_FLAGS="-L$BOOST_LIBRARYDIR -L$ANDROID_MYSQL_ROOT/../lib -Wl,--allow-multiple-definition -lunwind -lncurses"
cmake --build "$BUILD_DIR" --parallel
cmake --install "$BUILD_DIR"

mkdir -p "$OUTPUT_DIR/bin" "$OUTPUT_DIR/lib" "$OUTPUT_DIR/sql" "$OUTPUT_DIR/share"
cp "$REPO_DIR/modules/manifest.json" "$OUTPUT_DIR/modules.json"
cp "$INSTALL_DIR/bin/authserver" "$OUTPUT_DIR/bin/"
cp "$INSTALL_DIR/bin/worldserver" "$OUTPUT_DIR/bin/"

cp "$MARIADB_RUNTIME_DIR/bin/mariadbd" "$OUTPUT_DIR/bin/"
cp "$MARIADB_RUNTIME_DIR/bin/mariadb" "$OUTPUT_DIR/bin/mariadb_client"
chmod +x "$OUTPUT_DIR/bin/mariadbd" "$OUTPUT_DIR/bin/mariadb_client"
cp -a "$MARIADB_RUNTIME_DIR/lib/." "$OUTPUT_DIR/lib/"
cp -a "$MARIADB_RUNTIME_DIR/share/." "$OUTPUT_DIR/share/"
cp "$ANDROID_MYSQL_ROOT/../lib/ossl-modules/legacy.so" "$OUTPUT_DIR/lib/legacy.so"

# The packaged Pocket Realm provider uses APK-safe dependency filenames. Keep
# canonical SONAME copies in the combined runtime so AzerothCore's ELF
# dependency closure and non-Android tooling can resolve the original names.
python3 - "$OUTPUT_DIR/lib" "$OUTPUT_DIR/share/runtime-manifest.json" <<'PY'
import json
import pathlib
import shutil
import sys

library_dir = pathlib.Path(sys.argv[1])
manifest = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
for entry in manifest.get("links", []):
    source = library_dir / entry["apk_name"]
    destination = library_dir / entry["logical"]
    if not source.is_file():
        raise SystemExit(f"Missing provider library: {source}")
    shutil.copyfile(source, destination)
PY

# Mirror the SQL update tree so the on-device AzerothCore updater can find
# base/update SQL via SourceDirectory, matching how the source checkout is
# laid out for authserver/worldserver's own DBUpdater.
if [ -d "$CORE_DIR/data/sql" ]; then
    mkdir -p "$OUTPUT_DIR/sql/data"
    cp -R "$CORE_DIR/data/sql" "$OUTPUT_DIR/sql/data/"
fi
for module_dir in "$CORE_DIR"/modules/*; do
    test -d "$module_dir" || continue
    module_name="$(basename "$module_dir")"
    data_sql_dir="$module_dir/data/sql"
    if [ -d "$data_sql_dir" ]; then
        mkdir -p "$OUTPUT_DIR/sql/modules/$module_name/data"
        cp -R "$data_sql_dir" "$OUTPUT_DIR/sql/modules/$module_name/data/"
    fi
    legacy_sql_dir="$module_dir/sql"
    if [ -d "$legacy_sql_dir" ]; then
        mkdir -p "$OUTPUT_DIR/sql/modules/$module_name/sql"
        cp -R "$legacy_sql_dir/." "$OUTPUT_DIR/sql/modules/$module_name/sql/"
    fi
done

for binary in "$OUTPUT_DIR/bin/authserver" "$OUTPUT_DIR/bin/worldserver" "$OUTPUT_DIR/bin/mariadbd"; do
    test -f "$binary" || continue
    while read -r library; do
        case "$library" in
            libc.so|libdl.so|liblog.so|libm.so|libandroid.so|libc++abi.so) continue ;;
        esac
        found="$(find "$OUTPUT_DIR/lib" "$MARIADB_RUNTIME_DIR/lib" "$ANDROID_RUNTIME_LIB_DIR" "$TOOLCHAIN_DIR/sysroot/usr/lib/aarch64-linux-android" -name "$library" \( -type f -o -type l \) -print -quit)"
        test -n "$found" || { echo "Required runtime library not found: $library" >&2; exit 1; }
        cp -nL "$found" "$OUTPUT_DIR/lib/"
    done < <("$ANDROID_READELF" -d "$binary" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' | sort -u)
done

for binary in "$OUTPUT_DIR/bin/authserver" "$OUTPUT_DIR/bin/worldserver" "$OUTPUT_DIR/bin/mariadbd"; do
    test -f "$binary" || continue
    while read -r library; do
        case "$library" in
            libc.so|libdl.so|liblog.so|libm.so|libandroid.so|libc++abi.so) continue ;;
        esac
        test -f "$OUTPUT_DIR/lib/$library" || { echo "Runtime artifact is missing $library required by $binary" >&2; exit 1; }
    done < <("$ANDROID_READELF" -d "$binary" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' | sort -u)
done

timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
python3 - "$OUTPUT_DIR/manifest.json" "$timestamp" "$ANDROID_API" "$REPO_DIR" <<'PY'
import json
import pathlib
import subprocess
import sys

output = pathlib.Path(sys.argv[1])
manifest = {
    "git_commit": subprocess.check_output(["git", "-C", sys.argv[4], "rev-parse", "HEAD"], text=True).strip(),
    "build_timestamp": sys.argv[2],
    "target_architecture": "arm64-v8a",
    "android_api": int(sys.argv[3]),
    "binaries": sorted(path.name for path in (output.parent / "bin").iterdir()),
    "shared_libraries": sorted(path.name for path in (output.parent / "lib").iterdir()),
}
output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY