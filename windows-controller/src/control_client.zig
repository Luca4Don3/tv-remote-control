const std = @import("std");
const control_protocol = @import("control_protocol.zig");
const control_channel = @import("control_channel.zig");
const credential_store = @import("credential_store.zig");
const security = @import("security_protocol.zig");
const tls_transport = @import("tls_transport.zig");
const transport = @import("transport_protocol.zig");

const connection_read_timeout_ms: u32 = 1_000;
const pre_auth_deadline_ms: i64 = 30_000;
const command_deadline_ms: i64 = 10_000;
const disconnect_deadline_ms: i64 = 5_000;
const credential_magic = "TVRCCR1!";
const credential_version: u8 = 1;
const credential_record_length = credential_magic.len + 1 + security.fingerprint_length + 1 + 32 + security.pairing_secret_length;

pub const Key = enum(u32) {
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

pub const KeyState = enum(u32) { down, repeat, up, press };

pub const key_names = [_][]const u8{
    "DPAD_UP",      "DPAD_DOWN",        "DPAD_LEFT",  "DPAD_RIGHT",  "DPAD_CENTER",    "BACK",
    "HOME",         "MENU",             "VOLUME_UP",  "VOLUME_DOWN", "VOLUME_MUTE",    "CHANNEL_UP",
    "CHANNEL_DOWN", "MEDIA_PLAY_PAUSE", "MEDIA_STOP", "MEDIA_NEXT",  "MEDIA_PREVIOUS", "POWER",
};

pub const key_state_names = [_][]const u8{ "DOWN", "REPEAT", "UP", "PRESS" };

pub const KeyCapability = enum {
    supported,
    best_effort,
    permission_required,
    unsupported,
    unverified,

    pub fn enabled(self: KeyCapability) bool {
        return self == .supported or self == .best_effort;
    }

    pub fn wireName(self: KeyCapability) []const u8 {
        return switch (self) {
            .supported => "SUPPORTED",
            .best_effort => "BEST_EFFORT",
            .permission_required => "PERMISSION_REQUIRED",
            .unsupported => "UNSUPPORTED",
            .unverified => "UNVERIFIED",
        };
    }
};

pub const Capabilities = struct {
    keys: [key_names.len]KeyCapability = @splat(.unverified),
    media_transport: KeyCapability = .unverified,

    fn parse(object: std.json.ObjectMap) !Capabilities {
        const value = object.get("keySupport") orelse return error.ProtocolViolation;
        const key_support = switch (value) {
            .object => |map| map,
            else => return error.ProtocolViolation,
        };
        if (key_support.count() != key_names.len) return error.ProtocolViolation;
        var result = Capabilities{};
        if (object.get("mediaTransport")) |media_value| {
            const media_status = switch (media_value) {
                .string => |string| string,
                else => return error.ProtocolViolation,
            };
            result.media_transport = parseCapability(media_status) orelse return error.ProtocolViolation;
        }
        for (key_names, 0..) |name, index| {
            const status_value = key_support.get(name) orelse return error.ProtocolViolation;
            const status = switch (status_value) {
                .string => |string| string,
                else => return error.ProtocolViolation,
            };
            result.keys[index] = parseCapability(status) orelse return error.ProtocolViolation;
        }
        return result;
    }

    pub fn enabled(self: *const Capabilities, key: Key) bool {
        return self.keys[@intFromEnum(key)].enabled();
    }

    pub fn writeJson(self: *const Capabilities, output: []u8) ![]const u8 {
        var writer: std.Io.Writer = .fixed(output);
        try writer.writeAll("{\"keySupport\":{");
        for (key_names, self.keys, 0..) |name, status, index| {
            if (index != 0) try writer.writeByte(',');
            try writer.print("\"{s}\":\"{s}\"", .{ name, status.wireName() });
        }
        try writer.print("}},\"mediaTransport\":\"{s}\"}}", .{self.media_transport.wireName()});
        return writer.buffered();
    }
};

pub const SasDetails = struct {
    sas: [security.pairing_code_length]u8,
    pairing_id: [32]u8,
    expires_in_ms: i64,
};

pub const AckStatus = enum {
    success,
    unsupported,
    permission_denied,
    mapping_missing,
    rejected,
    execution_failed,

    pub fn wireName(self: AckStatus) []const u8 {
        return switch (self) {
            .success => "SUCCESS",
            .unsupported => "UNSUPPORTED",
            .permission_denied => "PERMISSION_DENIED",
            .mapping_missing => "MAPPING_MISSING",
            .rejected => "REJECTED",
            .execution_failed => "EXECUTION_FAILED",
        };
    }
};

pub const Ack = struct {
    command_sequence: u64,
    status: AckStatus,
    reason: [256]u8 = undefined,
    reason_len: u16 = 0,

    pub fn reasonSlice(self: *const Ack) []const u8 {
        return self.reason[0..self.reason_len];
    }

    pub fn writeJson(self: *const Ack, output: []u8) ![]const u8 {
        var writer: std.Io.Writer = .fixed(output);
        std.json.Stringify.value(.{
            .commandSequence = self.command_sequence,
            .status = self.status.wireName(),
            .reason = self.reasonSlice(),
        }, .{}, &writer) catch return error.BufferTooSmall;
        return writer.buffered();
    }
};

pub const PollResult = enum { no_change, capabilities_changed, media_state_changed };

pub const MediaOffer = struct {
    token: [64]u8,
    expires_in_ms: i64,
};

pub const MediaConnectionInfo = struct {
    host: [255]u8 = undefined,
    host_len: u16,
    port: u16,
    fingerprint: [security.fingerprint_length]u8,
    session_id: [control_protocol.max_identifier_length]u8 = undefined,
    session_id_len: u8,

    pub fn hostSlice(self: *const MediaConnectionInfo) []const u8 {
        return self.host[0..self.host_len];
    }

    pub fn sessionId(self: *const MediaConnectionInfo) []const u8 {
        return self.session_id[0..self.session_id_len];
    }
};

const Target = struct {
    address: std.Io.net.IpAddress,
    host: [255]u8 = undefined,
    host_len: u16,
    credential_id: [32]u8,

    fn hostSlice(self: *const Target) []const u8 {
        return self.host[0..self.host_len];
    }
};

const StoredCredential = struct {
    fingerprint: [security.fingerprint_length]u8,
    controller_id: [32]u8,
    controller_id_len: u8,
    secret: [security.pairing_secret_length]u8,

    fn controllerId(self: *const StoredCredential) []const u8 {
        return self.controller_id[0..self.controller_id_len];
    }
};

const PairingContext = struct {
    pairing_id: [32]u8,
    deadline_ms: i64,
};

pub const CredentialMigrationOutcome = enum {
    not_needed,
    completed,
    cleanup_pending,
};

pub const Client = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    credentials: credential_store.Adapter,
    tls: tls_transport.Client,
    decoder: transport.FrameDecoder = .{},
    target: ?Target = null,
    pairing: ?PairingContext = null,
    inbound_sequence: u64 = 0,
    outbound_sequence: u64 = 0,
    session_id: [control_protocol.max_identifier_length]u8 = undefined,
    session_id_len: u8 = 0,
    capabilities: Capabilities = .{},
    controller_name: [64]u8 = undefined,
    controller_name_len: u8,
    connected: bool = false,
    last_received_ms: i64 = 0,
    last_ping_ms: i64 = 0,
    repeat_counts: [key_names.len]u32 = @splat(0),
    media_state: [32]u8 = undefined,
    media_state_len: u8 = 0,

    pub fn init(allocator: std.mem.Allocator, io: std.Io, credentials: credential_store.Adapter, name: []const u8) !Client {
        if (name.len == 0 or name.len > 64 or !std.unicode.utf8ValidateSlice(name)) return error.InvalidControllerName;
        var result = Client{
            .allocator = allocator,
            .io = io,
            .credentials = credentials,
            .tls = try tls_transport.Client.init(),
            .controller_name_len = @intCast(name.len),
        };
        @memcpy(result.controller_name[0..name.len], name);
        return result;
    }

    pub fn deinit(self: *Client) void {
        self.tls.deinit();
        std.crypto.secureZero(u8, &self.session_id);
        std.crypto.secureZero(u8, &self.controller_name);
        self.* = undefined;
    }

    /// Safe to call from the lifecycle thread while the worker owns the TLS
    /// session. It only issues socket shutdown; the worker performs cleanup.
    pub fn cancel(self: *Client) void {
        self.tls.cancel();
    }

    pub fn prepareNetwork(self: *Client) void {
        self.tls.prepare();
    }

    pub fn setTarget(self: *Client, value: []const u8) !void {
        if (self.connected or self.pairing != null) return error.InvalidState;
        self.target = try parseTarget(value);
    }

    pub fn hasTarget(self: *const Client) bool {
        return self.target != null;
    }

    pub fn beginPair(self: *Client, request_tag: u64, code: []const u8) !SasDetails {
        if (self.connected or self.pairing != null) return error.InvalidState;
        const target = self.target orelse return error.TargetRequired;
        self.resetConnection();
        errdefer self.resetConnection();
        try self.tls.connectForPairing(.{
            .host = target.hostSlice(),
            .port = target.address.getPort(),
            .read_timeout_ms = connection_read_timeout_ms,
        });

        var controller_nonce: [security.nonce_length]u8 = undefined;
        defer std.crypto.secureZero(u8, &controller_nonce);
        try self.io.randomSecure(&controller_nonce);
        const controller_nonce_hex = std.fmt.bytesToHex(controller_nonce, .lower);
        var request_buffer: [64]u8 = undefined;
        const request_id = try formatRequestId(&request_buffer, request_tag, null);
        _ = try self.send(request_id, "", "pair_request", .{
            .code = code,
            .controllerName = self.controllerName(),
            .controllerNonce = &controller_nonce_hex,
        });

        var response = try self.readUntil(nowMs(self.io) + pre_auth_deadline_ms);
        defer response.deinit();
        if (std.mem.eql(u8, response.message_type, "error")) {
            try self.expectEnvelope(&response, request_id, "", "error");
            try validateErrorMessage(&response);
            return error.PairingRejected;
        }
        try self.expectEnvelope(&response, request_id, "", "pairing_sas");
        try response.requirePayloadFields(&.{ "pairingId", "sas", "tvNonce", "expiresInMs" }, &.{});
        const pairing_id = try response.requireString("pairingId", 32);
        if (!validLowerHex(pairing_id, 32)) return error.ProtocolViolation;
        const remote_sas = try response.requireString("sas", security.pairing_code_length);
        if (!sixDigits(remote_sas)) return error.ProtocolViolation;
        const tv_nonce_text = try response.requireString("tvNonce", security.nonce_length * 2);
        var tv_nonce: [security.nonce_length]u8 = undefined;
        defer std.crypto.secureZero(u8, &tv_nonce);
        try decodeLowerHex(&tv_nonce, tv_nonce_text);
        const expires_in_ms = try response.requireInteger("expiresInMs", 1, 120_000);
        const expected_sas = try security.pairingSas(code, .{
            .certificate_fingerprint = &self.tls.peer_fingerprint,
            .tv_nonce = &tv_nonce,
            .controller_nonce = &controller_nonce,
            .controller_name = self.controllerName(),
        });
        if (!std.crypto.timing_safe.eql([security.pairing_code_length]u8, expected_sas, remote_sas[0..security.pairing_code_length].*)) {
            return error.PairingSasMismatch;
        }
        const fingerprint = self.tls.peer_fingerprint;
        try self.tls.promoteAfterSas(true, &fingerprint);

        var details = SasDetails{
            .sas = expected_sas,
            .pairing_id = undefined,
            .expires_in_ms = expires_in_ms,
        };
        @memcpy(&details.pairing_id, pairing_id);
        self.pairing = .{
            .pairing_id = details.pairing_id,
            .deadline_ms = nowMs(self.io) + expires_in_ms + 5_000,
        };
        return details;
    }

    pub fn finishPair(self: *Client, request_tag: u64) !void {
        _ = self.target orelse return error.TargetRequired;
        const pairing = self.pairing orelse return error.InvalidState;
        defer self.resetConnection();

        var credential_message = try self.readUntil(pairing.deadline_ms);
        defer credential_message.deinit();
        if (std.mem.eql(u8, credential_message.message_type, "pair_rejected")) {
            try self.expectEnvelopeType(&credential_message, "", "pair_rejected");
            try credential_message.requirePayloadFields(&.{"reason"}, &.{});
            _ = try credential_message.requireString("reason", 256);
            return error.PairingRejected;
        }
        if (std.mem.eql(u8, credential_message.message_type, "error")) {
            try self.expectEnvelopeType(&credential_message, "", "error");
            try validateErrorMessage(&credential_message);
            return error.RemoteError;
        }
        try self.expectEnvelopeType(&credential_message, "", "pair_credential");
        try credential_message.requirePayloadFields(&.{ "pairingId", "controllerId", "secret" }, &.{});
        const pairing_id = try credential_message.requireString("pairingId", 32);
        if (!std.mem.eql(u8, pairing_id, &pairing.pairing_id)) return error.ProtocolViolation;
        const controller_id = try credential_message.requireString("controllerId", 32);
        if (!validLowerHex(controller_id, 32)) return error.ProtocolViolation;
        const secret_text = try credential_message.requireString("secret", security.pairing_secret_length * 2);
        var credential = StoredCredential{
            .fingerprint = self.tls.peer_fingerprint,
            .controller_id = @splat(0),
            .controller_id_len = @intCast(controller_id.len),
            .secret = undefined,
        };
        defer std.crypto.secureZero(u8, std.mem.asBytes(&credential));
        @memcpy(credential.controller_id[0..controller_id.len], controller_id);
        try decodeLowerHex(&credential.secret, secret_text);
        var encoded_credential: [credential_record_length]u8 = undefined;
        defer std.crypto.secureZero(u8, &encoded_credential);
        encodeCredential(&credential, &encoded_credential);
        var previous_credential: [credential_record_length]u8 = undefined;
        defer std.crypto.secureZero(u8, &previous_credential);
        const stable_credential_id = fingerprintCredentialId(&credential.fingerprint);
        const previous_len = self.credentials.get(&stable_credential_id, &previous_credential) catch |err| switch (err) {
            error.CredentialNotFound => 0,
            else => return err,
        };
        if (previous_len != 0 and previous_len != credential_record_length) return error.InvalidCredentialData;
        try self.credentials.put(&stable_credential_id, &encoded_credential);

        var acknowledgement_id_buffer: [80]u8 = undefined;
        const acknowledgement_id = try formatRequestId(&acknowledgement_id_buffer, request_tag, "store");
        _ = self.send(acknowledgement_id, "", "pair_store_ack", .{
            .pairingId = &pairing.pairing_id,
            .controllerId = controller_id,
        }) catch |err| {
            // 回滚路径：send 失败时恢复旧凭据或删除新凭据。
            // resetConnection 由函数入口的 defer 保证执行（释放配对会话与连接）。
            if (previous_len == credential_record_length) {
                self.credentials.put(&stable_credential_id, &previous_credential) catch |rollback_err| {
                    std.log.warn("credential rollback restore failed: {s} (original error: {s})", .{ @errorName(rollback_err), @errorName(err) });
                    return error.CredentialRollbackFailed;
                };
            } else {
                self.credentials.remove(&stable_credential_id) catch |rollback_err| {
                    std.log.warn("credential rollback remove failed: {s} (original error: {s})", .{ @errorName(rollback_err), @errorName(err) });
                    return error.CredentialRollbackFailed;
                };
            }
            std.log.warn("pair_store_ack send failed: {s}; pairing credential rolled back", .{@errorName(err)});
            return err;
        };

        var complete = try self.readUntil(pairing.deadline_ms);
        defer complete.deinit();
        try self.expectEnvelope(&complete, acknowledgement_id, "", "pair_complete");
        try complete.requirePayloadFields(&.{"controllerId"}, &.{});
        const completed_id = try complete.requireString("controllerId", 32);
        if (!std.mem.eql(u8, completed_id, controller_id)) return error.ProtocolViolation;
    }

    pub fn connect(self: *Client, request_tag: u64) !CredentialMigrationOutcome {
        if (self.connected or self.pairing != null) return error.InvalidState;
        const target = self.target orelse return error.TargetRequired;
        self.resetConnection();
        errdefer self.resetConnection();

        try self.tls.connectForPairing(.{
            .host = target.hostSlice(),
            .port = target.address.getPort(),
            .read_timeout_ms = connection_read_timeout_ms,
        });
        const stable_credential_id = fingerprintCredentialId(&self.tls.peer_fingerprint);
        var encoded_credential: [credential_record_length]u8 = undefined;
        defer std.crypto.secureZero(u8, &encoded_credential);
        var used_legacy_credential = false;
        const credential_len = self.credentials.get(&stable_credential_id, &encoded_credential) catch |err| switch (err) {
            error.CredentialNotFound => blk: {
                used_legacy_credential = true;
                break :blk try self.credentials.get(&target.credential_id, &encoded_credential);
            },
            else => return err,
        };
        if (credential_len != credential_record_length) return error.InvalidCredentialData;
        var credential = try decodeCredential(&encoded_credential);
        defer std.crypto.secureZero(u8, std.mem.asBytes(&credential));
        try self.tls.verifyPeerPin(&credential.fingerprint);

        var client_nonce: [security.nonce_length]u8 = undefined;
        defer std.crypto.secureZero(u8, &client_nonce);
        try self.io.randomSecure(&client_nonce);
        const client_nonce_hex = std.fmt.bytesToHex(client_nonce, .lower);
        var begin_id_buffer: [64]u8 = undefined;
        const begin_id = try formatRequestId(&begin_id_buffer, request_tag, null);
        _ = try self.send(begin_id, "", "auth_begin", .{
            .controllerId = credential.controllerId(),
            .clientNonce = &client_nonce_hex,
        });

        var challenge_message = try self.readUntil(nowMs(self.io) + pre_auth_deadline_ms);
        defer challenge_message.deinit();
        try self.expectEnvelope(&challenge_message, begin_id, "", "auth_challenge");
        try challenge_message.requirePayloadFields(&.{ "challengeId", "serverNonce", "expiresInMs" }, &.{});
        const challenge_id = try challenge_message.requireString("challengeId", 32);
        if (!validLowerHex(challenge_id, 32)) return error.ProtocolViolation;
        const server_nonce_text = try challenge_message.requireString("serverNonce", security.nonce_length * 2);
        var server_nonce: [security.nonce_length]u8 = undefined;
        defer std.crypto.secureZero(u8, &server_nonce);
        try decodeLowerHex(&server_nonce, server_nonce_text);
        _ = try challenge_message.requireInteger("expiresInMs", 1, 120_000);
        const response_mac = try security.authMac(&credential.secret, .{
            .certificate_fingerprint = &credential.fingerprint,
            .controller_id = credential.controllerId(),
            .challenge_id = challenge_id,
            .client_nonce = &client_nonce,
            .server_nonce = &server_nonce,
        });
        const response_hex = std.fmt.bytesToHex(response_mac, .lower);
        const server_nonce_hex = std.fmt.bytesToHex(server_nonce, .lower);
        var response_id_buffer: [80]u8 = undefined;
        const response_id = try formatRequestId(&response_id_buffer, request_tag, "auth");
        _ = try self.send(response_id, "", "auth_response", .{
            .controllerId = credential.controllerId(),
            .challengeId = challenge_id,
            .clientNonce = &client_nonce_hex,
            .serverNonce = &server_nonce_hex,
            .response = &response_hex,
        });

        var complete = try self.readUntil(nowMs(self.io) + pre_auth_deadline_ms);
        defer complete.deinit();
        if (std.mem.eql(u8, complete.message_type, "error")) {
            try self.expectEnvelope(&complete, response_id, "", "error");
            try validateErrorMessage(&complete);
            return error.AuthenticationFailed;
        }
        try self.expectEnvelopeType(&complete, complete.session_id, "auth_complete");
        if (!std.mem.eql(u8, complete.request_id, response_id) or complete.session_id.len == 0) return error.ProtocolViolation;
        try complete.requirePayloadFields(&.{ "sessionId", "expiresInMs", "capabilities" }, &.{});
        const payload_session_id = try complete.requireString("sessionId", control_protocol.max_identifier_length);
        if (!std.mem.eql(u8, payload_session_id, complete.session_id)) return error.ProtocolViolation;
        _ = try complete.requireInteger("expiresInMs", 1, std.math.maxInt(i64));
        const capabilities_object = try complete.requireObject("capabilities");
        self.capabilities = try Capabilities.parse(capabilities_object);
        @memcpy(self.session_id[0..complete.session_id.len], complete.session_id);
        self.session_id_len = @intCast(complete.session_id.len);
        self.connected = true;
        self.last_received_ms = nowMs(self.io);
        self.last_ping_ms = 0;
        if (used_legacy_credential) {
            // 凭据迁移：先写入基于证书指纹的新凭据，成功后才移除基于
            // IP 地址的旧凭据。稳定 ID 写入失败会使 connect 返回错误；旧 ID
            // 清理失败只返回 cleanup_pending，连接保持可用且下次继续重试。
            try self.credentials.put(&stable_credential_id, &encoded_credential);
        }

        return cleanupLegacyCredential(
            self.credentials,
            &stable_credential_id,
            &target.credential_id,
            used_legacy_credential,
        );
    }

    pub fn poll(self: *Client) !PollResult {
        if (!self.connected) return error.InvalidState;
        var message = self.readMessage() catch |err| switch (err) {
            error.Timeout => {
                const now = nowMs(self.io);
                if (now - self.last_received_ms >= transport.heartbeat_timeout_ms) return error.HeartbeatTimeout;
                if (self.last_ping_ms == 0 or now - self.last_ping_ms >= transport.heartbeat_interval_ms) {
                    var id_buffer: [80]u8 = undefined;
                    const request_id = try formatSequenceRequestId(&id_buffer, "heartbeat", self.outbound_sequence + 1);
                    _ = try self.send(request_id, self.sessionId(), "ping", struct {}{});
                    self.last_ping_ms = now;
                }
                return .no_change;
            },
            else => return err,
        };
        defer message.deinit();
        try self.validateControlEnvelope(&message);
        self.last_received_ms = nowMs(self.io);
        if (std.mem.eql(u8, message.message_type, "ping")) {
            try message.requirePayloadFields(&.{}, &.{});
            _ = try self.send(message.request_id, self.sessionId(), "pong", struct {}{});
            return .no_change;
        }
        if (std.mem.eql(u8, message.message_type, "pong")) {
            try message.requirePayloadFields(&.{}, &.{});
            return .no_change;
        }
        if (std.mem.eql(u8, message.message_type, "capabilities")) {
            self.capabilities = try Capabilities.parse(message.payload());
            return .capabilities_changed;
        }
        if (std.mem.eql(u8, message.message_type, "media_state")) {
            try self.captureMediaState(&message);
            return .media_state_changed;
        }
        if (std.mem.eql(u8, message.message_type, "error")) {
            try validateErrorMessage(&message);
            return error.RemoteError;
        }
        return error.ProtocolViolation;
    }

    pub fn sendKey(self: *Client, request_tag: u64, key: Key, key_state: KeyState) !Ack {
        if (!self.connected or !self.capabilities.enabled(key)) return error.UnsupportedKey;
        const key_index = @intFromEnum(key);
        const repeat_count = switch (key_state) {
            .down, .press => 0,
            .repeat => blk: {
                if (self.repeat_counts[key_index] == std.math.maxInt(u32)) return error.RepeatCountExhausted;
                self.repeat_counts[key_index] += 1;
                break :blk self.repeat_counts[key_index];
            },
            .up => self.repeat_counts[key_index],
        };
        if (key_state == .down) self.repeat_counts[key_index] = 0;
        errdefer if (key_state == .down or key_state == .repeat) {
            self.repeat_counts[key_index] = 0;
        };
        var request_id_buffer: [64]u8 = undefined;
        const request_id = try formatRequestId(&request_id_buffer, request_tag, null);
        const command_sequence = try self.send(request_id, self.sessionId(), "key_event", .{
            .key = key_names[@intFromEnum(key)],
            .state = key_state_names[@intFromEnum(key_state)],
            .repeatCount = repeat_count,
        });
        const deadline = nowMs(self.io) + command_deadline_ms;
        while (true) {
            var message = try self.readUntil(deadline);
            defer message.deinit();
            try self.validateControlEnvelope(&message);
            self.last_received_ms = nowMs(self.io);
            if (std.mem.eql(u8, message.message_type, "ping")) {
                try message.requirePayloadFields(&.{}, &.{});
                _ = try self.send(message.request_id, self.sessionId(), "pong", struct {}{});
                continue;
            }
            if (std.mem.eql(u8, message.message_type, "pong")) {
                try message.requirePayloadFields(&.{}, &.{});
                continue;
            }
            if (std.mem.eql(u8, message.message_type, "error")) {
                try validateErrorMessage(&message);
                return error.RemoteError;
            }
            if (!std.mem.eql(u8, message.message_type, "command_ack") or !std.mem.eql(u8, message.request_id, request_id)) {
                return error.ProtocolViolation;
            }
            try message.requirePayloadFields(&.{ "commandSequence", "status" }, &.{"reason"});
            const acknowledged_sequence = try message.requireInteger("commandSequence", 1, std.math.maxInt(i64));
            if (acknowledged_sequence != command_sequence) return error.ProtocolViolation;
            const status_text = try message.requireString("status", 32);
            var ack = Ack{
                .command_sequence = command_sequence,
                .status = parseAckStatus(status_text) orelse return error.ProtocolViolation,
            };
            if (message.payload().contains("reason")) {
                const reason_value = message.payload().get("reason").?;
                const reason = switch (reason_value) {
                    .string => |string| string,
                    else => return error.ProtocolViolation,
                };
                if (reason.len > ack.reason.len or !std.unicode.utf8ValidateSlice(reason)) return error.ProtocolViolation;
                @memcpy(ack.reason[0..reason.len], reason);
                ack.reason_len = @intCast(reason.len);
            }
            if (ack.status != .success and (key_state == .down or key_state == .repeat)) {
                self.repeat_counts[key_index] = 0;
            } else if (key_state == .up or key_state == .press) {
                self.repeat_counts[key_index] = 0;
            }
            return ack;
        }
    }

    pub fn startMedia(self: *Client, request_tag: u64) !MediaOffer {
        if (!self.connected or self.capabilities.media_transport == .unsupported or
            self.capabilities.media_transport == .unverified) return error.MediaUnsupported;
        var request_id_buffer: [64]u8 = undefined;
        const request_id = try formatRequestId(&request_id_buffer, request_tag, "media");
        _ = try self.send(request_id, self.sessionId(), "media_start", struct {}{});
        const deadline = nowMs(self.io) + 15_000;
        while (true) {
            var message = try self.readUntil(deadline);
            defer message.deinit();
            try self.validateControlEnvelope(&message);
            self.last_received_ms = nowMs(self.io);
            if (try self.handleInterleaved(&message)) continue;
            if (std.mem.eql(u8, message.message_type, "error")) {
                try validateErrorMessage(&message);
                return error.RemoteError;
            }
            if (!std.mem.eql(u8, message.message_type, "media_offer") or
                !std.mem.eql(u8, message.request_id, request_id)) return error.ProtocolViolation;
            try message.requirePayloadFields(&.{ "token", "expiresInMs" }, &.{});
            const token = try message.requireString("token", 64);
            if (!validLowerHex(token, 64)) return error.ProtocolViolation;
            var offer = MediaOffer{ .token = undefined, .expires_in_ms = try message.requireInteger("expiresInMs", 1, 30_000) };
            @memcpy(&offer.token, token);
            return offer;
        }
    }

    pub fn stopMedia(self: *Client, request_tag: u64) !void {
        if (!self.connected) return error.InvalidState;
        var request_id_buffer: [64]u8 = undefined;
        const request_id = try formatRequestId(&request_id_buffer, request_tag, "media-stop");
        _ = try self.send(request_id, self.sessionId(), "media_stop", struct {}{});
        const deadline = nowMs(self.io) + 10_000;
        while (true) {
            var message = try self.readUntil(deadline);
            defer message.deinit();
            try self.validateControlEnvelope(&message);
            self.last_received_ms = nowMs(self.io);
            if (try self.handleInterleaved(&message)) continue;
            if (std.mem.eql(u8, message.message_type, "error")) {
                try validateErrorMessage(&message);
                return error.RemoteError;
            }
            if (!std.mem.eql(u8, message.message_type, "media_stop_ack") or
                !std.mem.eql(u8, message.request_id, request_id)) return error.ProtocolViolation;
            try message.requirePayloadFields(&.{}, &.{});
            return;
        }
    }

    pub fn mediaConnectionInfo(self: *const Client) !MediaConnectionInfo {
        if (!self.connected) return error.InvalidState;
        const target = self.target orelse return error.TargetRequired;
        var result = MediaConnectionInfo{
            .host_len = target.host_len,
            .port = target.address.getPort(),
            .fingerprint = self.tls.peer_fingerprint,
            .session_id_len = self.session_id_len,
        };
        @memcpy(result.host[0..target.host_len], target.hostSlice());
        @memcpy(result.session_id[0..self.session_id_len], self.sessionId());
        return result;
    }

    pub fn mediaState(self: *const Client) []const u8 {
        return self.media_state[0..self.media_state_len];
    }

    pub fn disconnect(self: *Client, request_tag: u64) !void {
        if (!self.connected) return error.InvalidState;
        defer self.resetConnection();
        var request_id_buffer: [64]u8 = undefined;
        const request_id = try formatRequestId(&request_id_buffer, request_tag, null);
        _ = try self.send(request_id, self.sessionId(), "disconnect", struct {}{});
        const deadline = nowMs(self.io) + disconnect_deadline_ms;
        while (true) {
            var message = try self.readUntil(deadline);
            defer message.deinit();
            try self.validateControlEnvelope(&message);
            if (std.mem.eql(u8, message.message_type, "ping")) {
                try message.requirePayloadFields(&.{}, &.{});
                _ = try self.send(message.request_id, self.sessionId(), "pong", struct {}{});
                continue;
            }
            if (std.mem.eql(u8, message.message_type, "disconnect_ack") and std.mem.eql(u8, message.request_id, request_id)) {
                try message.requirePayloadFields(&.{}, &.{});
                return;
            }
            return error.ProtocolViolation;
        }
    }

    pub fn dropConnection(self: *Client) void {
        self.resetConnection();
    }

    pub fn sessionId(self: *const Client) []const u8 {
        return self.session_id[0..self.session_id_len];
    }

    fn controllerName(self: *const Client) []const u8 {
        return self.controller_name[0..self.controller_name_len];
    }

    fn resetConnection(self: *Client) void {
        self.tls.close();
        self.decoder.reset();
        self.pairing = null;
        self.inbound_sequence = 0;
        self.outbound_sequence = 0;
        std.crypto.secureZero(u8, &self.session_id);
        self.session_id_len = 0;
        self.connected = false;
        self.last_received_ms = 0;
        self.last_ping_ms = 0;
        self.repeat_counts = @splat(0);
        self.media_state_len = 0;
    }

    fn send(self: *Client, request_id: []const u8, session_id: []const u8, message_type: []const u8, payload: anytype) !u64 {
        if (self.outbound_sequence >= std.math.maxInt(i64)) return error.SequenceExhausted;
        const sequence = self.outbound_sequence + 1;
        var envelope_buffer: [transport.max_frame_size]u8 = undefined;
        const envelope = try control_protocol.encodeEnvelope(
            &envelope_buffer,
            request_id,
            session_id,
            sequence,
            message_type,
            payload,
        );
        var frame_buffer: [transport.max_frame_size + transport.frame_header_size]u8 = undefined;
        const frame = try transport.encodeFrame(envelope, &frame_buffer);
        try control_channel.forTls(&self.tls, self.tls.state == .encrypted_unverified).write(frame);
        self.outbound_sequence = sequence;
        return sequence;
    }

    fn readMessage(self: *Client) !control_protocol.Decoded {
        while (try self.decoder.peek() == null) {
            var buffer: [4096]u8 = undefined;
            const count = try control_channel.forTls(
                &self.tls,
                self.tls.state == .encrypted_unverified,
            ).read(&buffer);
            try self.decoder.append(buffer[0..count]);
        }
        const payload = (try self.decoder.peek()).?;
        var decoded = try control_protocol.decode(self.allocator, payload);
        errdefer decoded.deinit();
        try self.decoder.consume();
        if (decoded.sequence <= self.inbound_sequence) return error.ReplayedSequence;
        self.inbound_sequence = decoded.sequence;
        return decoded;
    }

    fn readUntil(self: *Client, deadline_ms: i64) !control_protocol.Decoded {
        while (true) {
            return self.readMessage() catch |err| switch (err) {
                error.Timeout => if (nowMs(self.io) >= deadline_ms) error.Timeout else continue,
                else => return err,
            };
        }
    }

    fn expectEnvelope(self: *Client, message: *const control_protocol.Decoded, request_id: []const u8, session_id: []const u8, message_type: []const u8) !void {
        try self.expectEnvelopeType(message, session_id, message_type);
        if (!std.mem.eql(u8, message.request_id, request_id)) return error.ProtocolViolation;
    }

    fn expectEnvelopeType(_: *Client, message: *const control_protocol.Decoded, session_id: []const u8, message_type: []const u8) !void {
        if (!std.mem.eql(u8, message.session_id, session_id) or !std.mem.eql(u8, message.message_type, message_type)) {
            return error.ProtocolViolation;
        }
    }

    fn validateControlEnvelope(self: *Client, message: *const control_protocol.Decoded) !void {
        if (!std.mem.eql(u8, message.session_id, self.sessionId())) return error.InvalidSession;
    }

    fn handleInterleaved(self: *Client, message: *const control_protocol.Decoded) !bool {
        if (std.mem.eql(u8, message.message_type, "ping")) {
            try message.requirePayloadFields(&.{}, &.{});
            _ = try self.send(message.request_id, self.sessionId(), "pong", struct {}{});
            return true;
        }
        if (std.mem.eql(u8, message.message_type, "pong")) {
            try message.requirePayloadFields(&.{}, &.{});
            return true;
        }
        if (std.mem.eql(u8, message.message_type, "media_state")) {
            try self.captureMediaState(message);
            return true;
        }
        return false;
    }

    fn captureMediaState(self: *Client, message: *const control_protocol.Decoded) !void {
        try message.requirePayloadFields(&.{"state"}, &.{});
        const state = try message.requireString("state", self.media_state.len);
        const valid = std.mem.eql(u8, state, "waiting_tv_authorization") or
            std.mem.eql(u8, state, "streaming") or std.mem.eql(u8, state, "video_only") or
            std.mem.eql(u8, state, "stopped") or std.mem.eql(u8, state, "failed") or
            std.mem.eql(u8, state, "unsupported");
        if (!valid) return error.ProtocolViolation;
        @memcpy(self.media_state[0..state.len], state);
        self.media_state_len = @intCast(state.len);
    }
};

fn cleanupLegacyCredential(
    credentials: credential_store.Adapter,
    stable_id: []const u8,
    legacy_id: []const u8,
    legacy_loaded: bool,
) CredentialMigrationOutcome {
    if (std.mem.eql(u8, stable_id, legacy_id)) return if (legacy_loaded) .completed else .not_needed;
    var legacy_probe: [credential_record_length]u8 = undefined;
    defer std.crypto.secureZero(u8, &legacy_probe);
    const legacy_present = if (legacy_loaded)
        true
    else blk: {
        const legacy_len = credentials.get(legacy_id, &legacy_probe) catch |err| switch (err) {
            error.CredentialNotFound => break :blk false,
            else => {
                std.log.warn("legacy credential cleanup probe failed: {s}", .{@errorName(err)});
                return .cleanup_pending;
            },
        };
        if (legacy_len != credential_record_length) {
            std.log.warn("legacy credential cleanup probe returned invalid record length", .{});
            return .cleanup_pending;
        }
        break :blk true;
    };
    if (!legacy_present) return .not_needed;
    credentials.remove(legacy_id) catch |err| {
        std.log.warn("legacy credential cleanup failed: {s}", .{@errorName(err)});
        return .cleanup_pending;
    };
    return .completed;
}

fn parseTarget(value: []const u8) !Target {
    if (value.len == 0 or value.len > 270 or !std.unicode.utf8ValidateSlice(value)) return error.InvalidTarget;
    var address = std.Io.net.IpAddress.parseLiteral(value) catch return error.InvalidTarget;
    const supplied_port = address.getPort();
    if (supplied_port != 0 and supplied_port != transport.control_port) return error.InvalidTarget;
    address.setPort(transport.control_port);
    const host_text = switch (address) {
        .ip4 => if (std.mem.lastIndexOfScalar(u8, value, ':')) |separator| value[0..separator] else value,
        .ip6 => blk: {
            const end = std.mem.indexOfScalar(u8, value, ']') orelse return error.InvalidTarget;
            break :blk value[1..end];
        },
    };
    if (host_text.len == 0 or host_text.len > 255 or std.mem.indexOfScalar(u8, host_text, 0) != null) return error.InvalidTarget;
    var result = Target{
        .address = address,
        .host_len = @intCast(host_text.len),
        .credential_id = credentialId(address),
    };
    @memcpy(result.host[0..host_text.len], host_text);
    return result;
}

fn credentialId(address: std.Io.net.IpAddress) [32]u8 {
    var hash = std.crypto.hash.sha2.Sha256.init(.{});
    hash.update("TVRC-TARGET-v1");
    switch (address) {
        .ip4 => |ip4| {
            hash.update(&.{4});
            hash.update(&ip4.bytes);
        },
        .ip6 => |ip6| {
            hash.update(&.{6});
            hash.update(&ip6.bytes);
        },
    }
    var port: [2]u8 = undefined;
    std.mem.writeInt(u16, &port, address.getPort(), .big);
    hash.update(&port);
    var result: [32]u8 = undefined;
    hash.final(&result);
    return result;
}

fn fingerprintCredentialId(fingerprint: *const [security.fingerprint_length]u8) [32]u8 {
    var hash = std.crypto.hash.sha2.Sha256.init(.{});
    hash.update("TVRC-CERTIFICATE-v1");
    hash.update(fingerprint);
    var result: [32]u8 = undefined;
    hash.final(&result);
    return result;
}

fn encodeCredential(credential: *const StoredCredential, output: *[credential_record_length]u8) void {
    output.* = @splat(0);
    var offset: usize = 0;
    @memcpy(output[offset..][0..credential_magic.len], credential_magic);
    offset += credential_magic.len;
    output[offset] = credential_version;
    offset += 1;
    @memcpy(output[offset..][0..credential.fingerprint.len], &credential.fingerprint);
    offset += credential.fingerprint.len;
    output[offset] = credential.controller_id_len;
    offset += 1;
    @memcpy(output[offset..][0..credential.controller_id.len], &credential.controller_id);
    offset += credential.controller_id.len;
    @memcpy(output[offset..][0..credential.secret.len], &credential.secret);
}

fn decodeCredential(input: *const [credential_record_length]u8) !StoredCredential {
    var offset: usize = 0;
    if (!std.mem.eql(u8, input[offset..][0..credential_magic.len], credential_magic)) return error.InvalidCredentialData;
    offset += credential_magic.len;
    if (input[offset] != credential_version) return error.InvalidCredentialData;
    offset += 1;
    var result = StoredCredential{
        .fingerprint = undefined,
        .controller_id = undefined,
        .controller_id_len = 0,
        .secret = undefined,
    };
    @memcpy(&result.fingerprint, input[offset..][0..result.fingerprint.len]);
    offset += result.fingerprint.len;
    result.controller_id_len = input[offset];
    offset += 1;
    if (result.controller_id_len == 0 or result.controller_id_len > result.controller_id.len) return error.InvalidCredentialData;
    @memcpy(&result.controller_id, input[offset..][0..result.controller_id.len]);
    offset += result.controller_id.len;
    if (!validLowerHex(result.controllerId(), 32)) return error.InvalidCredentialData;
    for (result.controller_id[result.controller_id_len..]) |byte| if (byte != 0) return error.InvalidCredentialData;
    @memcpy(&result.secret, input[offset..][0..result.secret.len]);
    return result;
}

fn validateErrorMessage(message: *const control_protocol.Decoded) !void {
    try message.requirePayloadFields(&.{ "code", "message" }, &.{});
    _ = try message.requireString("code", 64);
    _ = try message.requireString("message", 256);
}

fn parseCapability(value: []const u8) ?KeyCapability {
    if (std.mem.eql(u8, value, "SUPPORTED")) return .supported;
    if (std.mem.eql(u8, value, "BEST_EFFORT")) return .best_effort;
    if (std.mem.eql(u8, value, "PERMISSION_REQUIRED")) return .permission_required;
    if (std.mem.eql(u8, value, "UNSUPPORTED")) return .unsupported;
    if (std.mem.eql(u8, value, "UNVERIFIED")) return .unverified;
    return null;
}

fn parseAckStatus(value: []const u8) ?AckStatus {
    if (std.mem.eql(u8, value, "SUCCESS")) return .success;
    if (std.mem.eql(u8, value, "UNSUPPORTED")) return .unsupported;
    if (std.mem.eql(u8, value, "PERMISSION_DENIED")) return .permission_denied;
    if (std.mem.eql(u8, value, "MAPPING_MISSING")) return .mapping_missing;
    if (std.mem.eql(u8, value, "REJECTED")) return .rejected;
    if (std.mem.eql(u8, value, "EXECUTION_FAILED")) return .execution_failed;
    return null;
}

fn formatRequestId(output: []u8, request_tag: u64, suffix: ?[]const u8) ![]const u8 {
    return if (suffix) |value|
        std.fmt.bufPrint(output, "client-{d}-{s}", .{ request_tag, value }) catch error.InvalidRequestId
    else
        std.fmt.bufPrint(output, "client-{d}", .{request_tag}) catch error.InvalidRequestId;
}

fn formatSequenceRequestId(output: []u8, prefix: []const u8, sequence: u64) ![]const u8 {
    return std.fmt.bufPrint(output, "client-{s}-{d}", .{ prefix, sequence }) catch error.InvalidRequestId;
}

fn decodeLowerHex(output: []u8, input: []const u8) !void {
    if (!validLowerHex(input, output.len * 2)) return error.ProtocolViolation;
    _ = std.fmt.hexToBytes(output, input) catch return error.ProtocolViolation;
}

fn validLowerHex(value: []const u8, expected_len: usize) bool {
    if (value.len != expected_len) return false;
    for (value) |byte| switch (byte) {
        '0'...'9', 'a'...'f' => {},
        else => return false,
    };
    return true;
}

fn sixDigits(value: []const u8) bool {
    if (value.len != security.pairing_code_length) return false;
    for (value) |byte| if (!std.ascii.isDigit(byte)) return false;
    return true;
}

fn nowMs(io: std.Io) i64 {
    return std.Io.Clock.awake.now(io).toMilliseconds();
}

test "target credential identity is canonical and port locked" {
    const first = try parseTarget("127.0.0.1");
    const second = try parseTarget("127.0.0.1:47832");
    try std.testing.expectEqualSlices(u8, &first.credential_id, &second.credential_id);
    try std.testing.expectEqualStrings("127.0.0.1", first.hostSlice());
    try std.testing.expectError(error.InvalidTarget, parseTarget("127.0.0.1:22"));
    try std.testing.expectError(error.InvalidTarget, parseTarget("example.com"));
}

test "certificate fingerprint is the stable credential identity" {
    const first = [_]u8{0x11} ** security.fingerprint_length;
    const second = [_]u8{0x22} ** security.fingerprint_length;
    try std.testing.expectEqualSlices(u8, &fingerprintCredentialId(&first), &fingerprintCredentialId(&first));
    try std.testing.expect(!std.mem.eql(u8, &fingerprintCredentialId(&first), &fingerprintCredentialId(&second)));
}

test "credential record is fixed width strict and round trips" {
    var credential = StoredCredential{
        .fingerprint = @splat(0x11),
        .controller_id = @splat(0),
        .controller_id_len = 32,
        .secret = @splat(0x22),
    };
    @memcpy(&credential.controller_id, "0123456789abcdef0123456789abcdef");
    var encoded: [credential_record_length]u8 = undefined;
    encodeCredential(&credential, &encoded);
    const decoded = try decodeCredential(&encoded);
    try std.testing.expectEqualSlices(u8, &credential.fingerprint, &decoded.fingerprint);
    try std.testing.expectEqualStrings(credential.controllerId(), decoded.controllerId());
    try std.testing.expectEqualSlices(u8, &credential.secret, &decoded.secret);
    encoded[0] ^= 1;
    try std.testing.expectError(error.InvalidCredentialData, decodeCredential(&encoded));
}

const CleanupTestStore = struct {
    record: [credential_record_length]u8 = @splat(0x5a),
    legacy_present: bool = true,
    removal_failures: u8 = 1,
    remove_calls: u8 = 0,
};

fn cleanupTestPut(_: ?*anyopaque, _: ?[*]const u8, _: u32, _: ?[*]const u8, _: u32) callconv(.c) i32 {
    return 0;
}

fn cleanupTestGet(
    context: ?*anyopaque,
    _: ?[*]const u8,
    _: u32,
    output: ?[*]u8,
    capacity: u32,
    output_len: ?*u32,
) callconv(.c) i32 {
    const store: *CleanupTestStore = @ptrCast(@alignCast(context.?));
    if (!store.legacy_present) return 6;
    output_len.?.* = credential_record_length;
    if (capacity < credential_record_length) return 4;
    @memcpy(output.?[0..credential_record_length], &store.record);
    return 0;
}

fn cleanupTestRemove(context: ?*anyopaque, _: ?[*]const u8, _: u32) callconv(.c) i32 {
    const store: *CleanupTestStore = @ptrCast(@alignCast(context.?));
    store.remove_calls += 1;
    if (store.removal_failures > 0) {
        store.removal_failures -= 1;
        return 5;
    }
    store.legacy_present = false;
    return 0;
}

test "legacy credential cleanup failure is visible and retried on the next connection" {
    var store = CleanupTestStore{};
    const credentials = credential_store.Adapter{
        .context = &store,
        .put_fn = cleanupTestPut,
        .get_fn = cleanupTestGet,
        .remove_fn = cleanupTestRemove,
    };
    const stable_id = [_]u8{0x11} ** 32;
    const legacy_id = [_]u8{0x22} ** 32;
    try std.testing.expectEqual(
        CredentialMigrationOutcome.cleanup_pending,
        cleanupLegacyCredential(credentials, &stable_id, &legacy_id, false),
    );
    try std.testing.expect(store.legacy_present);
    try std.testing.expectEqual(@as(u8, 1), store.remove_calls);
    try std.testing.expectEqual(
        CredentialMigrationOutcome.completed,
        cleanupLegacyCredential(credentials, &stable_id, &legacy_id, false),
    );
    try std.testing.expect(!store.legacy_present);
    try std.testing.expectEqual(@as(u8, 2), store.remove_calls);
    try std.testing.expectEqual(
        CredentialMigrationOutcome.not_needed,
        cleanupLegacyCredential(credentials, &stable_id, &legacy_id, false),
    );
}

test "capabilities enable only supported and best effort keys" {
    var json_buffer: [4096]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&json_buffer);
    try writer.writeAll("{\"protocolVersion\":1,\"requestId\":\"a\",\"sessionId\":\"s\",\"sequence\":1,\"type\":\"capabilities\",\"payload\":{\"keySupport\":{");
    for (key_names, 0..) |name, index| {
        if (index != 0) try writer.writeByte(',');
        try writer.print("\"{s}\":\"{s}\"", .{ name, if (index == 0) "SUPPORTED" else if (index == 1) "BEST_EFFORT" else "UNSUPPORTED" });
    }
    try writer.writeAll("}}}");
    var decoded = try control_protocol.decode(std.testing.allocator, writer.buffered());
    defer decoded.deinit();
    const capabilities = try Capabilities.parse(decoded.payload());
    try std.testing.expect(capabilities.enabled(.dpad_up));
    try std.testing.expect(capabilities.enabled(.dpad_down));
    try std.testing.expect(!capabilities.enabled(.home));
}
