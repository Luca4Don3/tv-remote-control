const std = @import("std");
const Io = std.Io;
const core = @import("tv_remote_core");

pub fn main(init: std.process.Init) !void {
    const allocator = init.arena.allocator();
    var stdout_buffer: [4096]u8 = undefined;
    var stdout_file_writer: Io.File.Writer = .init(.stdout(), init.io, &stdout_buffer);
    const writer = &stdout_file_writer.interface;

    try writer.print("APK discovery: enabled\n", .{});

    const result = std.process.run(allocator, init.io, .{
        .argv = &.{ "adb", "version" },
        .stdout_limit = .limited(64 * 1024),
        .stderr_limit = .limited(64 * 1024),
    }) catch |err| {
        try writer.print("ADB enhancement: unavailable ({t})\n", .{err});
        try writer.print("Optional action: {t}; APK remote remains available\n", .{core.adb.DependencyAction.confirm_install});
        try writer.flush();
        return;
    };

    const info = core.adb.parseInfo(result.stdout) catch {
        try writer.print("ADB enhancement: incompatible output\nOptional action: {t}; APK remote remains available\n", .{core.adb.DependencyAction.confirm_upgrade});
        try writer.flush();
        return;
    };

    const action = core.adb.dependencyAction(true, info);
    const release = info.platform_tools orelse core.adb.Version{ .major = 0, .minor = 0, .patch = 0 };
    try writer.print("ADB protocol: {d}.{d}.{d}\nPlatform Tools: {d}.{d}.{d}\nOptional action: {t}\n", .{
        info.protocol.major,
        info.protocol.minor,
        info.protocol.patch,
        release.major,
        release.minor,
        release.patch,
        action,
    });
    try writer.flush();
}
