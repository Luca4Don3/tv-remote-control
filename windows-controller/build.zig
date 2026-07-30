const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const core = b.addModule("tv_remote_core", .{
        .root_source_file = b.path("src/root.zig"),
        .target = target,
    });

    const is_windows = target.result.os.tag == .windows;

    const exe = b.addExecutable(.{
        .name = "tv-remote-control",
        .root_module = b.createModule(.{
            .root_source_file = b.path(if (is_windows) "src/windows_main.zig" else "src/main.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = is_windows,
            .imports = &.{.{ .name = "tv_remote_core", .module = core }},
        }),
    });
    if (is_windows) {
        exe.subsystem = .Windows;
        exe.root_module.linkSystemLibrary("user32", .{});
        exe.root_module.linkSystemLibrary("gdi32", .{});
    }
    b.installArtifact(exe);

    const run_step = b.step("run", "Run controller diagnostics");
    const run_cmd = b.addRunArtifact(exe);
    run_cmd.step.dependOn(b.getInstallStep());
    if (b.args) |args| run_cmd.addArgs(args);
    run_step.dependOn(&run_cmd.step);

    const core_tests = b.addTest(.{ .root_module = core });
    const run_core_tests = b.addRunArtifact(core_tests);
    const test_step = b.step("test", "Run controller core tests");
    test_step.dependOn(&run_core_tests.step);
}
