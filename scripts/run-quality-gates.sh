#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
android_dir="$project_root/android-agent"
windows_dir="$project_root/windows-controller"
macos_dir="$project_root/macos-controller"
quality_temp="$project_root/.temp/quality-gates"
mkdir -p "$quality_temp"

if [ -z "${JAVA_HOME:-}" ]; then
    if [ -d "$project_root/.temp/jdk17/Contents/Home" ]; then
        JAVA_HOME="$project_root/.temp/jdk17/Contents/Home"
    else
        echo "JAVA_HOME must point to JDK 17" >&2
        exit 1
    fi
fi
export JAVA_HOME
export GRADLE_USER_HOME="$android_dir/.temp/gradle-user-home"
(cd "$android_dir" && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease)

export ZIG_GLOBAL_CACHE_DIR="$windows_dir/.temp/zig-global-cache"
export ZIG_LOCAL_CACHE_DIR="$windows_dir/.temp/zig-local-cache"
(cd "$windows_dir" && zig build test)
for target in x86-windows-gnu x86_64-windows-gnu aarch64-windows-gnu; do
    (cd "$windows_dir" && zig build -Doptimize=ReleaseSafe -Dtarget="$target" --prefix "$quality_temp/$target")
done

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
