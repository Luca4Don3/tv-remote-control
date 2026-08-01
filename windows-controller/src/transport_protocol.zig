const std = @import("std");

pub const discovery_port: u16 = 47_831;
pub const control_port: u16 = 47_832;
pub const max_frame_size: usize = 64 * 1024;
pub const frame_header_size: usize = 4;
pub const heartbeat_interval_ms: u32 = 15_000;
pub const heartbeat_timeout_ms: u32 = 45_000;

pub fn encodeFrame(payload: []const u8, output: []u8) ![]u8 {
    if (payload.len == 0) return error.EmptyFrame;
    if (payload.len > max_frame_size) return error.FrameTooLarge;
    if (output.len < payload.len + frame_header_size) return error.BufferTooSmall;
    std.mem.writeInt(u32, output[0..frame_header_size], @intCast(payload.len), .big);
    @memcpy(output[frame_header_size .. payload.len + frame_header_size], payload);
    return output[0 .. payload.len + frame_header_size];
}

pub fn decodeFrame(frame: []const u8) ![]const u8 {
    if (frame.len < frame_header_size) return error.IncompleteFrame;
    const length = std.mem.readInt(u32, frame[0..frame_header_size], .big);
    if (length == 0) return error.EmptyFrame;
    if (length > max_frame_size) return error.FrameTooLarge;
    if (frame.len != @as(usize, length) + frame_header_size) return error.IncompleteFrame;
    return frame[frame_header_size..];
}

/// Incremental decoder for TCP, where one read can contain a partial frame or
/// several coalesced frames. `peek` borrows storage until `consume` is called.
pub const FrameDecoder = struct {
    storage: [max_frame_size + frame_header_size]u8 = undefined,
    buffered: usize = 0,
    pending_total: ?usize = null,

    pub fn append(self: *FrameDecoder, bytes: []const u8) !void {
        if (bytes.len > self.storage.len - self.buffered) return error.InputBufferFull;
        @memcpy(self.storage[self.buffered..][0..bytes.len], bytes);
        self.buffered += bytes.len;
    }

    pub fn peek(self: *FrameDecoder) !?[]const u8 {
        if (self.pending_total) |total| return self.storage[frame_header_size..total];
        if (self.buffered < frame_header_size) return null;
        const payload_len = std.mem.readInt(u32, self.storage[0..frame_header_size], .big);
        if (payload_len == 0) return error.EmptyFrame;
        if (payload_len > max_frame_size) return error.FrameTooLarge;
        const total = @as(usize, payload_len) + frame_header_size;
        if (self.buffered < total) return null;
        self.pending_total = total;
        return self.storage[frame_header_size..total];
    }

    pub fn consume(self: *FrameDecoder) !void {
        const total = self.pending_total orelse return error.NoPendingFrame;
        const remaining = self.buffered - total;
        if (remaining > 0) @memmove(self.storage[0..remaining], self.storage[total..self.buffered]);
        self.buffered = remaining;
        self.pending_total = null;
    }

    pub fn reset(self: *FrameDecoder) void {
        self.buffered = 0;
        self.pending_total = null;
    }

    pub fn copyBuffered(self: *const FrameDecoder, output: []u8) !usize {
        if (self.pending_total != null) return error.PendingFrameNotConsumed;
        if (output.len < self.buffered) return error.BufferTooSmall;
        @memcpy(output[0..self.buffered], self.storage[0..self.buffered]);
        return self.buffered;
    }
};

pub const SessionGuard = struct {
    last_sequence: ?u64 = null,
    pressed_keys: std.StaticBitSet(32) = .initEmpty(),

    pub fn validateSequence(self: *SessionGuard, sequence: u64) !void {
        if (sequence == 0 or sequence > std.math.maxInt(i64)) return error.InvalidSequence;
        if (self.last_sequence) |last| if (sequence <= last) return error.ReplayedSequence;
        self.last_sequence = sequence;
    }

    pub fn keyDown(self: *SessionGuard, key: u5) !void {
        if (self.pressed_keys.isSet(key)) return error.InvalidKeyTransition;
        self.pressed_keys.set(key);
    }

    pub fn keyUp(self: *SessionGuard, key: u5) !void {
        if (!self.pressed_keys.isSet(key)) return error.InvalidKeyTransition;
        self.pressed_keys.unset(key);
    }

    pub fn disconnect(self: *SessionGuard) std.StaticBitSet(32) {
        const released = self.pressed_keys;
        self.* = .{};
        return released;
    }
};

test "frame length is big endian and bounded" {
    var buffer: [32]u8 = undefined;
    const frame = try encodeFrame("{}", &buffer);
    try std.testing.expectEqualSlices(u8, &.{ 0, 0, 0, 2 }, frame[0..4]);
    try std.testing.expectEqualStrings("{}", try decodeFrame(frame));

    var malicious = [_]u8{ 0, 2, 0, 1 };
    try std.testing.expectError(error.FrameTooLarge, decodeFrame(&malicious));
}

test "incremental decoder handles fragmented and coalesced TCP reads" {
    var first_buffer: [32]u8 = undefined;
    var second_buffer: [32]u8 = undefined;
    const first = try encodeFrame("{\"first\":1}", &first_buffer);
    const second = try encodeFrame("{\"second\":2}", &second_buffer);
    var joined: [64]u8 = undefined;
    @memcpy(joined[0..first.len], first);
    @memcpy(joined[first.len..][0..second.len], second);

    var decoder = FrameDecoder{};
    try decoder.append(joined[0..2]);
    try std.testing.expect(try decoder.peek() == null);
    try decoder.append(joined[2 .. first.len + 3]);
    try std.testing.expectEqualStrings("{\"first\":1}", (try decoder.peek()).?);
    try decoder.consume();
    try std.testing.expect(try decoder.peek() == null);
    try decoder.append(joined[first.len + 3 .. first.len + second.len]);
    try std.testing.expectEqualStrings("{\"second\":2}", (try decoder.peek()).?);
    try decoder.consume();
    try std.testing.expectEqual(@as(usize, 0), decoder.buffered);
}

test "incremental decoder rejects empty and oversized frames before allocation" {
    var decoder = FrameDecoder{};
    try decoder.append(&.{ 0, 0, 0, 0 });
    try std.testing.expectError(error.EmptyFrame, decoder.peek());
    decoder.reset();
    try decoder.append(&.{ 0, 1, 0, 1 });
    try std.testing.expectError(error.FrameTooLarge, decoder.peek());
}

test "session rejects replay and releases keys on disconnect" {
    var guard = SessionGuard{};
    try std.testing.expectError(error.InvalidSequence, guard.validateSequence(0));
    try guard.validateSequence(1);
    try std.testing.expectError(error.ReplayedSequence, guard.validateSequence(1));
    try guard.keyDown(2);
    const released = guard.disconnect();
    try std.testing.expect(released.isSet(2));
    try std.testing.expect(guard.last_sequence == null);
    try std.testing.expect(guard.pressed_keys.count() == 0);
}
