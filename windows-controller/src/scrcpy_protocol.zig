const std = @import("std");
const media_transport = @import("media_transport.zig");

pub const locked_server_version = "4.1";
pub const device_name_length = 64;
pub const video_codec_metadata_length = 12;
pub const audio_codec_metadata_length = 4;
pub const packet_header_length = 12;
pub const configuration_flag: u64 = 1 << 63;
pub const key_frame_flag: u64 = 1 << 62;
pub const timestamp_mask: u64 = key_frame_flag - 1;

pub const VideoCodec = enum(u32) {
    h264 = fourcc("h264"),
};

pub const AudioCodec = enum(u32) {
    aac = fourcc("aac "),
};

pub const VideoMetadata = struct {
    codec: VideoCodec,
    width: u32,
    height: u32,

    pub fn decode(input: *const [video_codec_metadata_length]u8) !VideoMetadata {
        const width = std.mem.readInt(u32, input[4..8], .big);
        const height = std.mem.readInt(u32, input[8..12], .big);
        if (width == 0 or height == 0 or width > std.math.maxInt(u16) or height > std.math.maxInt(u16)) {
            return error.InvalidVideoDimensions;
        }
        return .{
            .codec = std.enums.fromInt(VideoCodec, std.mem.readInt(u32, input[0..4], .big)) orelse
                return error.UnsupportedVideoCodec,
            .width = width,
            .height = height,
        };
    }
};

pub const AudioMetadata = struct {
    codec: AudioCodec,

    pub fn decode(input: *const [audio_codec_metadata_length]u8) !AudioMetadata {
        return .{
            .codec = std.enums.fromInt(AudioCodec, std.mem.readInt(u32, input, .big)) orelse
                return error.UnsupportedAudioCodec,
        };
    }
};

pub const PacketHeader = struct {
    configuration: bool,
    key_frame: bool,
    presentation_time_us: u64,
    payload_len: u32,

    pub fn decode(input: *const [packet_header_length]u8) !PacketHeader {
        const encoded_timestamp = std.mem.readInt(u64, input[0..8], .big);
        const payload_len = std.mem.readInt(u32, input[8..12], .big);
        if (payload_len == 0 or payload_len > media_transport.max_packet_size) return error.PacketTooLarge;
        return .{
            .configuration = encoded_timestamp & configuration_flag != 0,
            .key_frame = encoded_timestamp & key_frame_flag != 0,
            .presentation_time_us = encoded_timestamp & timestamp_mask,
            .payload_len = payload_len,
        };
    }
};

pub fn validateDeviceName(input: *const [device_name_length]u8) ![]const u8 {
    const end = std.mem.indexOfScalar(u8, input, 0) orelse input.len;
    const name = input[0..end];
    if (name.len == 0 or !std.unicode.utf8ValidateSlice(name)) return error.InvalidDeviceName;
    for (input[end..]) |byte| if (byte != 0) return error.InvalidDeviceNamePadding;
    return name;
}

pub const H264Adapter = struct {
    configuration_id: u32 = 0,
    sequence: u32 = 0,
    width: u16,
    height: u16,

    pub fn init(metadata: VideoMetadata) H264Adapter {
        return .{ .width = @intCast(metadata.width), .height = @intCast(metadata.height) };
    }

    pub fn adapt(
        self: *H264Adapter,
        packet: PacketHeader,
        payload: []const u8,
        output: []u8,
    ) !AdaptedPacket {
        if (payload.len != packet.payload_len) return error.PayloadLengthMismatch;
        self.sequence +%= 1;
        if (packet.configuration) {
            self.configuration_id +%= 1;
            if (self.configuration_id == 0) self.configuration_id = 1;
            const bytes = try annexBConfigurationToAvcc(payload, output);
            return .{
                .header = .{
                    .track = .video,
                    .flags = .{ .codec_config = true },
                    .sequence = self.sequence,
                    .presentation_time_us = 0,
                    .payload_len = @intCast(bytes.len),
                    .codec_config_id = self.configuration_id,
                    .width = self.width,
                    .height = self.height,
                },
                .payload = bytes,
            };
        }
        if (self.configuration_id == 0) return error.MissingCodecConfiguration;
        const bytes = try annexBAccessUnitToAvcc(payload, output);
        return .{
            .header = .{
                .track = .video,
                .flags = .{ .key_frame = packet.key_frame },
                .sequence = self.sequence,
                .presentation_time_us = packet.presentation_time_us,
                .payload_len = @intCast(bytes.len),
                .codec_config_id = self.configuration_id,
                .width = self.width,
                .height = self.height,
            },
            .payload = bytes,
        };
    }
};

pub const AudioAdapter = struct {
    configuration_id: u32 = 0,
    sequence: u32 = 0,

    pub fn adapt(self: *AudioAdapter, packet: PacketHeader, payload: []const u8) !AdaptedPacket {
        if (payload.len != packet.payload_len) return error.PayloadLengthMismatch;
        self.sequence +%= 1;
        if (packet.configuration) {
            if (payload.len < 2 or payload.len > 64) return error.InvalidAudioConfiguration;
            self.configuration_id +%= 1;
            if (self.configuration_id == 0) self.configuration_id = 1;
        } else if (self.configuration_id == 0) {
            return error.MissingCodecConfiguration;
        }
        return .{
            .header = .{
                .track = .audio,
                .flags = .{ .codec_config = packet.configuration },
                .sequence = self.sequence,
                .presentation_time_us = if (packet.configuration) 0 else packet.presentation_time_us,
                .payload_len = @intCast(payload.len),
                .codec_config_id = self.configuration_id,
            },
            .payload = payload,
        };
    }
};

pub const AdaptedPacket = struct {
    header: media_transport.Header,
    payload: []const u8,
};

pub fn annexBConfigurationToAvcc(input: []const u8, output: []u8) ![]const u8 {
    var iterator = AnnexBIterator.init(input);
    var sps: ?[]const u8 = null;
    var pps: ?[]const u8 = null;
    while (iterator.next()) |nal| {
        if (nal.len == 0) continue;
        switch (nal[0] & 0x1f) {
            7 => if (sps == null) {
                sps = nal;
            },
            8 => if (pps == null) {
                pps = nal;
            },
            else => {},
        }
    }
    const sequence = sps orelse return error.MissingSequenceParameterSet;
    const picture = pps orelse return error.MissingPictureParameterSet;
    // SPS 基本字段校验：长度至少覆盖 NAL header + profile_idc + constraint
    // flags + level_idc（4 字节）；nal_unit_type 必须为 7（SPS），PPS 为 8。
    // 其余 profile 字段由播放器解析，这里只拒绝明显畸形的配置。
    if (sequence.len < 4 or (sequence[0] & 0x1f) != 7 or (picture[0] & 0x1f) != 8) {
        return error.InvalidCodecConfiguration;
    }
    if (sequence.len > std.math.maxInt(u16) or picture.len > std.math.maxInt(u16)) {
        return error.InvalidCodecConfiguration;
    }
    const required = 11 + sequence.len + picture.len;
    if (output.len < required) return error.BufferTooSmall;
    output[0] = 1;
    output[1] = sequence[1];
    output[2] = sequence[2];
    output[3] = sequence[3];
    output[4] = 0xff;
    output[5] = 0xe1;
    std.mem.writeInt(u16, output[6..8], @intCast(sequence.len), .big);
    @memcpy(output[8 .. 8 + sequence.len], sequence);
    var cursor = 8 + sequence.len;
    output[cursor] = 1;
    cursor += 1;
    std.mem.writeInt(u16, output[cursor..][0..2], @intCast(picture.len), .big);
    cursor += 2;
    @memcpy(output[cursor .. cursor + picture.len], picture);
    return output[0..required];
}

pub fn annexBAccessUnitToAvcc(input: []const u8, output: []u8) ![]const u8 {
    var iterator = AnnexBIterator.init(input);
    var cursor: usize = 0;
    var count: usize = 0;
    while (iterator.next()) |nal| {
        if (nal.len == 0 or nal.len > std.math.maxInt(u32)) return error.InvalidAccessUnit;
        if (cursor + 4 + nal.len > output.len) return error.BufferTooSmall;
        std.mem.writeInt(u32, output[cursor..][0..4], @intCast(nal.len), .big);
        cursor += 4;
        @memcpy(output[cursor .. cursor + nal.len], nal);
        cursor += nal.len;
        count += 1;
    }
    if (count == 0) return error.InvalidAccessUnit;
    return output[0..cursor];
}

const AnnexBIterator = struct {
    input: []const u8,
    cursor: usize,

    fn init(input: []const u8) AnnexBIterator {
        return .{ .input = input, .cursor = findStartCode(input, 0) orelse input.len };
    }

    fn next(self: *AnnexBIterator) ?[]const u8 {
        if (self.cursor == self.input.len) return null;
        const prefix_len = startCodeLength(self.input, self.cursor) orelse {
            self.cursor = self.input.len;
            return null;
        };
        const start = self.cursor + prefix_len;
        const next_start = findStartCode(self.input, start) orelse self.input.len;
        self.cursor = next_start;
        var end = next_start;
        while (end > start and self.input[end - 1] == 0) end -= 1;
        return self.input[start..end];
    }
};

fn findStartCode(input: []const u8, start: usize) ?usize {
    var index = start;
    while (index + 3 <= input.len) : (index += 1) {
        if (startCodeLength(input, index) != null) return index;
    }
    return null;
}

fn startCodeLength(input: []const u8, index: usize) ?usize {
    if (index + 3 <= input.len and input[index] == 0 and input[index + 1] == 0 and input[index + 2] == 1) {
        return 3;
    }
    if (index + 4 <= input.len and input[index] == 0 and input[index + 1] == 0 and
        input[index + 2] == 0 and input[index + 3] == 1)
    {
        return 4;
    }
    return null;
}

fn fourcc(comptime text: *const [4]u8) u32 {
    return (@as(u32, text[0]) << 24) | (@as(u32, text[1]) << 16) |
        (@as(u32, text[2]) << 8) | text[3];
}

test "scrcpy frame metadata maps only locked H264 and bounded payloads" {
    var metadata: [video_codec_metadata_length]u8 = undefined;
    std.mem.writeInt(u32, metadata[0..4], fourcc("h264"), .big);
    std.mem.writeInt(u32, metadata[4..8], 1280, .big);
    std.mem.writeInt(u32, metadata[8..12], 720, .big);
    const decoded = try VideoMetadata.decode(&metadata);
    try std.testing.expectEqual(VideoCodec.h264, decoded.codec);

    var packet: [packet_header_length]u8 = undefined;
    std.mem.writeInt(u64, packet[0..8], key_frame_flag | 1234, .big);
    std.mem.writeInt(u32, packet[8..12], 4, .big);
    const header = try PacketHeader.decode(&packet);
    try std.testing.expect(header.key_frame);
    try std.testing.expectEqual(@as(u64, 1234), header.presentation_time_us);
}

test "scrcpy Annex B is normalized to the desktop AVCC wire" {
    const configuration = &[_]u8{ 0, 0, 0, 1, 0x67, 0x64, 0, 0x1f, 0, 0, 1, 0x68, 1 };
    var configuration_output: [64]u8 = undefined;
    const avcc = try annexBConfigurationToAvcc(configuration, &configuration_output);
    try std.testing.expectEqual(@as(u8, 1), avcc[0]);

    const access_unit = &[_]u8{ 0, 0, 1, 0x65, 1, 2, 0, 0, 0, 1, 0x41, 3 };
    var access_unit_output: [64]u8 = undefined;
    const normalized = try annexBAccessUnitToAvcc(access_unit, &access_unit_output);
    try std.testing.expectEqual(@as(u32, 3), std.mem.readInt(u32, normalized[0..4], .big));
    try std.testing.expectEqual(@as(u32, 2), std.mem.readInt(u32, normalized[7..11], .big));
}
