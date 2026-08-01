const std = @import("std");

pub const Reachability = enum { reachable, not_reachable };
pub const ModelVerification = enum { verified, unverified };

pub const ControlBackend = enum {
    apk,
    scrcpy_control,
    restricted_adb_input,
    none,
};

pub const VideoBackend = enum {
    scrcpy,
    media_projection,
    minicap,
    none,
};

pub const AudioBackend = enum {
    scrcpy,
    playback_capture,
    none,
};

pub const Probe = struct {
    api_level: u16,
    verified_profile: bool,
    apk_reachable: bool,
    apk_supports_action: bool,
    media_projection_available: bool,
    playback_capture_available: bool,
    adb_authorized: bool,
    scrcpy_video_available: bool,
    scrcpy_control_available: bool,
    scrcpy_audio_available: bool,
    minicap_verified: bool,
};

pub const Selection = struct {
    reachability: Reachability,
    model_verification: ModelVerification,
    default_control: ControlBackend,
    enhanced_control: ControlBackend,
    video: VideoBackend,
    audio: AudioBackend,
    video_reason: []const u8,
};

pub fn select(probe: Probe) Selection {
    const default_control: ControlBackend = if (probe.apk_reachable and probe.apk_supports_action)
        .apk
    else
        .none;

    // 设计决策（非未实现项）：enhanced_control 恒为 none。
    // 原因：scrcpy 固定以 control=false 启动，ADB 仅作为用户显式启用的
    // 媒体增强（scrcpy 画面/音频），从不替代 APK 按键控制；
    // restricted_adb_input 与 scrcpy_control 枚举值保留用于未来授权场景。
    const enhanced_control: ControlBackend = .none;

    const video_choice = selectVideo(probe);
    const audio = if (probe.adb_authorized and probe.scrcpy_audio_available)
        AudioBackend.scrcpy
    else if (probe.api_level >= 29 and probe.playback_capture_available)
        AudioBackend.playback_capture
    else
        AudioBackend.none;

    return .{
        .reachability = if (default_control != .none or probe.apk_reachable) .reachable else .not_reachable,
        .model_verification = if (probe.verified_profile) .verified else .unverified,
        .default_control = default_control,
        .enhanced_control = enhanced_control,
        .video = video_choice.backend,
        .audio = audio,
        .video_reason = video_choice.reason,
    };
}

const VideoChoice = struct {
    backend: VideoBackend,
    reason: []const u8,
};

fn selectVideo(probe: Probe) VideoChoice {
    if (probe.api_level >= 21) {
        if (probe.adb_authorized and probe.scrcpy_video_available) {
            return .{ .backend = .scrcpy, .reason = "user-authorized ADB with scrcpy video" };
        }
        if (probe.apk_reachable and probe.media_projection_available) {
            return .{ .backend = .media_projection, .reason = "MediaProjection requires confirmation for this viewing session" };
        }
        return .{ .backend = .none, .reason = "no scrcpy or MediaProjection video capability" };
    }

    if (probe.api_level >= 19 and probe.adb_authorized and probe.minicap_verified) {
        return .{ .backend = .minicap, .reason = "verified Android 4.4 minicap profile" };
    }
    return .{ .backend = .none, .reason = "Android 4.4 requires authorized ADB and verified minicap for video" };
}

test "keeps APK control independent from missing video" {
    const result = select(.{
        .api_level = 19,
        .verified_profile = true,
        .apk_reachable = true,
        .apk_supports_action = true,
        .media_projection_available = false,
        .playback_capture_available = false,
        .adb_authorized = false,
        .scrcpy_video_available = false,
        .scrcpy_control_available = false,
        .scrcpy_audio_available = false,
        .minicap_verified = false,
    });
    try std.testing.expectEqual(Reachability.reachable, result.reachability);
    try std.testing.expectEqual(ModelVerification.verified, result.model_verification);
    try std.testing.expectEqual(ControlBackend.apk, result.default_control);
    try std.testing.expectEqual(VideoBackend.none, result.video);
}

test "reachability is independent from model verification" {
    const result = select(.{
        .api_level = 34,
        .verified_profile = false,
        .apk_reachable = true,
        .apk_supports_action = true,
        .media_projection_available = false,
        .playback_capture_available = false,
        .adb_authorized = false,
        .scrcpy_video_available = false,
        .scrcpy_control_available = false,
        .scrcpy_audio_available = false,
        .minicap_verified = false,
    });
    try std.testing.expectEqual(Reachability.reachable, result.reachability);
    try std.testing.expectEqual(ModelVerification.unverified, result.model_verification);
}

test "prefers scrcpy video without replacing working APK control" {
    const result = select(.{
        .api_level = 34,
        .verified_profile = true,
        .apk_reachable = true,
        .apk_supports_action = true,
        .media_projection_available = true,
        .playback_capture_available = true,
        .adb_authorized = true,
        .scrcpy_video_available = true,
        .scrcpy_control_available = true,
        .scrcpy_audio_available = true,
        .minicap_verified = false,
    });
    try std.testing.expectEqual(ControlBackend.apk, result.default_control);
    try std.testing.expectEqual(ControlBackend.none, result.enhanced_control);
    try std.testing.expectEqual(VideoBackend.scrcpy, result.video);
    try std.testing.expectEqual(AudioBackend.scrcpy, result.audio);
}

test "uses MediaProjection when ADB is unavailable" {
    const result = select(.{
        .api_level = 29,
        .verified_profile = true,
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
    try std.testing.expectEqual(VideoBackend.media_projection, result.video);
    try std.testing.expectEqual(AudioBackend.playback_capture, result.audio);
}

test "uses minicap only on a verified legacy ADB device" {
    const result = select(.{
        .api_level = 19,
        .verified_profile = true,
        .apk_reachable = true,
        .apk_supports_action = false,
        .media_projection_available = false,
        .playback_capture_available = false,
        .adb_authorized = true,
        .scrcpy_video_available = false,
        .scrcpy_control_available = false,
        .scrcpy_audio_available = false,
        .minicap_verified = true,
    });
    try std.testing.expectEqual(VideoBackend.minicap, result.video);
    try std.testing.expectEqual(ControlBackend.none, result.default_control);
}
