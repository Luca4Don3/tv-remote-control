#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
android_dir="$project_root/android-agent"
artifact_dir="$project_root/.artifacts"
version=$(tr -d '\r\n' < "$project_root/VERSION")
output="$artifact_dir/tv-remote-agent-$version-android.apk"
checksum="$artifact_dir/tv-remote-agent-$version-android.apk.sha256"
run_id=$(date -u +%Y%m%dT%H%M%SZ)-$$
staging_root="$project_root/.temp/package-android-$run_id"
"$project_root/scripts/run-release-gates.sh"
python3 "$project_root/scripts/release-preflight.py"

: "${TVRC_RELEASE_STORE_FILE:?TVRC_RELEASE_STORE_FILE must point to an external keystore}"
: "${TVRC_RELEASE_STORE_PASSWORD:?TVRC_RELEASE_STORE_PASSWORD is required}"
: "${TVRC_RELEASE_KEY_ALIAS:?TVRC_RELEASE_KEY_ALIAS is required}"
: "${TVRC_RELEASE_KEY_PASSWORD:?TVRC_RELEASE_KEY_PASSWORD is required}"

store_dir=$(CDPATH= cd -- "$(dirname -- "$TVRC_RELEASE_STORE_FILE")" && pwd)
store_file="$store_dir/$(basename -- "$TVRC_RELEASE_STORE_FILE")"
case "$store_file" in
    "$project_root"/*) echo "release keystore must remain outside the repository" >&2; exit 1 ;;
esac
[ -f "$store_file" ] || { echo "release keystore not found: $store_file" >&2; exit 1; }
[ ! -e "$output" ] || { echo "refusing to overwrite existing artifact: $output" >&2; exit 1; }
[ ! -e "$checksum" ] || { echo "refusing to overwrite existing checksum: $checksum" >&2; exit 1; }

mkdir -p "$staging_root" "$android_dir/.temp/gradle-native"
# lintRelease 默认仅在存在 error 级别问题（lint abortOnError）时失败，warning 不会终止构建；
# 紧急热修复如需放宽可临时加 -PlintAbortOnError=false，发布构建保持默认严格。
(cd "$android_dir" && \
    GRADLE_USER_HOME="$android_dir/.gradle" \
    ./gradlew --no-daemon -Dorg.gradle.native.dir="$android_dir/.temp/gradle-native" assembleRelease lintRelease)

apk="$android_dir/app/build/outputs/apk/release/app-release.apk"
[ -f "$apk" ] || { echo "signed release APK was not produced; unsigned builds cannot be published" >&2; exit 1; }

apksigner_path=$(command -v apksigner || true)
if [ -z "$apksigner_path" ] && [ -n "${ANDROID_HOME:-}" ]; then
    apksigner_path=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner 2>/dev/null | sort -r | head -n 1)
fi
[ -n "$apksigner_path" ] || { echo "apksigner is required to verify the formal APK" >&2; exit 1; }
"$apksigner_path" verify --verbose "$apk"

zipinfo -1 "$apk" | grep -Eiq '(^|/)(test|androidTest|fixtures?)(/|$)|Test(Class)?\.class$' && {
    echo "formal APK contains test or fixture paths" >&2
    exit 1
}
if strings "$apk" | grep -Eiq 'MII[A-Za-z0-9+/]{120,}|TVRC_TEST_SECRET|fixture[_-]?token'; then
    echo "formal APK contains a forbidden secret or fixture marker" >&2
    exit 1
fi

staged_apk="$staging_root/$(basename -- "$output")"
staged_checksum="$staging_root/$(basename -- "$checksum")"
cp "$apk" "$staged_apk"
(cd "$staging_root" && shasum -a 256 "$(basename -- "$staged_apk")") > "$staged_checksum"
mkdir -p "$artifact_dir"
mv "$staged_apk" "$output"
mv "$staged_checksum" "$checksum"
echo "signed Android artifact written to $output"
