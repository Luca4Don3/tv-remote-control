const std = @import("std");
const control_protocol = @import("control_protocol.zig");
const lan_discovery = @import("lan_discovery.zig");
const tls_transport = @import("tls_transport.zig");
const transport = @import("transport_protocol.zig");

pub const ReadFn = *const fn (*anyopaque, []u8) anyerror!usize;
pub const WriteFn = *const fn (*anyopaque, []const u8) anyerror!void;

/// Injectable framed control I/O. Production wraps the existing pinned TLS
/// client; tests may provide a deterministic television state machine.
pub const Channel = struct {
    context: *anyopaque,
    read_fn: ReadFn,
    write_fn: WriteFn,

    pub fn read(self: Channel, output: []u8) !usize {
        if (output.len == 0) return error.InvalidBuffer;
        return self.read_fn(self.context, output);
    }

    pub fn write(self: Channel, bytes: []const u8) !void {
        if (bytes.len == 0) return error.InvalidBuffer;
        try self.write_fn(self.context, bytes);
    }
};

pub fn forTls(client: *tls_transport.Client, provisional: bool) Channel {
    return .{
        .context = client,
        .read_fn = if (provisional) tlsPairingRead else tlsRead,
        .write_fn = if (provisional) tlsPairingWrite else tlsWrite,
    };
}

fn tlsPairingRead(context: *anyopaque, output: []u8) !usize {
    const client: *tls_transport.Client = @ptrCast(@alignCast(context));
    return client.pairingRead(output);
}

fn tlsPairingWrite(context: *anyopaque, bytes: []const u8) !void {
    const client: *tls_transport.Client = @ptrCast(@alignCast(context));
    try client.pairingWrite(bytes);
}

fn tlsRead(context: *anyopaque, output: []u8) !usize {
    const client: *tls_transport.Client = @ptrCast(@alignCast(context));
    return client.read(output);
}

fn tlsWrite(context: *anyopaque, bytes: []const u8) !void {
    const client: *tls_transport.Client = @ptrCast(@alignCast(context));
    try client.write(bytes);
}

const ScriptedTelevision = struct {
    state: State = .discovered,
    credential_saved: bool = false,
    response: [transport.max_frame_size + transport.frame_header_size]u8 = undefined,
    response_len: usize = 0,
    response_sequence: u64 = 1,

    const State = enum { discovered, pairing, paired, authenticating, connected, disconnected };

    fn channel(self: *ScriptedTelevision) Channel {
        return .{ .context = self, .read_fn = read, .write_fn = write };
    }

    fn write(context: *anyopaque, frame: []const u8) !void {
        const self: *ScriptedTelevision = @ptrCast(@alignCast(context));
        const envelope = try transport.decodeFrame(frame);
        var decoded = try control_protocol.decode(std.testing.allocator, envelope);
        defer decoded.deinit();
        const response_type: []const u8 = if (std.mem.eql(u8, decoded.message_type, "pair_request") and self.state == .discovered) blk: {
            self.state = .pairing;
            break :blk "pairing_sas";
        } else if (std.mem.eql(u8, decoded.message_type, "pair_store_ack") and self.state == .pairing) blk: {
            self.state = .paired;
            self.credential_saved = true;
            break :blk "pair_complete";
        } else if (std.mem.eql(u8, decoded.message_type, "auth_begin") and
            (self.state == .paired or self.state == .disconnected)) blk: {
            self.state = .authenticating;
            break :blk "auth_challenge";
        } else if (std.mem.eql(u8, decoded.message_type, "auth_response") and self.state == .authenticating) blk: {
            self.state = .connected;
            break :blk "auth_complete";
        } else if (std.mem.eql(u8, decoded.message_type, "key_event") and self.state == .connected)
            "command_ack"
        else if (std.mem.eql(u8, decoded.message_type, "disconnect") and self.state == .connected) blk: {
            self.state = .disconnected;
            break :blk "disconnect_ack";
        } else return error.UnexpectedScriptMessage;

        var envelope_buffer: [transport.max_frame_size]u8 = undefined;
        const session_id = if (self.state == .connected or self.state == .disconnected) "session-1" else "";
        const response_envelope = try control_protocol.encodeEnvelope(
            &envelope_buffer,
            decoded.request_id,
            session_id,
            self.response_sequence,
            response_type,
            struct {}{},
        );
        self.response_sequence += 1;
        const response_frame = try transport.encodeFrame(response_envelope, &self.response);
        self.response_len = response_frame.len;
    }

    fn read(context: *anyopaque, output: []u8) !usize {
        const self: *ScriptedTelevision = @ptrCast(@alignCast(context));
        if (self.response_len == 0) return error.NoScriptedResponse;
        if (output.len < self.response_len) return error.BufferTooSmall;
        @memcpy(output[0..self.response_len], self.response[0..self.response_len]);
        const count = self.response_len;
        self.response_len = 0;
        return count;
    }
};

const ScriptResponse = enum { pairing_sas, pair_complete, auth_challenge, auth_complete, command_ack, disconnect_ack };

fn scriptedExchange(
    channel: Channel,
    sequence: u64,
    request_id: []const u8,
    session_id: []const u8,
    message_type: []const u8,
) !ScriptResponse {
    var envelope_buffer: [1024]u8 = undefined;
    const envelope = try control_protocol.encodeEnvelope(
        &envelope_buffer,
        request_id,
        session_id,
        sequence,
        message_type,
        struct {}{},
    );
    var frame_buffer: [1028]u8 = undefined;
    try channel.write(try transport.encodeFrame(envelope, &frame_buffer));
    var response_buffer: [1024]u8 = undefined;
    const count = try channel.read(&response_buffer);
    const response_envelope = try transport.decodeFrame(response_buffer[0..count]);
    var decoded = try control_protocol.decode(std.testing.allocator, response_envelope);
    defer decoded.deinit();
    if (std.mem.eql(u8, decoded.message_type, "pairing_sas")) return .pairing_sas;
    if (std.mem.eql(u8, decoded.message_type, "pair_complete")) return .pair_complete;
    if (std.mem.eql(u8, decoded.message_type, "auth_challenge")) return .auth_challenge;
    if (std.mem.eql(u8, decoded.message_type, "auth_complete")) return .auth_complete;
    if (std.mem.eql(u8, decoded.message_type, "command_ack")) return .command_ack;
    if (std.mem.eql(u8, decoded.message_type, "disconnect_ack")) return .disconnect_ack;
    return error.UnexpectedScriptResponse;
}

test "scripted television covers discovery pairing credential auth key disconnect and reconnect" {
    const discovery_payload =
        "{\"protocolVersion\":1,\"type\":\"probe_response\"," ++
        "\"instanceId\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"," ++
        "\"displayName\":\"Scripted TV\",\"controlPort\":47832}";
    const discovery = try lan_discovery.parseResponse(std.testing.allocator, discovery_payload);
    try std.testing.expectEqualStrings("Scripted TV", discovery.displayName());

    var television = ScriptedTelevision{};
    const channel = television.channel();
    try std.testing.expectEqual(ScriptResponse.pairing_sas, try scriptedExchange(channel, 1, "pair-1", "", "pair_request"));
    try std.testing.expectEqual(ScriptResponse.pair_complete, try scriptedExchange(channel, 2, "pair-store", "", "pair_store_ack"));
    try std.testing.expect(television.credential_saved);
    try std.testing.expectEqual(ScriptResponse.auth_challenge, try scriptedExchange(channel, 3, "auth-1", "", "auth_begin"));
    try std.testing.expectEqual(ScriptResponse.auth_complete, try scriptedExchange(channel, 4, "auth-2", "", "auth_response"));
    try std.testing.expectEqual(ScriptResponse.command_ack, try scriptedExchange(channel, 5, "key-1", "session-1", "key_event"));
    try std.testing.expectEqual(ScriptResponse.disconnect_ack, try scriptedExchange(channel, 6, "disconnect-1", "session-1", "disconnect"));
    try std.testing.expectEqual(ScriptResponse.auth_challenge, try scriptedExchange(channel, 7, "auth-3", "", "auth_begin"));
    try std.testing.expectEqual(ScriptResponse.auth_complete, try scriptedExchange(channel, 8, "auth-4", "", "auth_response"));
}
