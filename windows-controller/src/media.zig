const std = @import("std");
const backend = @import("backend.zig");

pub const SessionState = enum { idle, starting, streaming, stopping, failed };

pub const QualityProfile = struct {
    width: u16,
    height: u16,
    frames_per_second: u8,
    bitrate_bps: u32,
};

pub const default_quality = QualityProfile{
    .width = 1280,
    .height = 720,
    .frames_per_second = 15,
    .bitrate_bps = 2_000_000,
};

pub const maximum_quality = QualityProfile{
    .width = 1920,
    .height = 1080,
    .frames_per_second = 30,
    .bitrate_bps = 8_000_000,
};

pub const EncodedVideoFrame = struct {
    presentation_time_us: i64,
    key_frame: bool,
    codec: enum { h264, jpeg },
    payload: []const u8,
};

pub const EncodedAudioFrame = struct {
    presentation_time_us: i64,
    codec: enum { aac, opus },
    payload: []const u8,
};

pub const MediaSession = struct {
    state: SessionState = .idle,
    video_backend: backend.VideoBackend = .none,
    audio_backend: backend.AudioBackend = .none,
    quality: QualityProfile = default_quality,

    pub fn begin(self: *MediaSession, video: backend.VideoBackend, audio: backend.AudioBackend) !void {
        if (self.state != .idle) return error.SessionAlreadyActive;
        if (video == .none) return error.VideoBackendUnavailable;
        self.video_backend = video;
        self.audio_backend = audio;
        self.state = .starting;
    }

    pub fn markStreaming(self: *MediaSession) !void {
        if (self.state != .starting) return error.InvalidSessionTransition;
        self.state = .streaming;
    }

    pub fn stop(self: *MediaSession) void {
        self.state = .idle;
        self.video_backend = .none;
        self.audio_backend = .none;
        self.quality = default_quality;
    }

    pub fn lowerLoad(self: *MediaSession) void {
        if (self.quality.width > 1280 or self.quality.frames_per_second > 15) {
            self.quality = default_quality;
        } else {
            self.quality = .{ .width = 960, .height = 540, .frames_per_second = 10, .bitrate_bps = 1_000_000 };
        }
    }
};

test "media session requires an explicit video backend" {
    var session = MediaSession{};
    try std.testing.expectError(error.VideoBackendUnavailable, session.begin(.none, .none));
    try session.begin(.media_projection, .playback_capture);
    try session.markStreaming();
    try std.testing.expectEqual(SessionState.streaming, session.state);
    session.stop();
    try std.testing.expectEqual(SessionState.idle, session.state);
}

test "load reduction is bounded and explicit" {
    var session = MediaSession{ .quality = maximum_quality };
    session.lowerLoad();
    try std.testing.expectEqual(default_quality, session.quality);
    session.lowerLoad();
    try std.testing.expectEqual(@as(u16, 960), session.quality.width);
    try std.testing.expectEqual(@as(u8, 10), session.quality.frames_per_second);
}
