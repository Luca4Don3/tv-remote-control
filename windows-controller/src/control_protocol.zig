const std = @import("std");
const transport = @import("transport_protocol.zig");

pub const protocol_version: i64 = 1;
pub const max_identifier_length: usize = 128;
pub const max_type_length: usize = 64;

pub const Decoded = struct {
    parsed: std.json.Parsed(std.json.Value),
    request_id: []const u8,
    session_id: []const u8,
    sequence: u64,
    message_type: []const u8,

    pub fn deinit(self: *Decoded) void {
        self.parsed.deinit();
        self.* = undefined;
    }

    pub fn payload(self: *const Decoded) std.json.ObjectMap {
        const root = self.parsed.value.object;
        return root.get("payload").?.object;
    }

    pub fn requirePayloadFields(
        self: *const Decoded,
        required: []const []const u8,
        optional: []const []const u8,
    ) !void {
        const object = self.payload();
        if (object.count() != required.len + countPresentOptional(object, optional)) {
            return error.ProtocolViolation;
        }
        for (required) |field| if (!object.contains(field)) return error.ProtocolViolation;
        var iterator = object.iterator();
        while (iterator.next()) |entry| {
            if (!containsName(required, entry.key_ptr.*) and !containsName(optional, entry.key_ptr.*)) {
                return error.ProtocolViolation;
            }
        }
    }

    pub fn requireString(self: *const Decoded, field: []const u8, max_len: usize) ![]const u8 {
        return requireObjectString(self.payload(), field, max_len);
    }

    pub fn requireInteger(self: *const Decoded, field: []const u8, minimum: i64, maximum: i64) !i64 {
        return requireObjectInteger(self.payload(), field, minimum, maximum);
    }

    pub fn requireObject(self: *const Decoded, field: []const u8) !std.json.ObjectMap {
        const value = self.payload().get(field) orelse return error.ProtocolViolation;
        return switch (value) {
            .object => |object| object,
            else => error.ProtocolViolation,
        };
    }
};

pub fn decode(allocator: std.mem.Allocator, bytes: []const u8) !Decoded {
    if (bytes.len == 0 or bytes.len > transport.max_frame_size) return error.ProtocolViolation;
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, bytes, .{
        .duplicate_field_behavior = .@"error",
        .max_value_len = transport.max_frame_size,
    }) catch return error.ProtocolViolation;
    errdefer parsed.deinit();
    const root = switch (parsed.value) {
        .object => |object| object,
        else => return error.ProtocolViolation,
    };
    const envelope_fields = [_][]const u8{
        "protocolVersion", "requestId", "sessionId", "sequence", "type", "payload",
    };
    if (root.count() != envelope_fields.len) return error.ProtocolViolation;
    for (&envelope_fields) |field| if (!root.contains(field)) return error.ProtocolViolation;

    const version = valueInteger(root.get("protocolVersion").?) orelse return error.ProtocolViolation;
    if (version != protocol_version) return error.ProtocolViolation;
    const request_id = valueString(root.get("requestId").?) orelse return error.ProtocolViolation;
    if (!validIdentifier(request_id, false)) return error.ProtocolViolation;
    const session_id = valueString(root.get("sessionId").?) orelse return error.ProtocolViolation;
    if (!validIdentifier(session_id, true)) return error.ProtocolViolation;
    const sequence = valueInteger(root.get("sequence").?) orelse return error.ProtocolViolation;
    if (sequence <= 0) return error.ProtocolViolation;
    const message_type = valueString(root.get("type").?) orelse return error.ProtocolViolation;
    if (!validType(message_type)) return error.ProtocolViolation;
    if (root.get("payload").? != .object) return error.ProtocolViolation;

    return .{
        .parsed = parsed,
        .request_id = request_id,
        .session_id = session_id,
        .sequence = @intCast(sequence),
        .message_type = message_type,
    };
}

pub fn encodeFrame(
    output: []u8,
    request_id: []const u8,
    session_id: []const u8,
    sequence: u64,
    message_type: []const u8,
    payload_value: anytype,
) ![]const u8 {
    if (output.len < transport.frame_header_size + 1) return error.BufferTooSmall;
    if (!validIdentifier(request_id, false) or !validIdentifier(session_id, true) or
        sequence == 0 or sequence > std.math.maxInt(i64) or !validType(message_type))
    {
        return error.InvalidEnvelope;
    }
    var writer: std.Io.Writer = .fixed(output[transport.frame_header_size..]);
    std.json.Stringify.value(.{
        .protocolVersion = protocol_version,
        .requestId = request_id,
        .sessionId = session_id,
        .sequence = sequence,
        .type = message_type,
        .payload = payload_value,
    }, .{}, &writer) catch return error.BufferTooSmall;
    const payload_len = writer.buffered().len;
    // payload_len == 0 表示空帧（编码异常），与超长帧区分开。
    if (payload_len == 0) return error.EmptyFrame;
    if (payload_len > transport.max_frame_size) return error.FrameTooLarge;
    std.mem.writeInt(u32, output[0..transport.frame_header_size], @intCast(payload_len), .big);
    return output[0 .. payload_len + transport.frame_header_size];
}

pub fn requireObjectString(object: std.json.ObjectMap, field: []const u8, max_len: usize) ![]const u8 {
    const value = object.get(field) orelse return error.ProtocolViolation;
    const string = valueString(value) orelse return error.ProtocolViolation;
    if (string.len == 0 or string.len > max_len or !std.unicode.utf8ValidateSlice(string)) {
        return error.ProtocolViolation;
    }
    return string;
}

pub fn requireObjectInteger(object: std.json.ObjectMap, field: []const u8, minimum: i64, maximum: i64) !i64 {
    const value = object.get(field) orelse return error.ProtocolViolation;
    const integer = valueInteger(value) orelse return error.ProtocolViolation;
    if (integer < minimum or integer > maximum) return error.ProtocolViolation;
    return integer;
}

pub fn validIdentifier(value: []const u8, allow_empty: bool) bool {
    if (value.len == 0) return allow_empty;
    if (value.len > max_identifier_length) return false;
    for (value) |byte| switch (byte) {
        'A'...'Z', 'a'...'z', '0'...'9', '.', '_', ':', '-' => {},
        else => return false,
    };
    return true;
}

pub fn validType(value: []const u8) bool {
    if (value.len == 0 or value.len > max_type_length or value[0] < 'a' or value[0] > 'z') return false;
    for (value[1..]) |byte| switch (byte) {
        'a'...'z', '0'...'9', '_' => {},
        else => return false,
    };
    return true;
}

fn valueString(value: std.json.Value) ?[]const u8 {
    return switch (value) {
        .string => |string| string,
        else => null,
    };
}

fn valueInteger(value: std.json.Value) ?i64 {
    return switch (value) {
        .integer => |integer| integer,
        else => null,
    };
}

fn containsName(values: []const []const u8, candidate: []const u8) bool {
    for (values) |value| if (std.mem.eql(u8, value, candidate)) return true;
    return false;
}

fn countPresentOptional(object: std.json.ObjectMap, optional: []const []const u8) usize {
    var count: usize = 0;
    for (optional) |field| if (object.contains(field)) {
        count += 1;
    };
    return count;
}

test "control envelope round trips with strict Android field names" {
    var buffer: [1024]u8 = undefined;
    const encoded = try encodeFrame(&buffer, "client-1", "", 1, "pair_request", .{
        .code = "123456",
        .controllerName = "Windows Controller",
        .controllerNonce = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
    });
    const payload = try transport.decodeFrame(encoded);
    var decoded = try decode(std.testing.allocator, payload);
    defer decoded.deinit();
    try std.testing.expectEqualStrings("client-1", decoded.request_id);
    try std.testing.expectEqualStrings("pair_request", decoded.message_type);
    try decoded.requirePayloadFields(&.{ "code", "controllerName", "controllerNonce" }, &.{});
    try std.testing.expectEqualStrings("123456", try decoded.requireString("code", 6));
}

test "control envelope rejects replay-shaped and ambiguous JSON" {
    const duplicate =
        "{\"protocolVersion\":1,\"requestId\":\"a\",\"requestId\":\"b\",\"sessionId\":\"\",\"sequence\":1,\"type\":\"ping\",\"payload\":{}}";
    try std.testing.expectError(error.ProtocolViolation, decode(std.testing.allocator, duplicate));
    const unknown =
        "{\"protocolVersion\":1,\"requestId\":\"a\",\"sessionId\":\"\",\"sequence\":1,\"type\":\"ping\",\"payload\":{},\"extra\":true}";
    try std.testing.expectError(error.ProtocolViolation, decode(std.testing.allocator, unknown));
    const invalid_id =
        "{\"protocolVersion\":1,\"requestId\":\"bad id\",\"sessionId\":\"\",\"sequence\":1,\"type\":\"ping\",\"payload\":{}}";
    try std.testing.expectError(error.ProtocolViolation, decode(std.testing.allocator, invalid_id));
}
