#!/usr/bin/env bash
# 拉取并校验依赖锁定文件。依赖版本、尺寸与 SHA-256 均来自 dependencies.lock.json。
set -euo pipefail

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cache_dir="$project_root/.temp/dependencies"
vendor_dir="$project_root/windows-controller/vendor"
mkdir -p "$cache_dir" "$vendor_dir"

fetch_locked() {
    url=$1
    output=$2
    expected_size=$3
    expected_sha=$4
    if [ ! -f "$output" ]; then
        partial="$output.partial.$$"
        curl --fail --location --proto '=https' --max-time 120 --output "$partial" "$url"
        verify_locked "$partial" "$expected_size" "$expected_sha"
        mv "$partial" "$output"
    fi
    verify_locked "$output" "$expected_size" "$expected_sha"
}

verify_locked() {
    file=$1
    expected_size=$2
    expected_sha=$3
    actual_size=$(wc -c < "$file" | tr -d '[:space:]')
    [ "$actual_size" = "$expected_size" ] || {
        echo "size mismatch for $file: expected $expected_size, got $actual_size" >&2
        return 1
    }
    actual_sha=$(shasum -a 256 "$file" | awk '{print $1}')
    [ "$actual_sha" = "$expected_sha" ] || {
        echo "SHA-256 mismatch for $file: expected $expected_sha, got $actual_sha" >&2
        return 1
    }
}

mbedtls_archive="$cache_dir/mbedtls-3.6.7.tar.gz"
scrcpy_server="$cache_dir/scrcpy-server-v4.1"
scrcpy_license="$cache_dir/scrcpy-LICENSE-v4.1"
platform_tools_windows="$cache_dir/platform-tools_r37.0.1-win.zip"
fetch_locked \
    "https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/mbedtls-3.6.7.tar.gz" \
    "$mbedtls_archive" 5678976 \
    807f0a1cc7c63b571180e9efeb6236b677e550869eb0442ace33aa9c69055fcc
fetch_locked \
    "https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-server-v4.1" \
    "$scrcpy_server" 733706 \
    deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae
fetch_locked \
    "https://raw.githubusercontent.com/Genymobile/scrcpy/v4.1/LICENSE" \
    "$scrcpy_license" 11387 \
    01c12035bf35af37241298dc7ad538eb2a07e5c940437bc6876feeaa9d1951d0
fetch_locked \
    "https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip" \
    "$platform_tools_windows" 8044989 \
    45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99
fetch_locked \
    "https://dl.google.com/android/repository/repository2-1.xml" \
    "$cache_dir/repository2-1.xml" 367919 \
    ea0509d1f955495ed543d9b16edfb55758fc3df5177210b043580e6d563d0b32

# 从仓库元数据提取 Android SDK License 文本，作为 platform-tools 的随包许可
android_sdk_license="$vendor_dir/android-sdk-license.txt"
python3 - "$cache_dir/repository2-1.xml" "$android_sdk_license" <<'PY'
import re
import sys

source, target = sys.argv[1:3]
with open(source, encoding="utf-8") as handle:
    xml = handle.read()
match = re.search(r'<license id="android-sdk-license"[^>]*>(.*?)</license>', xml, re.S)
if not match:
    print(f"android-sdk-license node not found in {source}", file=sys.stderr)
    sys.exit(1)
text = re.sub(r"<[^>]+>", "", match.group(1)).strip() + "\n"
with open(target, "w", encoding="utf-8") as handle:
    handle.write(text)
print(f"wrote {len(text)} bytes to {target}")
PY
[ "$(wc -c < "$android_sdk_license" | tr -d '[:space:]')" = "16962" ] || {
    echo "android-sdk-license.txt size mismatch" >&2
    exit 1
}

mbedtls_target="$vendor_dir/mbedtls-3.6.7"
if [ ! -d "$mbedtls_target" ]; then
    if tar -tzf "$mbedtls_archive" | awk 'BEGIN { bad=0 } /(^\/|(^|\/)\.\.($|\/))/ { bad=1 } END { exit bad }'; then
        staging="$vendor_dir/.mbedtls-3.6.7-staging.$$"
        mkdir -p "$staging"
        tar -xzf "$mbedtls_archive" --strip-components=1 -C "$staging"
        printf '%s\n' 807f0a1cc7c63b571180e9efeb6236b677e550869eb0442ace33aa9c69055fcc > "$staging/.tvrc-source-sha256"
        mv "$staging" "$mbedtls_target"
    else
        echo "unsafe path in mbedTLS archive" >&2
        exit 1
    fi
fi
[ -f "$mbedtls_target/.tvrc-source-sha256" ] || {
    echo "mbedTLS source marker is missing: $mbedtls_target/.tvrc-source-sha256" >&2
    exit 1
}
[ "$(tr -d '\r\n' < "$mbedtls_target/.tvrc-source-sha256")" = "807f0a1cc7c63b571180e9efeb6236b677e550869eb0442ace33aa9c69055fcc" ] || {
    echo "mbedTLS source marker does not match the dependency lock" >&2
    exit 1
}
[ -s "$mbedtls_target/LICENSE" ] || { echo "mbedTLS license is missing" >&2; exit 1; }
[ -s "$mbedtls_target/include/mbedtls/version.h" ] || { echo "mbedTLS extracted tree is incomplete" >&2; exit 1; }

scrcpy_target="$vendor_dir/scrcpy-server-v4.1"
# vendor 中已有文件也必须通过 SHA-256 校验；不匹配时从缓存刷新，而不是静默跳过
if ! verify_locked "$scrcpy_target" 733706 deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae; then
    echo "vendor scrcpy server 校验失败，从缓存刷新: $scrcpy_target" >&2
    cp "$scrcpy_server" "$scrcpy_target"
fi
verify_locked "$scrcpy_target" 733706 deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae
scrcpy_license_target="$vendor_dir/scrcpy-LICENSE-v4.1"
if ! verify_locked "$scrcpy_license_target" 11387 01c12035bf35af37241298dc7ad538eb2a07e5c940437bc6876feeaa9d1951d0; then
    echo "vendor scrcpy LICENSE 校验失败，从缓存刷新: $scrcpy_license_target" >&2
    cp "$scrcpy_license" "$scrcpy_license_target"
fi
verify_locked "$scrcpy_license_target" 11387 01c12035bf35af37241298dc7ad538eb2a07e5c940437bc6876feeaa9d1951d0

echo "locked dependencies are ready under $vendor_dir"
