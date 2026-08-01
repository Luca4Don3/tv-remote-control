pub const HostOS = enum(u8) { windows, macos };
pub const HostArch = enum(u8) { x86, x86_64, arm64 };
pub const AndroidAbi = enum(u8) { armeabi_v7a, arm64_v8a, x86, x86_64, unknown };

pub const CapabilityStatus = enum(u8) { supported, unsupported, permission_required, unverified };
pub const KeySupport = enum(u8) { guaranteed, best_effort, unsupported };
pub const DependencyState = enum(u8) { unavailable, usable, outdated, incompatible, unverified };
pub const ConnectionState = enum(u8) { stopped, idle, discovering, pairing, connecting, connected };
pub const MediaState = enum(u8) { unsupported, idle, permission_required, starting, streaming, failed };
pub const Reachability = enum(u8) { not_reachable, reachable };
pub const ModelVerification = enum(u8) { unverified, verified };

pub fn validateTarget(os: HostOS, arch: HostArch) !void {
    if (os == .macos and arch != .arm64) return error.UnsupportedHostTarget;
}

test "only the locked host matrix is accepted" {
    const std = @import("std");
    try validateTarget(.windows, .x86);
    try validateTarget(.windows, .x86_64);
    try validateTarget(.windows, .arm64);
    try validateTarget(.macos, .arm64);
    try std.testing.expectError(error.UnsupportedHostTarget, validateTarget(.macos, .x86_64));
}
