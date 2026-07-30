const std = @import("std");
const backend = @import("backend.zig");

pub const CapabilityStatus = enum {
    supported,
    unsupported,
    permission_required,
    unverified,
};

pub const DeviceIdentity = struct {
    manufacturer: []const u8,
    model: []const u8,
    firmware: []const u8,
    api_level: u16,
    abi: []const u8,
};

pub const PerformanceSample = struct {
    latency_median_ms: ?u32 = null,
    latency_p95_ms: ?u32 = null,
    frames_per_second: ?f32 = null,
    memory_megabytes: ?u32 = null,
    temperature_celsius: ?f32 = null,
    stable_minutes: ?u32 = null,
};

pub const DeviceProbeReport = struct {
    identity: DeviceIdentity,
    verified_profile: bool,
    adb: CapabilityStatus,
    scrcpy: CapabilityStatus,
    minicap: CapabilityStatus,
    media_projection: CapabilityStatus,
    accessibility_control: CapabilityStatus,
    playback_audio: CapabilityStatus,
    selection: backend.Selection,
    performance: PerformanceSample = .{},
};

pub fn canDeclareVideoSupport(report: DeviceProbeReport) bool {
    if (!report.verified_profile) return false;
    if (report.identity.api_level < 21) {
        return report.adb == .supported and report.minicap == .supported and report.selection.video == .minicap;
    }
    return report.selection.video != .none;
}

test "unverified devices never claim video support" {
    const selection = backend.select(.{
        .api_level = 34,
        .verified_profile = false,
        .apk_reachable = true,
        .apk_supports_action = true,
        .media_projection_available = true,
        .playback_capture_available = true,
        .adb_authorized = false,
        .scrcpy_video_available = false,
        .scrcpy_control_available = false,
        .scrcpy_audio_available = false,
        .minicap_verified = false,
    });
    const report = DeviceProbeReport{
        .identity = .{ .manufacturer = "vendor", .model = "model", .firmware = "firmware", .api_level = 34, .abi = "arm64-v8a" },
        .verified_profile = false,
        .adb = .unsupported,
        .scrcpy = .unsupported,
        .minicap = .unsupported,
        .media_projection = .supported,
        .accessibility_control = .supported,
        .playback_audio = .supported,
        .selection = selection,
    };
    try std.testing.expect(!canDeclareVideoSupport(report));
}
