const std = @import("std");

pub fn build(b: *std.Build) void {
    const version_text = b.build_root.handle.readFileAlloc(
        b.graph.io,
        "../VERSION",
        b.allocator,
        .limited(64),
    ) catch @panic("unable to read ../VERSION");
    const version = ProductVersion.parse(std.mem.trim(u8, version_text, " \t\r\n")) catch
        @panic("VERSION must use major.minor.patch or major.minor-rcN");
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const strip_product = optimize != .Debug;

    const core = b.addModule("tv_remote_core", .{
        .root_source_file = b.path("src/root.zig"),
        .target = target,
        .optimize = optimize,
        .strip = strip_product,
    });

    const is_windows = target.result.os.tag == .windows and switch (target.result.cpu.arch) {
        .x86, .x86_64, .aarch64 => true,
        else => false,
    };
    const is_macos_arm64 = target.result.os.tag == .macos and target.result.cpu.arch == .aarch64;
    const is_ios = target.result.os.tag == .ios and target.result.cpu.arch == .aarch64;
    // CI 在 linux host 上运行 zig build test（核心逻辑为平台无关 Zig）；产品构建目标仍限 Windows/macOS/iOS
    const is_linux_host = target.result.os.tag == .linux;
    if (!is_windows and !is_macos_arm64 and !is_ios and !is_linux_host) {
        @panic("supported product targets are Windows x86/x64/ARM64, macOS ARM64 and iOS ARM64");
    }
    // iOS 交叉编译需要 Xcode iPhoneOS SDK 的 sysroot（Zig 0.16 不自动探测 iphoneos SDK）
    const ios_sysroot: ?[]const u8 = if (is_ios) blk: {
        break :blk b.graph.environ_map.get("TVRC_IOS_SDK_PATH") orelse
            @panic("iOS target requires TVRC_IOS_SDK_PATH (Xcode iPhoneOS SDK path)");
    } else null;

    const mbed = addMbedTls(b, target, optimize, is_windows, ios_sysroot);

    core.addIncludePath(b.path("src"));
    core.addIncludePath(b.path("include"));
    core.addIncludePath(b.path("vendor/mbedtls-3.6.7/include"));

    const core_library = b.addLibrary(.{
        .name = "tv_remote_core",
        .linkage = if (is_windows or is_ios) .static else .dynamic,
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/abi.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .strip = strip_product,
        }),
    });
    configureTlsConsumer(b, core_library, mbed, is_windows, ios_sysroot);
    if (is_windows) core_library.root_module.addCSourceFile(.{
        .file = b.path("src/windows_runtime_paths.c"),
        .flags = &.{
            "-std=c11",                                              "-Wall", "-Wextra", "-Werror",
            b.fmt("-ffile-prefix-map={s}=.", .{b.pathFromRoot("")}),
        },
    });
    // 静态库引用 f128 软浮点符号（__divtf3 等）；Apple compiler-rt 剔除了 fp128
    // 实现，必须捆绑 Zig 自带 compiler_rt 才可被外部链接（iOS/macOS）
    if (is_ios) core_library.bundle_compiler_rt = true;
    b.installArtifact(core_library);
    // iOS 静态库链 mbedTLS：静态依赖产物不会随 --prefix 安装（在 zig-cache 内），
    // 为 iOS 显式安装三个 mbedTLS 静态库供外部 swiftc 链接门禁使用
    if (is_ios) {
        b.installArtifact(mbed.tls);
        b.installArtifact(mbed.x509);
        b.installArtifact(mbed.crypto);
    }

    if (is_windows) {
        const generated = b.addWriteFiles();
        const version_resource = generated.add("version.rc", b.fmt(
            \\#include <windows.h>
            \\
            \\1 RT_MANIFEST "tv-remote-control.manifest"
            \\
            \\VS_VERSION_INFO VERSIONINFO
            \\ FILEVERSION {d},{d},{d},{d}
            \\ PRODUCTVERSION {d},{d},{d},{d}
            \\ FILEFLAGSMASK VS_FFI_FILEFLAGSMASK
            \\ FILEFLAGS {s}
            \\ FILEOS VOS_NT_WINDOWS32
            \\ FILETYPE VFT_APP
            \\ FILESUBTYPE 0
            \\BEGIN
            \\    BLOCK "StringFileInfo"
            \\    BEGIN
            \\        BLOCK "040904B0"
            \\        BEGIN
            \\            VALUE "CompanyName", "Lucas Done\\0"
            \\            VALUE "FileDescription", "TV Remote Control\\0"
            \\            VALUE "FileVersion", "{s}\\0"
            \\            VALUE "InternalName", "tv-remote-control\\0"
            \\            VALUE "OriginalFilename", "tv-remote-control.exe\\0"
            \\            VALUE "ProductName", "TV Remote Control\\0"
            \\            VALUE "ProductVersion", "{s}\\0"
            \\        END
            \\    END
            \\    BLOCK "VarFileInfo"
            \\    BEGIN
            \\        VALUE "Translation", 0x0409, 1200
            \\    END
            \\END
        , .{
            version.major,                                    version.minor, version.patch, version.rc,
            version.major,                                    version.minor, version.patch, version.rc,
            if (version.rc == 0) "0" else "VS_FF_PRERELEASE", version.text,  version.text,
        }));
        const exe = b.addExecutable(.{
            .name = "tv-remote-control",
            .root_module = b.createModule(.{
                .root_source_file = b.path("src/windows_shell_entry.zig"),
                .target = target,
                .optimize = optimize,
                .link_libc = true,
                .strip = strip_product,
                .imports = &.{.{ .name = "tv_remote_core", .module = core }},
            }),
        });
        exe.subsystem = .Windows;
        const prefix_map = b.fmt("-ffile-prefix-map={s}=.", .{b.pathFromRoot("")});
        exe.root_module.addCSourceFile(.{ .file = b.path("src/windows_shell.c"), .flags = &.{ "-std=c11", prefix_map } });
        if (target.result.cpu.arch != .x86) {
            exe.root_module.addCSourceFile(.{
                .file = b.path("src/windows_media_renderer.c"),
                .flags = &.{ "-std=c11", "-Wall", "-Wextra", "-Werror", prefix_map },
            });
        }
        exe.root_module.addIncludePath(b.path("include"));
        exe.root_module.addWin32ResourceFile(.{
            .file = version_resource,
            .include_paths = &.{b.path("resources")},
        });
        exe.root_module.linkLibrary(core_library);
        exe.root_module.linkSystemLibrary("user32", .{});
        exe.root_module.linkSystemLibrary("gdi32", .{});
        exe.root_module.linkSystemLibrary("comdlg32", .{});
        if (target.result.cpu.arch != .x86) {
            exe.root_module.linkSystemLibrary("d3d11", .{});
            exe.root_module.linkSystemLibrary("dxgi", .{});
            exe.root_module.linkSystemLibrary("mfplat", .{});
            exe.root_module.linkSystemLibrary("mf", .{});
            exe.root_module.linkSystemLibrary("mfuuid", .{});
            exe.root_module.linkSystemLibrary("wmcodecdspuuid", .{});
            exe.root_module.linkSystemLibrary("ole32", .{});
            exe.root_module.linkSystemLibrary("uuid", .{});
            exe.root_module.linkSystemLibrary("winmm", .{});
        }
        b.installArtifact(exe);

        const run_step = b.step("run", "Run the Windows controller");
        const run_cmd = b.addRunArtifact(exe);
        run_cmd.step.dependOn(b.getInstallStep());
        if (b.args) |args| run_cmd.addArgs(args);
        run_step.dependOn(&run_cmd.step);
    } else {
        const diagnostics = b.addExecutable(.{
            .name = "tvrc-diagnostics",
            .root_module = b.createModule(.{
                .root_source_file = b.path("src/main.zig"),
                .target = target,
                .optimize = .Debug,
                .imports = &.{.{ .name = "tv_remote_core", .module = core }},
            }),
        });
        const diagnostics_step = b.step("diagnostics", "Build and run non-product diagnostics");
        const run_diagnostics = b.addRunArtifact(diagnostics);
        if (b.args) |args| run_diagnostics.addArgs(args);
        diagnostics_step.dependOn(&run_diagnostics.step);
    }

    const core_tests = b.addTest(.{ .root_module = core });
    configureTlsConsumer(b, core_tests, mbed, is_windows, ios_sysroot);
    const run_core_tests = b.addRunArtifact(core_tests);
    const test_step = b.step("test", "Run controller core tests");
    test_step.dependOn(&run_core_tests.step);
}

const ProductVersion = struct {
    text: []const u8,
    major: u16,
    minor: u16,
    patch: u16,
    rc: u16,

    fn parse(text: []const u8) !ProductVersion {
        const first_dot = std.mem.indexOfScalar(u8, text, '.') orelse return error.InvalidVersion;
        const major = try parsePart(text[0..first_dot]);
        const remainder = text[first_dot + 1 ..];
        const second_separator = std.mem.indexOfAny(u8, remainder, ".-") orelse return error.InvalidVersion;
        const minor = try parsePart(remainder[0..second_separator]);
        const suffix = remainder[second_separator..];
        if (suffix[0] == '.') {
            return .{ .text = text, .major = major, .minor = minor, .patch = try parsePart(suffix[1..]), .rc = 0 };
        }
        if (!std.mem.startsWith(u8, suffix, "-rc")) return error.InvalidVersion;
        const rc = try parsePart(suffix[3..]);
        if (rc == 0) return error.InvalidVersion;
        return .{ .text = text, .major = major, .minor = minor, .patch = 0, .rc = rc };
    }

    fn parsePart(text: []const u8) !u16 {
        if (text.len == 0 or (text.len > 1 and text[0] == '0')) return error.InvalidVersion;
        for (text) |byte| if (!std.ascii.isDigit(byte)) return error.InvalidVersion;
        return std.fmt.parseInt(u16, text, 10) catch error.InvalidVersion;
    }
};

const MbedLibraries = struct {
    crypto: *std.Build.Step.Compile,
    x509: *std.Build.Step.Compile,
    tls: *std.Build.Step.Compile,
};

fn addMbedTls(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    is_windows: bool,
    ios_sysroot: ?[]const u8,
) MbedLibraries {
    const vendor = "vendor/mbedtls-3.6.7";
    std.Io.Dir.cwd().access(b.graph.io, vendor ++ "/include/mbedtls/version.h", .{}) catch {
        @panic("mbedTLS 3.6.7 is missing; run scripts/fetch-locked-dependencies.sh first");
    };
    const include = b.path(vendor ++ "/include");
    const library = b.path(vendor ++ "/library");
    const prefix_map = b.fmt("-ffile-prefix-map={s}=.", .{b.pathFromRoot("")});
    const flags: []const []const u8 = if (ios_sysroot) |sdk| &.{
        "-std=c11", "-D_FILE_OFFSET_BITS=64", prefix_map, b.fmt("-isysroot{s}", .{sdk}),
    } else &.{ "-std=c11", "-D_FILE_OFFSET_BITS=64", prefix_map };

    const crypto = b.addLibrary(.{
        .name = "mbedcrypto",
        .linkage = .static,
        .root_module = b.createModule(.{ .target = target, .optimize = optimize, .link_libc = true, .strip = optimize != .Debug }),
    });
    if (ios_sysroot) |sdk| {
        crypto.root_module.addSystemIncludePath(.{ .cwd_relative = b.fmt("{s}/usr/include", .{sdk}) });
    }
    crypto.root_module.addIncludePath(include);
    crypto.root_module.addIncludePath(library);
    crypto.root_module.addCSourceFiles(.{
        .root = library,
        .files = &.{
            "aes.c",                        "aesni.c",              "aesce.c",               "aria.c",                                 "asn1parse.c",         "asn1write.c",
            "base64.c",                     "bignum.c",             "bignum_core.c",         "bignum_mod.c",                           "bignum_mod_raw.c",    "block_cipher.c",
            "camellia.c",                   "ccm.c",                "chacha20.c",            "chachapoly.c",                           "cipher.c",            "cipher_wrap.c",
            "constant_time.c",              "cmac.c",               "ctr_drbg.c",            "des.c",                                  "dhm.c",               "ecdh.c",
            "ecdsa.c",                      "ecjpake.c",            "ecp.c",                 "ecp_curves.c",                           "entropy.c",           "entropy_poll.c",
            "error.c",                      "gcm.c",                "hkdf.c",                "hmac_drbg.c",                            "lmots.c",             "lms.c",
            "md.c",                         "md5.c",                "memory_buffer_alloc.c", "nist_kw.c",                              "oid.c",               "padlock.c",
            "pem.c",                        "pk.c",                 "pk_ecc.c",              "pk_wrap.c",                              "pkcs12.c",            "pkcs5.c",
            "pkparse.c",                    "pkwrite.c",            "platform.c",            "platform_util.c",                        "poly1305.c",          "psa_crypto.c",
            "psa_crypto_aead.c",            "psa_crypto_cipher.c",  "psa_crypto_client.c",   "psa_crypto_driver_wrappers_no_static.c", "psa_crypto_ecp.c",    "psa_crypto_ffdh.c",
            "psa_crypto_hash.c",            "psa_crypto_mac.c",     "psa_crypto_pake.c",     "psa_crypto_rsa.c",                       "psa_crypto_random.c", "psa_crypto_se.c",
            "psa_crypto_slot_management.c", "psa_crypto_storage.c", "psa_its_file.c",        "psa_util.c",                             "ripemd160.c",         "rsa.c",
            "rsa_alt_helpers.c",            "sha1.c",               "sha256.c",              "sha512.c",                               "sha3.c",              "threading.c",
            "timing.c",                     "version.c",            "version_features.c",
        },
        .flags = flags,
    });
    if (is_windows) crypto.root_module.linkSystemLibrary("bcrypt", .{});

    const x509 = b.addLibrary(.{
        .name = "mbedx509",
        .linkage = .static,
        .root_module = b.createModule(.{ .target = target, .optimize = optimize, .link_libc = true, .strip = optimize != .Debug }),
    });
    if (ios_sysroot) |sdk| {
        x509.root_module.addSystemIncludePath(.{ .cwd_relative = b.fmt("{s}/usr/include", .{sdk}) });
    }
    x509.root_module.addIncludePath(include);
    x509.root_module.addIncludePath(library);
    x509.root_module.addCSourceFiles(.{
        .root = library,
        .files = &.{
            "pkcs7.c",     "x509.c",          "x509_create.c",   "x509_crl.c", "x509_crt.c", "x509_csr.c",
            "x509write.c", "x509write_crt.c", "x509write_csr.c",
        },
        .flags = flags,
    });
    x509.root_module.linkLibrary(crypto);

    const tls = b.addLibrary(.{
        .name = "mbedtls",
        .linkage = .static,
        .root_module = b.createModule(.{ .target = target, .optimize = optimize, .link_libc = true, .strip = optimize != .Debug }),
    });
    if (ios_sysroot) |sdk| {
        tls.root_module.addSystemIncludePath(.{ .cwd_relative = b.fmt("{s}/usr/include", .{sdk}) });
    }
    tls.root_module.addIncludePath(include);
    tls.root_module.addIncludePath(library);
    tls.root_module.addCSourceFiles(.{
        .root = library,
        .files = &.{
            "debug.c",            "mps_reader.c",       "mps_trace.c",         "net_sockets.c",                 "ssl_cache.c",
            "ssl_ciphersuites.c", "ssl_client.c",       "ssl_cookie.c",        "ssl_debug_helpers_generated.c", "ssl_msg.c",
            "ssl_ticket.c",       "ssl_tls.c",          "ssl_tls12_client.c",  "ssl_tls12_server.c",            "ssl_tls13_keys.c",
            "ssl_tls13_server.c", "ssl_tls13_client.c", "ssl_tls13_generic.c",
        },
        .flags = flags,
    });
    tls.root_module.linkLibrary(x509);
    tls.root_module.linkLibrary(crypto);
    if (is_windows) tls.root_module.linkSystemLibrary("ws2_32", .{});
    return .{ .crypto = crypto, .x509 = x509, .tls = tls };
}

fn configureTlsConsumer(
    b: *std.Build,
    consumer: *std.Build.Step.Compile,
    mbed: MbedLibraries,
    is_windows: bool,
    ios_sysroot: ?[]const u8,
) void {
    const prefix_map = b.fmt("-ffile-prefix-map={s}=.", .{b.pathFromRoot("")});
    const ios_flags: []const []const u8 = if (ios_sysroot) |sdk| &.{
        "-std=c11", prefix_map, b.fmt("-isysroot{s}", .{sdk}),
    } else &.{ "-std=c11", prefix_map };
    if (ios_sysroot) |sdk| {
        consumer.root_module.addSystemIncludePath(.{ .cwd_relative = b.fmt("{s}/usr/include", .{sdk}) });
    }
    consumer.root_module.addIncludePath(b.path("src"));
    consumer.root_module.addIncludePath(b.path("include"));
    consumer.root_module.addIncludePath(b.path("vendor/mbedtls-3.6.7/include"));
    consumer.root_module.addCSourceFile(.{ .file = b.path("src/tls_client.c"), .flags = ios_flags });
    consumer.root_module.addCSourceFile(.{ .file = b.path("src/credential_store.c"), .flags = ios_flags });
    consumer.root_module.linkLibrary(mbed.tls);
    consumer.root_module.linkLibrary(mbed.x509);
    consumer.root_module.linkLibrary(mbed.crypto);
    if (is_windows) {
        consumer.root_module.linkSystemLibrary("ws2_32", .{});
        consumer.root_module.linkSystemLibrary("bcrypt", .{});
        consumer.root_module.linkSystemLibrary("crypt32", .{});
        consumer.root_module.linkSystemLibrary("advapi32", .{});
    }
}
