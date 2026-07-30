const std = @import("std");
const adb = @import("adb.zig");

pub const InstallPlan = struct {
    action: adb.DependencyAction,
    target_path: []const u8,
    download_url: []const u8,
    modify_user_path: bool,
    requires_confirmation: bool,
    backup_existing: bool,
};

pub fn plan(action: adb.DependencyAction, target_path: []const u8, modify_user_path: bool) !InstallPlan {
    if (!isAbsoluteWindowsPath(target_path)) return error.InstallPathMustBeAbsolute;
    return .{
        .action = action,
        .target_path = target_path,
        .download_url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
        .modify_user_path = modify_user_path,
        .requires_confirmation = action != .use_existing,
        .backup_existing = action == .confirm_upgrade,
    };
}

fn isAbsoluteWindowsPath(path: []const u8) bool {
    if (path.len >= 3 and std.ascii.isAlphabetic(path[0]) and path[1] == ':' and (path[2] == '\\' or path[2] == '/')) return true;
    return path.len >= 3 and path[0] == '\\' and path[1] == '\\' and path[2] != '\\';
}

test "install and upgrade always require confirmation" {
    const install = try plan(.confirm_install, "C:\\Users\\tester\\Android\\Sdk\\platform-tools", false);
    try std.testing.expect(install.requires_confirmation);
    try std.testing.expect(!install.modify_user_path);
    try std.testing.expect(!install.backup_existing);

    const upgrade = try plan(.confirm_upgrade, "C:\\Android\\platform-tools", true);
    try std.testing.expect(upgrade.requires_confirmation);
    try std.testing.expect(upgrade.backup_existing);
}

test "rejects ambiguous relative install directories" {
    try std.testing.expectError(error.InstallPathMustBeAbsolute, plan(.confirm_install, "tools\\platform-tools", false));
}
