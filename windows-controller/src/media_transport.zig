const std = @import("std");

pub const magic = [4]u8{ 'T', 'V', 'R', 'M' };
pub const version: u8 = 1;
pub const header_size: usize = 32;
pub const max_packet_size: usize = 4 * 1024 * 1024;
pub const max_queued_bytes: usize = 16 * 1024 * 1024;
pub const max_packets: usize = 128;

pub const Track = enum(u8) { video = 1, audio = 2 };
pub const Flags = packed struct(u16) {
    key_frame: bool = false,
    codec_config: bool = false,
    discontinuity: bool = false,
    end_of_stream: bool = false,
    _reserved: u12 = 0,
};

pub const Header = struct {
    track: Track,
    flags: Flags,
    sequence: u32,
    presentation_time_us: u64,
    payload_len: u32,
    codec_config_id: u32,
    width: u16 = 0,
    height: u16 = 0,

    pub fn encode(self: Header, output: *[header_size]u8) !void {
        if (self.payload_len > max_packet_size) return error.PacketTooLarge;
        @memcpy(output[0..4], &magic);
        output[4] = version;
        output[5] = @intFromEnum(self.track);
        std.mem.writeInt(u16, output[6..8], @bitCast(self.flags), .big);
        std.mem.writeInt(u32, output[8..12], self.sequence, .big);
        std.mem.writeInt(u64, output[12..20], self.presentation_time_us, .big);
        std.mem.writeInt(u32, output[20..24], self.payload_len, .big);
        std.mem.writeInt(u32, output[24..28], self.codec_config_id, .big);
        std.mem.writeInt(u16, output[28..30], self.width, .big);
        std.mem.writeInt(u16, output[30..32], self.height, .big);
    }

    pub fn decode(input: *const [header_size]u8) !Header {
        if (!std.mem.eql(u8, input[0..4], &magic)) return error.InvalidMagic;
        if (input[4] != version) return error.UnsupportedVersion;
        const payload_len = std.mem.readInt(u32, input[20..24], .big);
        if (payload_len > max_packet_size) return error.PacketTooLarge;
        const track = std.enums.fromInt(Track, input[5]) orelse return error.InvalidTrack;
        const flags: Flags = @bitCast(std.mem.readInt(u16, input[6..8], .big));
        if (flags._reserved != 0) return error.InvalidFlags;
        return .{
            .track = track,
            .flags = flags,
            .sequence = std.mem.readInt(u32, input[8..12], .big),
            .presentation_time_us = std.mem.readInt(u64, input[12..20], .big),
            .payload_len = payload_len,
            .codec_config_id = std.mem.readInt(u32, input[24..28], .big),
            .width = std.mem.readInt(u16, input[28..30], .big),
            .height = std.mem.readInt(u16, input[30..32], .big),
        };
    }
};

pub const OwnedPacket = struct {
    header: Header,
    payload: []u8,
};

pub fn validateStreamHeader(header: Header, sequences: *[2]u32, config_ids: *[2]u32) !void {
    const track_index: usize = @intFromEnum(header.track) - 1;
    if (header.sequence == 0 or header.sequence <= sequences[track_index]) return error.InvalidSequence;
    if (header.track == .audio and (header.width != 0 or header.height != 0)) return error.InvalidDimensions;
    if (header.flags.codec_config) {
        if (header.codec_config_id == 0 or header.codec_config_id <= config_ids[track_index] or header.payload_len == 0) {
            return error.InvalidCodecConfig;
        }
        config_ids[track_index] = header.codec_config_id;
    } else if (config_ids[track_index] == 0 or header.codec_config_id != config_ids[track_index]) {
        return error.MissingCodecConfig;
    }
    sequences[track_index] = header.sequence;
}

pub const PacketQueue = struct {
    allocator: std.mem.Allocator,
    packets: [max_packets]?OwnedPacket = @splat(null),
    count: usize = 0,
    queued_bytes: usize = 0,
    discontinuity_pending: bool = false,

    pub fn init(allocator: std.mem.Allocator) PacketQueue {
        return .{ .allocator = allocator };
    }

    pub fn deinit(self: *PacketQueue) void {
        while (self.count > 0) self.removeAt(0);
    }

    pub fn push(self: *PacketQueue, header: Header, payload: []const u8) !void {
        if (payload.len != header.payload_len) return error.PayloadLengthMismatch;
        if (payload.len > max_packet_size) return error.PacketTooLarge;
        while (self.count == max_packets or self.queued_bytes + payload.len > max_queued_bytes) {
            const index = self.firstDroppableVideo() orelse {
                if (header.track == .video and !header.flags.key_frame and !header.flags.codec_config) {
                    self.discontinuity_pending = true;
                    return error.DroppedIncomingVideo;
                }
                return error.QueueFull;
            };
            self.removeAt(index);
            self.discontinuity_pending = true;
        }
        var stored_header = header;
        if (self.discontinuity_pending and stored_header.track == .video and stored_header.flags.key_frame) {
            stored_header.flags.discontinuity = true;
            self.discontinuity_pending = false;
        }
        const copy = try self.allocator.dupe(u8, payload);
        self.packets[self.count] = .{ .header = stored_header, .payload = copy };
        self.count += 1;
        self.queued_bytes += copy.len;
    }

    pub fn peek(self: *const PacketQueue) ?*const OwnedPacket {
        if (self.count == 0) return null;
        return &self.packets[0].?;
    }

    pub fn pop(self: *PacketQueue, output: []u8) !Header {
        const packet = self.peek() orelse return error.NoPacket;
        if (output.len < packet.payload.len) return error.BufferTooSmall;
        const header = packet.header;
        @memcpy(output[0..packet.payload.len], packet.payload);
        self.removeAt(0);
        return header;
    }

    fn firstDroppableVideo(self: *const PacketQueue) ?usize {
        for (self.packets[0..self.count], 0..) |entry, index| {
            const packet = entry.?;
            if (packet.header.track == .video and !packet.header.flags.key_frame and !packet.header.flags.codec_config) return index;
        }
        return null;
    }

    fn removeAt(self: *PacketQueue, index: usize) void {
        const packet = self.packets[index].?;
        self.queued_bytes -= packet.payload.len;
        self.allocator.free(packet.payload);
        var cursor = index;
        while (cursor + 1 < self.count) : (cursor += 1) self.packets[cursor] = self.packets[cursor + 1];
        self.count -= 1;
        self.packets[self.count] = null;
    }
};

test "media header round trips with fixed 32 byte layout" {
    const original = Header{
        .track = .video,
        .flags = .{ .key_frame = true },
        .sequence = 42,
        .presentation_time_us = 123_456,
        .payload_len = 1024,
        .codec_config_id = 3,
        .width = 1280,
        .height = 720,
    };
    var bytes: [header_size]u8 = undefined;
    try original.encode(&bytes);
    const decoded = try Header.decode(&bytes);
    try std.testing.expectEqual(original.track, decoded.track);
    try std.testing.expectEqual(original.sequence, decoded.sequence);
    try std.testing.expect(decoded.flags.key_frame);
    try std.testing.expectEqual(@as(u16, 1280), decoded.width);
}

test "buffer-too-small does not consume media packet" {
    var queue = PacketQueue.init(std.testing.allocator);
    defer queue.deinit();
    try queue.push(.{ .track = .audio, .flags = .{}, .sequence = 1, .presentation_time_us = 0, .payload_len = 4, .codec_config_id = 1 }, "test");
    var small: [2]u8 = undefined;
    try std.testing.expectError(error.BufferTooSmall, queue.pop(&small));
    try std.testing.expectEqual(@as(usize, 1), queue.count);
    var output: [4]u8 = undefined;
    _ = try queue.pop(&output);
    try std.testing.expectEqualStrings("test", &output);
}

test "stream validation requires ordered packets and codec configuration" {
    var sequences: [2]u32 = @splat(0);
    var configs: [2]u32 = @splat(0);
    const config = Header{ .track = .video, .flags = .{ .codec_config = true }, .sequence = 1, .presentation_time_us = 0, .payload_len = 8, .codec_config_id = 1, .width = 1280, .height = 720 };
    try validateStreamHeader(config, &sequences, &configs);
    try validateStreamHeader(.{ .track = .video, .flags = .{}, .sequence = 2, .presentation_time_us = 1, .payload_len = 8, .codec_config_id = 1, .width = 1280, .height = 720 }, &sequences, &configs);
    try std.testing.expectError(error.InvalidSequence, validateStreamHeader(config, &sequences, &configs));
    try std.testing.expectError(error.MissingCodecConfig, validateStreamHeader(.{ .track = .audio, .flags = .{}, .sequence = 1, .presentation_time_us = 1, .payload_len = 8, .codec_config_id = 1 }, &sequences, &configs));
}
