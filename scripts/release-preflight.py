#!/usr/bin/env python3
"""Read-only validation of the dependency lock and release inputs.

锁定值如何更新（依赖升级时必须同步修改五处）：
1. dependencies.lock.json —— 新增/升级依赖时更新 version、url、size、sha256 等字段；
2. 本文件下方硬编码的版本/SHA 断言（Zig 0.16.0、Gradle 9.6.1、mbedTLS 3.6.7、scrcpy 4.1、Platform Tools 37.0.1）
   与锁文件逐字段比对，任何一处不一致都会导致发布失败；
3. windows-controller/vendor/ 下实际 vendor 文件（由 scripts/fetch-locked-dependencies.sh 拉取，
   本文件会对 scrcpy server/LICENSE 再次做 size+SHA-256 校验）；
4. windows-controller/src/abi.zig 内嵌的 Platform Tools 锁定值（install-adb 运行时校验用），
   本文件会检查锁定值已传入 abi.zig；windows-controller/scripts/install-adb.ps1 的 Expected* 参数同理。
"""

from __future__ import annotations

import hashlib
import json
import pathlib


ROOT = pathlib.Path(__file__).resolve().parent.parent


def fail(message: str) -> None:
    raise SystemExit(f"release preflight failed: {message}")


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_file(relative: str, *, size: int | None = None, digest: str | None = None) -> pathlib.Path:
    path = ROOT / relative
    if not path.is_file():
        fail(f"missing file: {relative}")
    if size is not None and path.stat().st_size != size:
        fail(f"size mismatch: {relative}")
    if digest is not None and sha256(path) != digest:
        fail(f"SHA-256 mismatch: {relative}")
    return path


def exact_keys(value: object, expected: set[str], label: str) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != expected:
        fail(f"invalid fields for {label}")
    return value


def main() -> None:
    lock_path = require_file("dependencies.lock.json")
    try:
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"invalid dependencies.lock.json: {error}")
    root = exact_keys(lock, {"schemaVersion", "dependencies", "downloadPolicy"}, "lock root")
    if root["schemaVersion"] != 1:
        fail("unsupported lock schema")
    dependencies = exact_keys(
        root["dependencies"],
        {"zig", "gradle", "mbedtls", "scrcpyServer", "androidPlatformToolsWindows", "minicap"},
        "dependencies",
    )
    zig = exact_keys(
        dependencies["zig"],
        {
            "version", "linuxX86_64Url", "linuxX86_64Sha256",
            "macosX86_64Url", "macosX86_64Sha256",
            "macosAarch64Url", "macosAarch64Sha256", "license",
        },
        "zig",
    )
    gradle = exact_keys(
        dependencies["gradle"],
        {"version", "distributionUrl", "distributionSha256", "wrapperJarSha256", "license"},
        "gradle",
    )
    mbedtls = exact_keys(
        dependencies["mbedtls"],
        {"version", "sourceCommit", "url", "size", "sha256", "license"},
        "mbedtls",
    )
    scrcpy = exact_keys(
        dependencies["scrcpyServer"],
        {"version", "sourceCommit", "url", "size", "sha256", "license", "licenseUrl", "licenseSize", "licenseSha256"},
        "scrcpyServer",
    )
    platform_tools = exact_keys(
        dependencies["androidPlatformToolsWindows"],
        {"version", "repositoryMetadataUrl", "url", "size", "repositoryChecksumAlgorithm", "repositoryChecksum", "sha256", "license"},
        "androidPlatformToolsWindows",
    )
    minicap = exact_keys(dependencies["minicap"], {"state", "artifacts"}, "minicap")
    policy = exact_keys(root["downloadPolicy"], {"allowedHosts", "cacheDirectory", "allowPreview"}, "downloadPolicy")

    if zig != {
        "version": "0.16.0",
        "linuxX86_64Url": "https://ziglang.org/download/0.16.0/zig-x86_64-linux-0.16.0.tar.xz",
        "linuxX86_64Sha256": "70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00",
        "macosX86_64Url": "https://ziglang.org/download/0.16.0/zig-x86_64-macos-0.16.0.tar.xz",
        "macosX86_64Sha256": "0387557ed1877bc6a2e1802c8391953baddba76081876301c522f52977b52ba7",
        "macosAarch64Url": "https://ziglang.org/download/0.16.0/zig-aarch64-macos-0.16.0.tar.xz",
        "macosAarch64Sha256": "b23d70deaa879b5c2d486ed3316f7eaa53e84acf6fc9cc747de152450d401489",
        "license": "MIT",
    }:
        fail("Zig lock does not match the approved CI toolchain")
    if gradle != {
        "version": "9.6.1",
        "distributionUrl": "https://services.gradle.org/distributions/gradle-9.6.1-bin.zip",
        "distributionSha256": "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14",
        "wrapperJarSha256": "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
        "license": "Apache-2.0",
    }:
        fail("Gradle lock does not match the approved release input")
    if mbedtls["version"] != "3.6.7" or mbedtls["sourceCommit"] != "627361b09e6f6e3eac756297a370a591cf310ab9":
        fail("mbedTLS lock does not match 3.6.7")
    if scrcpy["version"] != "4.1" or scrcpy["sourceCommit"] != "49c9501fb26f456bbf4a341dd68879f670c67452":
        fail("scrcpy lock does not match 4.1")
    if platform_tools["version"] != "37.0.1" or platform_tools["repositoryChecksumAlgorithm"] != "sha1":
        fail("Platform Tools lock does not match 37.0.1")
    if minicap != {"state": "disabled_no_verified_device_profile", "artifacts": []}:
        fail("minicap must remain disabled without a verified device profile")
    if policy != {
        "allowedHosts": ["github.com", "dl.google.com", "services.gradle.org", "ziglang.org"],
        "cacheDirectory": ".temp/dependencies",
        "allowPreview": False,
    }:
        fail("download policy differs from the approved release policy")

    wrapper = require_file("android-agent/gradle/wrapper/gradle-wrapper.jar", digest=str(gradle["wrapperJarSha256"]))
    _ = wrapper
    properties = require_file("android-agent/gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8").splitlines()
    required_properties = {
        "distributionUrl=" + str(gradle["distributionUrl"]).replace(":", "\\:"),
        f"distributionSha256Sum={gradle['distributionSha256']}",
    }
    if not required_properties.issubset(set(properties)):
        fail("Gradle wrapper properties do not match the lock")

    require_file("THIRD_PARTY_NOTICES.md")
    require_file("windows-controller/vendor/mbedtls-3.6.7/LICENSE")
    require_file(
        "windows-controller/vendor/scrcpy-server-v4.1",
        size=int(scrcpy["size"]),
        digest=str(scrcpy["sha256"]),
    )
    require_file(
        "windows-controller/vendor/scrcpy-LICENSE-v4.1",
        size=int(scrcpy["licenseSize"]),
        digest=str(scrcpy["licenseSha256"]),
    )
    installer = require_file("windows-controller/scripts/install-adb.ps1").read_text(encoding="utf-8")
    for parameter in ("ExpectedVersion", "ExpectedSize", "ExpectedSha256"):
        if parameter not in installer:
            fail("ADB installer is missing a required lock parameter")
    abi = require_file("windows-controller/src/abi.zig").read_text(encoding="utf-8")
    for locked_value in (str(platform_tools["version"]), str(platform_tools["size"]), str(platform_tools["sha256"])):
        if locked_value not in abi:
            fail("desktop release code does not pass every locked Platform Tools value")


if __name__ == "__main__":
    main()
