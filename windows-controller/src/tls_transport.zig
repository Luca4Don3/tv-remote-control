const std = @import("std");

const c = @cImport({
    @cInclude("tls_client.h");
});

pub const fingerprint_length = 32;
pub const default_read_timeout_ms: u32 = 5_000;

pub const State = enum {
    idle,
    encrypted_unverified,
    authenticated,
    failed,
};

pub const Config = struct {
    host: []const u8,
    port: u16 = 47_832,
    read_timeout_ms: u32 = default_read_timeout_ms,

    pub fn validate(self: Config) !void {
        if (self.host.len == 0 or self.host.len > 255) return error.InvalidHost;
        if (std.mem.indexOfScalar(u8, self.host, 0) != null) return error.InvalidHost;
        if (self.port == 0) return error.InvalidPort;
        if (self.read_timeout_ms == 0 or self.read_timeout_ms > 60_000) return error.InvalidTimeout;
    }
};

/// Owns one mbedTLS connection. A connection without a pre-existing pin is
/// deliberately kept in `encrypted_unverified`; only pairing messages may be
/// exchanged until local SAS confirmation promotes it.
pub const Client = struct {
    raw: ?*c.tvrc_tls_client = null,
    state: State = .idle,
    last_backend_error: i32 = 0,
    peer_fingerprint: [fingerprint_length]u8 = @splat(0),

    pub fn init() !Client {
        return .{ .raw = c.tvrc_tls_client_create() orelse return error.OutOfMemory };
    }

    pub fn deinit(self: *Client) void {
        c.tvrc_tls_client_destroy(self.raw);
        self.* = .{};
    }

    /// Interrupts a blocking handshake or read from another thread. The owner
    /// must subsequently call `close` before reusing the client.
    pub fn cancel(self: *Client) void {
        c.tvrc_tls_client_cancel(self.raw);
    }

    pub fn prepare(self: *Client) void {
        c.tvrc_tls_client_prepare(self.raw);
    }

    pub fn close(self: *Client) void {
        c.tvrc_tls_client_close(self.raw);
        self.state = .idle;
        self.last_backend_error = 0;
        self.peer_fingerprint = @splat(0);
    }

    pub fn connectPinned(self: *Client, config: Config, expected_fingerprint: *const [fingerprint_length]u8) !void {
        try self.connectInternal(config, expected_fingerprint);
        self.state = .authenticated;
    }

    pub fn connectForPairing(self: *Client, config: Config) !void {
        try self.connectInternal(config, null);
        self.state = .encrypted_unverified;
    }

    /// Call only after the user confirms the six-digit SAS on both endpoints.
    pub fn promoteAfterSas(self: *Client, confirmed: bool, expected_fingerprint: *const [fingerprint_length]u8) !void {
        if (self.state != .encrypted_unverified) return error.InvalidState;
        if (!confirmed) return error.PairingNotConfirmed;
        try self.verifyPeerPin(expected_fingerprint);
    }

    /// Promotes a completed TLS handshake only after a locally stored pin
    /// matches. No application secret is sent before this check succeeds.
    pub fn verifyPeerPin(self: *Client, expected_fingerprint: *const [fingerprint_length]u8) !void {
        if (self.state != .encrypted_unverified) return error.InvalidState;
        if (!std.crypto.timing_safe.eql([fingerprint_length]u8, self.peer_fingerprint, expected_fingerprint.*)) {
            self.state = .failed;
            return error.CertificatePinMismatch;
        }
        self.state = .authenticated;
    }

    pub fn pairingRead(self: *Client, output: []u8) !usize {
        if (self.state != .encrypted_unverified) return error.InvalidState;
        return self.backendRead(output);
    }

    pub fn pairingWrite(self: *Client, bytes: []const u8) !void {
        if (self.state != .encrypted_unverified) return error.InvalidState;
        try self.backendWrite(bytes);
    }

    pub fn read(self: *Client, output: []u8) !usize {
        if (self.state != .authenticated) return error.UnauthenticatedSession;
        return self.backendRead(output);
    }

    pub fn write(self: *Client, bytes: []const u8) !void {
        if (self.state != .authenticated) return error.UnauthenticatedSession;
        try self.backendWrite(bytes);
    }

    fn connectInternal(self: *Client, config: Config, expected: ?*const [fingerprint_length]u8) !void {
        try config.validate();
        const raw = self.raw orelse return error.InvalidState;
        if (self.state != .idle and self.state != .failed) return error.InvalidState;
        var host_buffer: [256]u8 = undefined;
        @memcpy(host_buffer[0..config.host.len], config.host);
        host_buffer[config.host.len] = 0;
        var port_buffer: [6]u8 = undefined;
        const port = std.fmt.bufPrintZ(&port_buffer, "{d}", .{config.port}) catch return error.InvalidPort;
        const result = c.tvrc_tls_client_connect(
            raw,
            @ptrCast(&host_buffer),
            port.ptr,
            if (expected) |fingerprint| fingerprint else null,
            if (expected == null) 0 else fingerprint_length,
            config.read_timeout_ms,
        );
        if (result != 0) {
            self.last_backend_error = c.tvrc_tls_client_last_error(raw);
            self.state = .failed;
            return error.TlsHandshakeFailed;
        }
        if (c.tvrc_tls_client_peer_fingerprint(raw, &self.peer_fingerprint) != 0) {
            self.last_backend_error = c.tvrc_tls_client_last_error(raw);
            self.state = .failed;
            return error.MissingPeerCertificate;
        }
        self.last_backend_error = 0;
    }

    fn backendRead(self: *Client, output: []u8) !usize {
        // 在调用 C 层前拒绝空 buffer：tvrc_tls_client_read 收到
        // (null, 0) 组合在 mbedTLS 中行为未定义，必须在此拦截。
        if (output.len == 0 or output.len > std.math.maxInt(i32)) return error.InvalidBuffer;
        const raw = self.raw orelse return error.InvalidState;
        const result = c.tvrc_tls_client_read(raw, output.ptr, @intCast(output.len));
        if (result == c.TVRC_TLS_CLIENT_TIMEOUT) return error.Timeout;
        if (result == 0) {
            self.state = .failed;
            return error.ConnectionClosed;
        }
        if (result < 0) {
            self.last_backend_error = result;
            self.state = .failed;
            return error.TlsReadFailed;
        }
        return @intCast(result);
    }

    fn backendWrite(self: *Client, bytes: []const u8) !void {
        if (bytes.len > std.math.maxInt(i32)) return error.InvalidBuffer;
        const raw = self.raw orelse return error.InvalidState;
        const result = c.tvrc_tls_client_write(raw, if (bytes.len == 0) null else bytes.ptr, @intCast(bytes.len));
        if (result < 0 or result != bytes.len) {
            self.last_backend_error = result;
            self.state = .failed;
            return error.TlsWriteFailed;
        }
    }
};

test "TLS configuration rejects unsafe boundaries before networking" {
    try std.testing.expectError(error.InvalidHost, (Config{ .host = "" }).validate());
    try std.testing.expectError(error.InvalidHost, (Config{ .host = "bad\x00host" }).validate());
    try std.testing.expectError(error.InvalidPort, (Config{ .host = "127.0.0.1", .port = 0 }).validate());
    try std.testing.expectError(error.InvalidTimeout, (Config{ .host = "127.0.0.1", .read_timeout_ms = 0 }).validate());
}

test "mbedTLS client lifecycle is linked and provisional sessions cannot control" {
    var client = try Client.init();
    defer client.deinit();
    var output: [1]u8 = undefined;
    try std.testing.expectError(error.UnauthenticatedSession, client.read(&output));
    try std.testing.expectError(error.InvalidState, client.pairingRead(&output));
}
