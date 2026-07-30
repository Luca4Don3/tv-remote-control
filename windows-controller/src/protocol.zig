const std = @import("std");

pub const KeyState = enum { down, repeat, up, press };

pub const LogicalKey = enum {
    dpad_up,
    dpad_down,
    dpad_left,
    dpad_right,
    dpad_center,
    back,
    home,
    menu,
    volume_up,
    volume_down,
    volume_mute,
    channel_up,
    channel_down,
    media_play_pause,
    media_stop,
    media_next,
    media_previous,
    power,
};

pub const AckStatus = enum {
    success,
    unsupported,
    permission_denied,
    mapping_missing,
    rejected,
    execution_failed,
};

pub const KeyEvent = struct {
    sequence: u64,
    key: LogicalKey,
    state: KeyState,
    repeat_count: u16 = 0,
};

pub fn validTransition(previous: ?KeyState, next: KeyState) bool {
    return switch (next) {
        .press => previous == null,
        .down => previous == null,
        .repeat => previous == .down or previous == .repeat,
        .up => previous == .down or previous == .repeat,
    };
}

test "key state machine rejects release without press" {
    try std.testing.expect(!validTransition(null, .up));
    try std.testing.expect(validTransition(null, .down));
    try std.testing.expect(validTransition(.down, .repeat));
    try std.testing.expect(validTransition(.repeat, .up));
    try std.testing.expect(!validTransition(.up, .repeat));
}
