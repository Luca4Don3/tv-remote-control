#!/bin/sh
set -eu

# Linux 构建机（199）门禁：Android + Rust + Zig(Windows 交叉) + 小程序。
# macOS/iOS 门禁由 GitHub Actions 的 macOS runner 承担（见 .github/workflows/ci.yml）。

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
android_dir="$project_root/android-agent"
windows_dir="$project_root/windows-controller"
core_dir="$project_root/core-rs"

: "${JAVA_HOME:?JAVA_HOME must point to JDK 17}"
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$android_dir/.temp/gradle-user-home}"

(cd "$android_dir" && ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease)
(cd "$android_dir" && ./gradlew --no-daemon :controller:testDebugUnitTest :controller:lintDebug :controller:assembleDebug)

(cd "$core_dir" && cargo test --locked)
(cd "$core_dir" && cargo clippy --locked -- -D warnings)

zig_global="${ZIG_GLOBAL_CACHE_DIR:-$windows_dir/.temp/zig-global-cache}"
(cd "$windows_dir" && ZIG_GLOBAL_CACHE_DIR="$zig_global" ZIG_LOCAL_CACHE_DIR="$windows_dir/.temp/zig-local-cache" zig build test)
for target in x86-windows-gnu x86_64-windows-gnu aarch64-windows-gnu; do
    (cd "$windows_dir" && ZIG_GLOBAL_CACHE_DIR="$zig_global" ZIG_LOCAL_CACHE_DIR="$windows_dir/.temp/zig-local-cache-$target" \
        zig build -Doptimize=ReleaseSafe -Dtarget="$target" --prefix "$windows_dir/.temp/build-$target")
done

for module in core wechat alipay; do
    (cd "$project_root/miniprograms/$module" && npm install --silent)
done
(cd "$project_root/miniprograms/core" && npm test && npx tsc --noEmit)
(cd "$project_root/miniprograms/wechat" && npm run typecheck)
(cd "$project_root/miniprograms/alipay" && npm run typecheck)

echo "linux quality gates passed"
