const std = @import("std");
const adb = @import("adb.zig");
const backend = @import("backend.zig");

pub const UiState = struct {
    apk_discovery_active: bool = true,
    adb_action: adb.DependencyAction = .confirm_install,
    reachability: backend.Reachability = .not_reachable,
    model_verification: backend.ModelVerification = .unverified,
    control_backend: backend.ControlBackend = .none,
    video_backend: backend.VideoBackend = .none,
    audio_backend: backend.AudioBackend = .none,
    status_message: []const u8 = "正在发现电视端 APK",

    pub fn applySelection(self: *UiState, selection: backend.Selection) void {
        self.reachability = selection.reachability;
        self.model_verification = selection.model_verification;
        self.control_backend = selection.default_control;
        self.video_backend = selection.video;
        self.audio_backend = selection.audio;
        self.status_message = selection.video_reason;
    }
};

test "missing ADB never disables APK discovery" {
    var state = UiState{};
    state.adb_action = .confirm_install;
    try std.testing.expect(state.apk_discovery_active);
}

test "applySelection covers every backend combination" {
    // 所有 ControlBackend × VideoBackend × AudioBackend 组合都必须能
    // 完整映射到 UiState，且不会篡改 apk_discovery_active / adb_action。
    const control_backends = [_]backend.ControlBackend{
        .apk, .scrcpy_control, .restricted_adb_input, .none,
    };
    const video_backends = [_]backend.VideoBackend{
        .scrcpy, .media_projection, .minicap, .none,
    };
    const audio_backends = [_]backend.AudioBackend{
        .scrcpy, .playback_capture, .none,
    };
    for (control_backends) |control| {
        for (video_backends) |video| {
            for (audio_backends) |audio| {
                var state = UiState{ .adb_action = .confirm_upgrade };
                state.applySelection(.{
                    .reachability = .reachable,
                    .model_verification = .verified,
                    .default_control = control,
                    .enhanced_control = .none,
                    .video = video,
                    .audio = audio,
                    .video_reason = "test-reason",
                });
                try std.testing.expectEqual(control, state.control_backend);
                try std.testing.expectEqual(video, state.video_backend);
                try std.testing.expectEqual(audio, state.audio_backend);
                try std.testing.expectEqualStrings("test-reason", state.status_message);
                try std.testing.expect(state.apk_discovery_active);
                try std.testing.expectEqual(adb.DependencyAction.confirm_upgrade, state.adb_action);
            }
        }
    }
}

test "applySelection preserves discovery and adb action flags" {
    var state = UiState{ .apk_discovery_active = false, .adb_action = .confirm_install };
    state.applySelection(.{
        .reachability = .not_reachable,
        .model_verification = .unverified,
        .default_control = .none,
        .enhanced_control = .none,
        .video = .none,
        .audio = .none,
        .video_reason = "no capability",
    });
    try std.testing.expect(!state.apk_discovery_active);
    try std.testing.expectEqual(adb.DependencyAction.confirm_install, state.adb_action);
    try std.testing.expectEqual(backend.Reachability.not_reachable, state.reachability);
    try std.testing.expectEqual(backend.ModelVerification.unverified, state.model_verification);
}
