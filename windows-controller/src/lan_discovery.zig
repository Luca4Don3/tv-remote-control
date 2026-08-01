const std = @import("std");
const transport = @import("transport_protocol.zig");

pub const max_datagram_size = 512;
pub const instance_id_hex_length = 64;
pub const max_display_name_length = 64;
pub const probe_payload = "{\"protocolVersion\":1,\"type\":\"probe\"}";

const WireResponse = struct {
    protocolVersion: u32,
    type: []const u8,
    instanceId: []const u8,
    displayName: []const u8,
    controlPort: u16,
};

pub const Response = struct {
    instance_id: [instance_id_hex_length]u8,
    display_name: [max_display_name_length]u8 = undefined,
    display_name_len: u8,
    control_port: u16,

    pub fn displayName(self: *const Response) []const u8 {
        return self.display_name[0..self.display_name_len];
    }
};

pub const FoundDevice = struct {
    response: Response,
    source: std.Io.net.IpAddress,
};

pub const max_devices = 32;

pub const DiscoveryResults = struct {
    devices: [max_devices]FoundDevice = undefined,
    count: usize = 0,

    pub fn slice(self: *const DiscoveryResults) []const FoundDevice {
        return self.devices[0..self.count];
    }
};

pub fn parseResponse(allocator: std.mem.Allocator, payload: []const u8) !Response {
    if (payload.len == 0 or payload.len > max_datagram_size) return error.InvalidDiscoveryResponse;
    const parsed = std.json.parseFromSlice(WireResponse, allocator, payload, .{
        .duplicate_field_behavior = .@"error",
        .ignore_unknown_fields = false,
        .max_value_len = max_display_name_length,
    }) catch return error.InvalidDiscoveryResponse;
    defer parsed.deinit();

    const value = parsed.value;
    if (value.protocolVersion != 1 or !std.mem.eql(u8, value.type, "probe_response")) return error.IncompatibleDiscoveryResponse;
    if (value.controlPort != transport.control_port) return error.InvalidDiscoveryResponse;
    if (!validLowerHexId(value.instanceId)) return error.InvalidDiscoveryResponse;
    if (!validDisplayName(value.displayName)) return error.InvalidDiscoveryResponse;

    var response = Response{
        .instance_id = undefined,
        .display_name_len = @intCast(value.displayName.len),
        .control_port = value.controlPort,
    };
    @memcpy(&response.instance_id, value.instanceId);
    @memcpy(response.display_name[0..value.displayName.len], value.displayName);
    return response;
}

/// Compatibility helper returning the first collected device.
pub fn discoverOne(
    allocator: std.mem.Allocator,
    io: std.Io,
    timeout: std.Io.Clock.Duration,
) !?FoundDevice {
    const results = try discoverMany(allocator, io, timeout);
    return if (results.count == 0) null else results.devices[0];
}

/// Collects all valid responses until the fixed deadline and de-duplicates
/// devices by their stable discovery instance id.
pub fn discoverMany(
    allocator: std.mem.Allocator,
    io: std.Io,
    timeout: std.Io.Clock.Duration,
) !DiscoveryResults {
    const local: std.Io.net.IpAddress = .{ .ip4 = .unspecified(0) };
    const socket = try local.bind(io, .{
        .allow_broadcast = true,
        .mode = .dgram,
        .protocol = .udp,
    });
    defer socket.close(io);

    const broadcast: std.Io.net.IpAddress = .{ .ip4 = .{
        .bytes = .{ 255, 255, 255, 255 },
        .port = transport.discovery_port,
    } };
    try socket.send(io, &broadcast, probe_payload);

    const deadline: std.Io.Timeout = .{ .deadline = .fromNow(io, timeout) };
    var buffer: [max_datagram_size]u8 = undefined;
    var results = DiscoveryResults{};
    while (results.count < max_devices) {
        const message = socket.receiveTimeout(io, &buffer, deadline) catch |err| switch (err) {
            error.Timeout => {
                std.log.info("lan discovery finished with {d} device(s)", .{results.count});
                return results;
            },
            else => |other| return other,
        };
        if (message.flags.trunc) continue;
        const response = parseResponse(allocator, message.data) catch continue;
        var duplicate = false;
        for (results.slice()) |existing| {
            if (std.mem.eql(u8, &existing.response.instance_id, &response.instance_id)) {
                duplicate = true;
                break;
            }
        }
        if (duplicate) continue;
        var source = message.from;
        source.setPort(response.control_port);
        results.devices[results.count] = .{ .response = response, .source = source };
        results.count += 1;
    }
    // 收集满上限时也报告数量，避免静默截断。
    std.log.info("lan discovery reached device cap {d}", .{results.count});
    return results;
}

fn validLowerHexId(value: []const u8) bool {
    if (value.len != instance_id_hex_length) return false;
    for (value) |byte| switch (byte) {
        '0'...'9', 'a'...'f' => {},
        else => return false,
    };
    return true;
}

fn validDisplayName(value: []const u8) bool {
    if (value.len == 0 or value.len > max_display_name_length) return false;
    const view = std.unicode.Utf8View.init(value) catch return false;
    var iterator = view.iterator();
    while (iterator.nextCodepoint()) |codepoint| {
        if (codepoint < 0x20 or (codepoint >= 0x7f and codepoint <= 0x9f)) return false;
    }
    return true;
}

test "strict discovery response exposes only pre-auth fields" {
    const payload =
        "{\"protocolVersion\":1,\"type\":\"probe_response\"," ++
        "\"instanceId\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"," ++
        "\"displayName\":\"Living Room TV\",\"controlPort\":47832}";
    const response = try parseResponse(std.testing.allocator, payload);
    try std.testing.expectEqualStrings("Living Room TV", response.displayName());
    try std.testing.expectEqual(@as(u16, transport.control_port), response.control_port);
}

test "discovery rejects unknown fields duplicate fields and control characters" {
    const base_id = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    var buffer: [512]u8 = undefined;
    const leaked = try std.fmt.bufPrint(
        &buffer,
        "{{\"protocolVersion\":1,\"type\":\"probe_response\",\"instanceId\":\"{s}\",\"displayName\":\"TV\",\"controlPort\":47832,\"model\":\"private\"}}",
        .{base_id},
    );
    try std.testing.expectError(error.InvalidDiscoveryResponse, parseResponse(std.testing.allocator, leaked));
    const duplicate = try std.fmt.bufPrint(
        &buffer,
        "{{\"protocolVersion\":1,\"protocolVersion\":1,\"type\":\"probe_response\",\"instanceId\":\"{s}\",\"displayName\":\"TV\",\"controlPort\":47832}}",
        .{base_id},
    );
    try std.testing.expectError(error.InvalidDiscoveryResponse, parseResponse(std.testing.allocator, duplicate));
    const control = try std.fmt.bufPrint(
        &buffer,
        "{{\"protocolVersion\":1,\"type\":\"probe_response\",\"instanceId\":\"{s}\",\"displayName\":\"TV\\u000aName\",\"controlPort\":47832}}",
        .{base_id},
    );
    try std.testing.expectError(error.InvalidDiscoveryResponse, parseResponse(std.testing.allocator, control));
}
