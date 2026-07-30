const std = @import("std");

pub const Version = struct {
    major: u32,
    minor: u32,
    patch: u32,

    pub fn atLeast(self: Version, required: Version) bool {
        if (self.major != required.major) return self.major > required.major;
        if (self.minor != required.minor) return self.minor > required.minor;
        return self.patch >= required.patch;
    }
};

pub const AdbInfo = struct {
    protocol: Version,
    platform_tools: ?Version,
};

pub const minimum_protocol = Version{ .major = 1, .minor = 0, .patch = 41 };
pub const minimum_platform_tools = Version{ .major = 30, .minor = 0, .patch = 0 };

pub const DependencyAction = enum {
    use_existing,
    confirm_install,
    confirm_upgrade,
};

pub fn dependencyAction(found: bool, info: ?AdbInfo) DependencyAction {
    if (!found) return .confirm_install;
    const detected = info orelse return .confirm_upgrade;
    if (!detected.protocol.atLeast(minimum_protocol)) return .confirm_upgrade;
    const release = detected.platform_tools orelse return .confirm_upgrade;
    return if (release.atLeast(minimum_platform_tools)) .use_existing else .confirm_upgrade;
}

pub fn parseInfo(output: []const u8) !AdbInfo {
    return .{
        .protocol = try parseVersionAfter(output, "Android Debug Bridge version "),
        .platform_tools = parseVersionAfter(output, "\nVersion ") catch null,
    };
}

fn parseVersionAfter(output: []const u8, marker: []const u8) !Version {
    const marker_index = std.mem.indexOf(u8, output, marker) orelse return error.VersionMarkerMissing;
    const start = marker_index + marker.len;
    var end = start;
    while (end < output.len and (std.ascii.isDigit(output[end]) or output[end] == '.')) : (end += 1) {}
    const text = output[start..end];

    var parts = std.mem.splitScalar(u8, text, '.');
    const major_text = parts.next() orelse return error.InvalidVersion;
    const minor_text = parts.next() orelse return error.InvalidVersion;
    const patch_text = parts.next() orelse return error.InvalidVersion;

    return .{
        .major = try std.fmt.parseInt(u32, major_text, 10),
        .minor = try std.fmt.parseInt(u32, minor_text, 10),
        .patch = try std.fmt.parseInt(u32, patch_text, 10),
    };
}

pub fn defaultWindowsInstallPath(allocator: std.mem.Allocator, local_app_data: []const u8) ![]u8 {
    return std.fmt.allocPrint(allocator, "{s}\\Android\\Sdk\\platform-tools", .{std.mem.trimEnd(u8, local_app_data, "\\/")});
}

test "parses protocol and Platform Tools versions" {
    const output =
        \\Android Debug Bridge version 1.0.41
        \\Version 36.0.0-13206524
        \\Installed as C:\\Android\\platform-tools\\adb.exe
    ;
    const info = try parseInfo(output);
    try std.testing.expectEqual(Version{ .major = 1, .minor = 0, .patch = 41 }, info.protocol);
    try std.testing.expectEqual(Version{ .major = 36, .minor = 0, .patch = 0 }, info.platform_tools.?);
}

test "requires upgrade when release version is missing or old" {
    try std.testing.expectEqual(DependencyAction.confirm_install, dependencyAction(false, null));
    try std.testing.expectEqual(DependencyAction.confirm_upgrade, dependencyAction(true, null));
    try std.testing.expectEqual(DependencyAction.confirm_upgrade, dependencyAction(true, .{
        .protocol = minimum_protocol,
        .platform_tools = .{ .major = 29, .minor = 0, .patch = 6 },
    }));
    try std.testing.expectEqual(DependencyAction.use_existing, dependencyAction(true, .{
        .protocol = minimum_protocol,
        .platform_tools = .{ .major = 36, .minor = 0, .patch = 0 },
    }));
}

test "uses an explicit Windows default directory" {
    const path = try defaultWindowsInstallPath(std.testing.allocator, "C:\\Users\\tester\\AppData\\Local\\");
    defer std.testing.allocator.free(path);
    try std.testing.expectEqualStrings("C:\\Users\\tester\\AppData\\Local\\Android\\Sdk\\platform-tools", path);
}
