#!/bin/sh
set -eu

# 统一门禁：Android(app+controller) + Rust + Zig(Windows 交叉) + 小程序；Darwin 上另跑 Swift/macOS。
# 重型构建在专用 Linux 构建机上执行（见 CONTRIBUTING）。

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
android_dir="$project_root/android-agent"
windows_dir="$project_root/windows-controller"
core_dir="$project_root/core-rs"
macos_dir="$project_root/macos-controller"
quality_temp="$project_root/.temp/quality-gates"
mkdir -p "$quality_temp"

# 需要 JAVA_HOME 指向 JDK（CI 使用最新 LTS：JDK 25）。
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -d "$project_root/.temp/jdk/Contents/Home" ]; then
        JAVA_HOME="$project_root/.temp/jdk/Contents/Home"
    elif [ -d "$project_root/.temp/jdk17/Contents/Home" ]; then
        JAVA_HOME="$project_root/.temp/jdk17/Contents/Home"
    else
        echo "JAVA_HOME must point to a JDK (CI uses the latest LTS, JDK 25)" >&2
        exit 1
    fi
fi
export JAVA_HOME
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$android_dir/.temp/gradle-user-home}"

(cd "$android_dir" && ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease)
(cd "$android_dir" && ./gradlew --no-daemon :controller:testDebugUnitTest :controller:lintDebug :controller:assembleDebug)

(cd "$core_dir" && cargo test --locked)
(cd "$core_dir" && cargo clippy --locked -- -D warnings)

export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$windows_dir/.temp/zig-global-cache}"
export ZIG_LOCAL_CACHE_DIR="$windows_dir/.temp/zig-local-cache"
(cd "$windows_dir" && zig build test)
for target in x86-windows-gnu x86_64-windows-gnu aarch64-windows-gnu; do
    (cd "$windows_dir" && zig build -Doptimize=ReleaseSafe -Dtarget="$target" --prefix "$quality_temp/$target")
done

for module in core wechat alipay; do
    (cd "$project_root/miniprograms/$module" && npm install --silent)
done
(cd "$project_root/miniprograms/core" && npm test && npx tsc --noEmit)
(cd "$project_root/miniprograms/wechat" && npm run typecheck)
(cd "$project_root/miniprograms/alipay" && npm run typecheck)

if [ "$(uname -s)" = Darwin ]; then
    CLANG_MODULE_CACHE_PATH="$macos_dir/.temp/swift-module-cache" \
    SWIFTPM_MODULECACHE_OVERRIDE="$macos_dir/.temp/swiftpm-module-cache" \
        swift test --disable-sandbox --package-path "$macos_dir" \
        --scratch-path "$macos_dir/.temp/swiftpm-build"

    mac_build="$quality_temp/aarch64-macos"
    (cd "$windows_dir" && zig build -Doptimize=ReleaseSafe -Dtarget=aarch64-macos.13.0 --prefix "$mac_build")
    CLANG_MODULE_CACHE_PATH="$macos_dir/.temp/swift-module-cache" \
        xcrun swiftc -swift-version 6 -parse-as-library -target arm64-apple-macos13.0 \
        "$macos_dir/Sources/TVRemoteCoreLogic/CoreLogic.swift" \
        "$macos_dir/Sources/"*.swift \
        -L "$mac_build/lib" -ltv_remote_core \
        -import-objc-header "$windows_dir/include/tv_remote_core.h" \
        -framework SwiftUI -framework AppKit -framework Security \
        -framework AVFoundation -framework CoreImage -framework CoreMedia \
        -framework MetalKit -framework VideoToolbox \
        -Xlinker -rpath -Xlinker @executable_path/../Frameworks \
        -o "$quality_temp/tv-remote-control-macos"
fi

echo "quality gates passed"
