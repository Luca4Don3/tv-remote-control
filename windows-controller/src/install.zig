const std = @import("std");
const adb = @import("adb.zig");
const adb_manager = @import("adb_manager.zig");

pub const maximum_archive_bytes: u64 = 512 * 1024 * 1024;
pub const maximum_expanded_bytes: u64 = 1024 * 1024 * 1024;
pub const maximum_entry_bytes: u64 = 256 * 1024 * 1024;
pub const maximum_archive_entries: u32 = 4096;

pub const LockedArtifact = struct {
    version: adb.Version,
    download_url: []const u8,
    size: u64,
    sha256: []const u8,
};

pub const InstallPlan = struct {
    action: adb.DependencyAction,
    target_path: []const u8,
    download_url: []const u8,
    version: adb.Version,
    expected_size: u64,
    expected_sha256: []const u8,
    modify_user_path: bool,
    requires_confirmation: bool,
    backup_existing: bool,
    x64_emulation: bool,
};

/// Creates an executable installation plan only when Google's stable metadata
/// agrees with the independently pinned SHA-256 lock information.
pub fn plan(
    action: adb.DependencyAction,
    target_path: []const u8,
    repository_package: adb.RepositoryPackage,
    locked: LockedArtifact,
    host_arch: adb_manager.WindowsArch,
    modify_user_path: bool,
) !InstallPlan {
    if (!isSafeManagedWindowsPath(target_path)) return error.UnsafeInstallPath;
    // 设计决策：首期明确禁止修改用户 PATH，任何 true 请求都显式失败。
    // 因此返回的 InstallPlan.modify_user_path 恒为 false，不传递调用者意图。
    if (modify_user_path) return error.UserPathModificationForbidden;
    if (adb_manager.installStrategy(host_arch) == .manual_probe_only) {
        return error.AutomaticInstallUnsupported;
    }
    try validateLock(repository_package, locked);

    return .{
        .action = action,
        .target_path = target_path,
        .download_url = locked.download_url,
        .version = locked.version,
        .expected_size = locked.size,
        .expected_sha256 = locked.sha256,
        .modify_user_path = false,
        .requires_confirmation = action != .use_existing,
        .backup_existing = action == .confirm_upgrade,
        .x64_emulation = adb_manager.installStrategy(host_arch) == .automatic_x64_emulated,
    };
}

pub fn validateLock(repository_package: adb.RepositoryPackage, locked: LockedArtifact) !void {
    if (!locked.version.eql(repository_package.version)) return error.MetadataLockMismatch;
    if (locked.size == 0 or locked.size > maximum_archive_bytes) return error.InvalidLockedSize;
    if (locked.size != repository_package.archive.size) return error.MetadataLockMismatch;
    if (!isSha256(locked.sha256)) return error.InvalidLockedSha256;

    const repository_prefix = "https://dl.google.com/android/repository/";
    if (!std.mem.startsWith(u8, locked.download_url, repository_prefix)) return error.UntrustedDownloadUrl;
    const relative_url = locked.download_url[repository_prefix.len..];
    if (!std.mem.eql(u8, relative_url, repository_package.archive.relative_url)) {
        return error.MetadataLockMismatch;
    }
    if (std.mem.indexOfAny(u8, relative_url, "/\\?#%") != null or
        std.mem.indexOf(u8, relative_url, "..") != null)
    {
        return error.UntrustedDownloadUrl;
    }

    if (repository_package.archive.checksum_algorithm == .sha256 and
        !std.ascii.eqlIgnoreCase(repository_package.archive.checksum, locked.sha256))
    {
        return error.MetadataLockMismatch;
    }
}

pub fn verifyDownloadedArtifact(actual_size: u64, actual_sha256: []const u8, install_plan: InstallPlan) !void {
    if (actual_size != install_plan.expected_size) return error.ArchiveSizeMismatch;
    // 错误语义区分：actual_sha256 来自文件系统等不可信来源，格式非法
    // （非 64 位十六进制）返回 InvalidActualSha256；格式合法但与锁定值
    // 不一致返回 ArchiveChecksumMismatch。两者不得混淆，UI 层据此分别
    // 提示「文件损坏」与「校验和不匹配」。
    if (!isSha256(actual_sha256)) return error.InvalidActualSha256;
    if (!std.ascii.eqlIgnoreCase(actual_sha256, install_plan.expected_sha256)) {
        return error.ArchiveChecksumMismatch;
    }
}

pub fn isSafeManagedWindowsPath(path: []const u8) bool {
    if (path.len < 4 or !std.ascii.isAlphabetic(path[0]) or path[1] != ':' or !isSeparator(path[2])) {
        return false;
    }
    if (isSeparator(path[3]) or std.mem.indexOfScalar(u8, path, 0) != null) return false;
    if (std.mem.indexOfAny(u8, path, "\r\n") != null) return false;
    if (std.mem.indexOfScalarPos(u8, path, 2, ':') != null) return false;

    const remainder = std.mem.trimEnd(u8, path[3..], "\\/");
    if (remainder.len == 0) return false;
    var components = std.mem.tokenizeAny(u8, remainder, "\\/");
    var component_index: usize = 0;
    var last_component: []const u8 = "";
    while (components.next()) |component| : (component_index += 1) {
        if (!isSafeWindowsComponent(component)) return false;
        if (component_index == 0 and std.ascii.eqlIgnoreCase(component, "Windows")) return false;
        last_component = component;
    }
    return std.ascii.eqlIgnoreCase(last_component, "platform-tools");
}

pub const ArchiveAudit = struct {
    entry_count: u32 = 0,
    expanded_bytes: u64 = 0,

    pub fn add(self: *ArchiveAudit, entry_name: []const u8, expanded_size: u64) !void {
        if (!isSafeArchiveEntry(entry_name)) return error.UnsafeArchiveEntry;
        if (expanded_size > maximum_entry_bytes) return error.ArchiveEntryTooLarge;
        if (self.entry_count == maximum_archive_entries) return error.TooManyArchiveEntries;
        self.entry_count += 1;
        self.expanded_bytes = std.math.add(u64, self.expanded_bytes, expanded_size) catch {
            return error.ExpandedSizeLimitExceeded;
        };
        if (self.expanded_bytes > maximum_expanded_bytes) return error.ExpandedSizeLimitExceeded;
    }
};

pub fn isSafeArchiveEntry(entry_name: []const u8) bool {
    const trimmed = std.mem.trimEnd(u8, entry_name, "\\/");
    if (trimmed.len == 0 or isSeparator(trimmed[0])) return false;
    if (std.mem.indexOfScalar(u8, trimmed, 0) != null or std.mem.indexOfScalar(u8, trimmed, ':') != null) {
        return false;
    }

    var components = std.mem.tokenizeAny(u8, trimmed, "\\/");
    const root = components.next() orelse return false;
    if (!std.mem.eql(u8, root, "platform-tools")) return false;
    while (components.next()) |component| if (!isSafeWindowsComponent(component)) return false;
    return true;
}

pub const InstallStage = enum {
    planned,
    downloaded,
    archive_verified,
    expanded,
    candidate_validated,
    backup_created,
    activated,
    complete,
};

pub const RecoveryAction = enum {
    cleanup_staging,
    restore_backup,
    remove_activated,
    replace_activated_with_backup,
    none,
};

pub const InstallTransaction = struct {
    stage: InstallStage = .planned,
    had_existing_install: bool,

    pub fn advance(self: *InstallTransaction, next: InstallStage) !void {
        const valid = switch (self.stage) {
            .planned => next == .downloaded,
            .downloaded => next == .archive_verified,
            .archive_verified => next == .expanded,
            .expanded => next == .candidate_validated,
            .candidate_validated => if (self.had_existing_install)
                next == .backup_created
            else
                next == .activated,
            .backup_created => next == .activated,
            .activated => next == .complete,
            .complete => false,
        };
        if (!valid) return error.InvalidInstallTransition;
        self.stage = next;
    }

    pub fn recoveryAction(self: InstallTransaction) RecoveryAction {
        return switch (self.stage) {
            .planned, .downloaded, .archive_verified, .expanded, .candidate_validated => .cleanup_staging,
            .backup_created => .restore_backup,
            .activated => if (self.had_existing_install)
                .replace_activated_with_backup
            else
                .remove_activated,
            .complete => .none,
        };
    }
};

fn isSeparator(byte: u8) bool {
    return byte == '\\' or byte == '/';
}

fn isSha256(text: []const u8) bool {
    if (text.len != 64) return false;
    for (text) |byte| if (!std.ascii.isHex(byte)) return false;
    return true;
}

fn isSafeWindowsComponent(component: []const u8) bool {
    if (component.len == 0 or std.mem.eql(u8, component, ".") or std.mem.eql(u8, component, "..")) return false;
    if (component[component.len - 1] == '.' or component[component.len - 1] == ' ') return false;
    if (std.mem.indexOfAny(u8, component, "<>:\"|?*") != null) return false;
    for (component) |byte| if (byte < 0x20) return false;

    const stem_end = std.mem.indexOfScalar(u8, component, '.') orelse component.len;
    const stem = component[0..stem_end];
    const reserved = [_][]const u8{ "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9" };
    for (reserved) |name| if (std.ascii.eqlIgnoreCase(stem, name)) return false;
    return true;
}

fn testRepositoryPackage() adb.RepositoryPackage {
    return .{
        .version = .{ .major = 36, .minor = 0, .patch = 1 },
        .archive = .{
            .relative_url = "platform-tools_r36.0.1-windows.zip",
            .size = 222,
            .checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            .checksum_algorithm = .sha1,
        },
    };
}

fn testLock() LockedArtifact {
    return .{
        .version = .{ .major = 36, .minor = 0, .patch = 1 },
        .download_url = "https://dl.google.com/android/repository/platform-tools_r36.0.1-windows.zip",
        .size = 222,
        .sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    };
}

test "installation and upgrade require confirmation without PATH changes" {
    const install = try plan(
        .confirm_install,
        "C:\\Users\\tester\\Android\\Sdk\\platform-tools",
        testRepositoryPackage(),
        testLock(),
        .x86_64,
        false,
    );
    try std.testing.expect(install.requires_confirmation);
    try std.testing.expect(!install.modify_user_path);
    try std.testing.expect(!install.backup_existing);

    const upgrade = try plan(
        .confirm_upgrade,
        "C:\\Android\\platform-tools",
        testRepositoryPackage(),
        testLock(),
        .arm64,
        false,
    );
    try std.testing.expect(upgrade.backup_existing);
    try std.testing.expect(upgrade.x64_emulation);
}

test "rejects dangerous and ambiguous install directories" {
    const paths = [_][]const u8{
        "tools\\platform-tools",
        "C:\\",
        "\\\\server\\share\\platform-tools",
        "C:\\Windows\\platform-tools",
        "c:/WINDOWS/platform-tools",
        "C:\\Android\\..\\platform-tools",
        "C:\\Android\\not-platform-tools",
        "C:\\Android\\NUL\\platform-tools",
    };
    for (paths) |path| try std.testing.expectError(error.UnsafeInstallPath, plan(
        .confirm_install,
        path,
        testRepositoryPackage(),
        testLock(),
        .x86_64,
        false,
    ));
    try std.testing.expectError(error.UserPathModificationForbidden, plan(
        .confirm_install,
        "C:\\Android\\platform-tools",
        testRepositoryPackage(),
        testLock(),
        .x86_64,
        true,
    ));
}

test "only a versioned official URL matching metadata and SHA-256 lock is accepted" {
    var latest_alias = testLock();
    latest_alias.download_url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip";
    try std.testing.expectError(error.MetadataLockMismatch, validateLock(testRepositoryPackage(), latest_alias));

    var wrong_host = testLock();
    wrong_host.download_url = "https://example.invalid/platform-tools_r36.0.1-windows.zip";
    try std.testing.expectError(error.UntrustedDownloadUrl, validateLock(testRepositoryPackage(), wrong_host));

    var wrong_size = testLock();
    wrong_size.size += 1;
    try std.testing.expectError(error.MetadataLockMismatch, validateLock(testRepositoryPackage(), wrong_size));
}

test "download validation checks both size and SHA-256" {
    const install_plan = try plan(
        .confirm_install,
        "C:\\Android\\platform-tools",
        testRepositoryPackage(),
        testLock(),
        .x86_64,
        false,
    );
    try verifyDownloadedArtifact(222, testLock().sha256, install_plan);
    try std.testing.expectError(error.ArchiveSizeMismatch, verifyDownloadedArtifact(221, testLock().sha256, install_plan));
    try std.testing.expectError(
        error.ArchiveChecksumMismatch,
        verifyDownloadedArtifact(222, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", install_plan),
    );
    // 校验和格式无效与不匹配必须区分：长度非法应报 InvalidActualSha256。
    try std.testing.expectError(
        error.InvalidActualSha256,
        verifyDownloadedArtifact(222, "not-a-sha256", install_plan),
    );
}

test "archive audit rejects traversal, alternate streams and expansion bombs" {
    var audit = ArchiveAudit{};
    try audit.add("platform-tools/adb.exe", 1024);
    try std.testing.expectError(error.UnsafeArchiveEntry, audit.add("platform-tools/../escape.exe", 1));
    try std.testing.expectError(error.UnsafeArchiveEntry, audit.add("platform-tools/adb.exe:payload", 1));
    try std.testing.expectError(error.UnsafeArchiveEntry, audit.add("other-root/adb.exe", 1));
    try std.testing.expectError(error.ArchiveEntryTooLarge, audit.add("platform-tools/huge.bin", maximum_entry_bytes + 1));
}

test "transaction exposes rollback action at every switch point" {
    var upgrade = InstallTransaction{ .had_existing_install = true };
    try upgrade.advance(.downloaded);
    try upgrade.advance(.archive_verified);
    try upgrade.advance(.expanded);
    try upgrade.advance(.candidate_validated);
    try upgrade.advance(.backup_created);
    try std.testing.expectEqual(RecoveryAction.restore_backup, upgrade.recoveryAction());
    try upgrade.advance(.activated);
    try std.testing.expectEqual(RecoveryAction.replace_activated_with_backup, upgrade.recoveryAction());
    try upgrade.advance(.complete);
    try std.testing.expectEqual(RecoveryAction.none, upgrade.recoveryAction());

    var fresh = InstallTransaction{ .had_existing_install = false };
    try std.testing.expectError(error.InvalidInstallTransition, fresh.advance(.activated));
}

test "x86 automatic installation is forbidden" {
    try std.testing.expectError(error.AutomaticInstallUnsupported, plan(
        .confirm_install,
        "C:\\Android\\platform-tools",
        testRepositoryPackage(),
        testLock(),
        .x86,
        false,
    ));
}
