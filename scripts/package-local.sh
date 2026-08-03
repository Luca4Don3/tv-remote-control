#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
controller_dir="$project_root/windows-controller"
artifact_dir="$project_root/.artifacts"
version=$(tr -d '\r\n' < "$project_root/VERSION")
printf '%s\n' "$version" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.((0|[1-9][0-9]*))$|^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-rc[1-9][0-9]*$' || {
    echo "invalid VERSION: $version" >&2
    exit 1
}
run_id=$(date -u +%Y%m%dT%H%M%SZ)-$$
staging_root="$project_root/.temp/package-$run_id"

"$project_root/scripts/run-release-gates.sh"
mkdir -p "$staging_root/output"

for artifact in \
    "tv-remote-control-$version-windows-x64.zip" \
    "tv-remote-control-$version-windows-arm64.zip" \
    "tv-remote-control-$version-windows-x86.zip" \
    "tv-remote-control-$version-macos-arm64.app.zip"; do
    if [ -e "$artifact_dir/$artifact" ]; then
        echo "refusing to overwrite existing artifact: $artifact_dir/$artifact" >&2
        exit 1
    fi
done
[ ! -e "$artifact_dir/SHA256SUMS-$version.txt" ] || {
    echo "refusing to overwrite existing checksum: $artifact_dir/SHA256SUMS-$version.txt" >&2
    exit 1
}

"$project_root/scripts/fetch-locked-dependencies.sh"
python3 "$project_root/scripts/release-preflight.py"

build_windows() {
    target=$1
    label=$2
    package_name="tv-remote-control-$version-windows-$label"
    prefix="$staging_root/build-$label"
    package_dir="$staging_root/$package_name"
    mkdir -p "$package_dir"
    (cd "$controller_dir" && \
        ZIG_GLOBAL_CACHE_DIR="$controller_dir/.temp/zig-global-cache" \
        ZIG_LOCAL_CACHE_DIR="$controller_dir/.temp/zig-package-$label" \
        zig build -Doptimize=ReleaseSafe -Dtarget="$target" --prefix "$prefix")
    cp "$prefix/bin/tv-remote-control.exe" "$package_dir/"
    cp "$project_root/THIRD_PARTY_NOTICES.md" "$project_root/dependencies.lock.json" "$package_dir/"
    cp "$controller_dir/vendor/mbedtls-3.6.7/LICENSE" "$package_dir/LICENSE-mbedtls.txt"
    cp "$controller_dir/vendor/scrcpy-LICENSE-v4.1" "$package_dir/LICENSE-scrcpy.txt"
    if [ "$label" != x86 ]; then
        cp "$controller_dir/vendor/scrcpy-server-v4.1" "$package_dir/"
        cp "$controller_dir/scripts/install-adb.ps1" "$package_dir/"
    fi
    (cd "$staging_root" && COPYFILE_DISABLE=1 zip -q -r "$staging_root/output/$package_name.zip" "$package_name")
}

build_windows x86_64-windows-gnu x64
build_windows aarch64-windows-gnu arm64
build_windows x86-windows-gnu x86

for label in x64 arm64; do
    package="$staging_root/tv-remote-control-$version-windows-$label"
    [ -f "$package/install-adb.ps1" ] || { echo "bundled ADB installer is missing from $label package" >&2; exit 1; }
    [ -f "$package/scrcpy-server-v4.1" ] || { echo "scrcpy server is missing from $label package" >&2; exit 1; }
done
x86_package="$staging_root/tv-remote-control-$version-windows-x86"
[ ! -e "$x86_package/install-adb.ps1" ] || { echo "x86 package must not contain the ADB installer" >&2; exit 1; }
[ ! -e "$x86_package/scrcpy-server-v4.1" ] || { echo "x86 package must not contain the scrcpy server" >&2; exit 1; }

mac_build="$staging_root/build-macos"
app="$staging_root/TV Remote Control.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Frameworks" "$app/Contents/Resources" "$mac_build"
(cd "$controller_dir" && \
    ZIG_GLOBAL_CACHE_DIR="$controller_dir/.temp/zig-global-cache" \
    ZIG_LOCAL_CACHE_DIR="$controller_dir/.temp/zig-package-macos" \
    zig build -Doptimize=ReleaseSafe -Dtarget=aarch64-macos.13.0 --prefix "$mac_build")
CLANG_MODULE_CACHE_PATH="$controller_dir/.temp/swift-module-cache" \
    xcrun swiftc -swift-version 6 -parse-as-library -target arm64-apple-macos13.0 \
    "$project_root/macos-controller/Sources/TVRemoteCoreLogic/CoreLogic.swift" \
    "$project_root/macos-controller/Sources/"*.swift \
    -L "$mac_build/lib" -ltv_remote_core \
    -import-objc-header "$controller_dir/include/tv_remote_core.h" \
    -framework SwiftUI -framework AppKit -framework Security \
    -framework AVFoundation -framework CoreImage -framework CoreMedia \
    -framework MetalKit -framework VideoToolbox \
    -Xlinker -rpath -Xlinker @executable_path/../Frameworks \
    -o "$app/Contents/MacOS/tv-remote-control"
cp "$mac_build/lib/libtv_remote_core.dylib" "$app/Contents/Frameworks/"
# $version 已在文件顶部通过 grep -Eq '^\d+\.\d+\.\d+$|^\d+\.\d+-rcN$' 校验
# （见上方版本校验），不会包含 sed 特殊字符，此处替换安全
sed "s/__TVRC_VERSION__/$version/g" "$project_root/macos-controller/Info.plist" > "$app/Contents/Info.plist"
cp "$project_root/THIRD_PARTY_NOTICES.md" "$project_root/dependencies.lock.json" "$app/Contents/Resources/"
cp "$controller_dir/vendor/mbedtls-3.6.7/LICENSE" "$app/Contents/Resources/LICENSE-mbedtls.txt"
cp "$controller_dir/vendor/scrcpy-LICENSE-v4.1" "$app/Contents/Resources/LICENSE-scrcpy.txt"
codesign --force --sign - --timestamp=none "$app/Contents/Frameworks/libtv_remote_core.dylib"
codesign --force --sign - --timestamp=none "$app"
COPYFILE_DISABLE=1 ditto -c -k --keepParent "$app" "$staging_root/output/tv-remote-control-$version-macos-arm64.app.zip"

for executable in \
    "$staging_root/tv-remote-control-$version-windows-x64/tv-remote-control.exe" \
    "$staging_root/tv-remote-control-$version-windows-arm64/tv-remote-control.exe" \
    "$staging_root/tv-remote-control-$version-windows-x86/tv-remote-control.exe" \
    "$app/Contents/MacOS/tv-remote-control" \
    "$app/Contents/Frameworks/libtv_remote_core.dylib"; do
    strings "$executable" | grep -Eiq 'TVRC_TEST_SECRET|fixture[_-]?token|MII[A-Za-z0-9+/]{120,}' && {
        echo "formal executable contains a forbidden test or secret marker: $executable" >&2
        exit 1
    }
done

(cd "$staging_root/output" && shasum -a 256 \
    "tv-remote-control-$version-windows-x64.zip" \
    "tv-remote-control-$version-windows-arm64.zip" \
    "tv-remote-control-$version-windows-x86.zip" \
    "tv-remote-control-$version-macos-arm64.app.zip") > "$staging_root/output/SHA256SUMS-$version.txt"

mkdir -p "$artifact_dir"
for completed in "$staging_root/output/"*; do
    mv "$completed" "$artifact_dir/"
done

echo "artifacts written to $artifact_dir"
