const std = @import("std");
const adb = @import("adb.zig");
const backend = @import("backend.zig");

pub const UiState = struct {
    apk_discovery_active: bool = true,
    adb_action: adb.DependencyAction = .confirm_install,
    device_availability: backend.DeviceAvailability = .unverified,
    control_backend: backend.ControlBackend = .none,
    video_backend: backend.VideoBackend = .none,
    audio_backend: backend.AudioBackend = .none,
    status_message: []const u8 = "正在发现电视端 APK",

    pub fn applySelection(self: *UiState, selection: backend.Selection) void {
        self.device_availability = selection.availability;
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
