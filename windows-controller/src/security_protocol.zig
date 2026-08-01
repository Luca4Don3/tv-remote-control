const std = @import("std");

pub const protocol_version: u32 = 1;
pub const fingerprint_length = 32;
pub const nonce_length = 32;
pub const pairing_secret_length = 32;
pub const mac_length = 32;
pub const pairing_code_length = 6;

pub const pairing_domain = "TVRC-PAIR-v1";
pub const auth_domain = "TVRC-AUTH-v1";

const HmacSha256 = std.crypto.auth.hmac.sha2.HmacSha256;

pub const PairingTranscript = struct {
    certificate_fingerprint: []const u8,
    tv_nonce: []const u8,
    controller_nonce: []const u8,
    controller_name: []const u8,
};

pub const AuthTranscript = struct {
    certificate_fingerprint: []const u8,
    controller_id: []const u8,
    challenge_id: []const u8,
    client_nonce: []const u8,
    server_nonce: []const u8,
};

pub fn pairingMac(code: []const u8, transcript: PairingTranscript) ![mac_length]u8 {
    try validatePairingCode(code);
    if (transcript.certificate_fingerprint.len != fingerprint_length) return error.InvalidFingerprintLength;
    if (transcript.tv_nonce.len != nonce_length or transcript.controller_nonce.len != nonce_length) return error.InvalidNonceLength;
    if (transcript.controller_name.len == 0 or transcript.controller_name.len > std.math.maxInt(u16)) return error.InvalidControllerName;
    if (!std.unicode.utf8ValidateSlice(transcript.controller_name)) return error.InvalidControllerName;

    var mac = HmacSha256.init(code);
    mac.update(pairing_domain);
    updateU32(&mac, protocol_version);
    mac.update(transcript.certificate_fingerprint);
    mac.update(transcript.tv_nonce);
    mac.update(transcript.controller_nonce);
    updateLengthPrefixed(&mac, transcript.controller_name) catch return error.InvalidControllerName;
    var result: [mac_length]u8 = undefined;
    mac.final(&result);
    return result;
}

pub fn pairingSas(code: []const u8, transcript: PairingTranscript) ![pairing_code_length]u8 {
    const mac = try pairingMac(code, transcript);
    var value = std.mem.readInt(u32, mac[0..4], .big) % 1_000_000;
    var result: [pairing_code_length]u8 = undefined;
    var index = result.len;
    while (index > 0) {
        index -= 1;
        result[index] = @intCast('0' + value % 10);
        value /= 10;
    }
    return result;
}

pub fn authMac(secret: []const u8, transcript: AuthTranscript) ![mac_length]u8 {
    if (secret.len != pairing_secret_length) return error.InvalidPairingSecretLength;
    if (transcript.certificate_fingerprint.len != fingerprint_length) return error.InvalidFingerprintLength;
    if (transcript.client_nonce.len != nonce_length or transcript.server_nonce.len != nonce_length) return error.InvalidNonceLength;
    if (transcript.controller_id.len == 0 or transcript.challenge_id.len == 0) return error.InvalidAuthenticationBinding;

    var mac = HmacSha256.init(secret);
    mac.update(auth_domain);
    updateLengthPrefixed(&mac, transcript.certificate_fingerprint) catch return error.InvalidAuthenticationBinding;
    updateLengthPrefixed(&mac, transcript.controller_id) catch return error.InvalidAuthenticationBinding;
    updateLengthPrefixed(&mac, transcript.challenge_id) catch return error.InvalidAuthenticationBinding;
    updateLengthPrefixed(&mac, transcript.client_nonce) catch return error.InvalidAuthenticationBinding;
    updateLengthPrefixed(&mac, transcript.server_nonce) catch return error.InvalidAuthenticationBinding;
    var result: [mac_length]u8 = undefined;
    mac.final(&result);
    return result;
}

pub fn authenticate(expected: *const [mac_length]u8, actual: *const [mac_length]u8) bool {
    return std.crypto.timing_safe.eql([mac_length]u8, expected.*, actual.*);
}

fn validatePairingCode(code: []const u8) !void {
    if (code.len != pairing_code_length) return error.InvalidPairingCode;
    for (code) |byte| if (!std.ascii.isDigit(byte)) return error.InvalidPairingCode;
}

fn updateU32(mac: *HmacSha256, value: u32) void {
    var encoded: [4]u8 = undefined;
    std.mem.writeInt(u32, &encoded, value, .big);
    mac.update(&encoded);
}

fn updateLengthPrefixed(mac: *HmacSha256, value: []const u8) !void {
    if (value.len > std.math.maxInt(u16)) return error.FieldTooLong;
    var encoded: [2]u8 = undefined;
    std.mem.writeInt(u16, &encoded, @intCast(value.len), .big);
    mac.update(&encoded);
    mac.update(value);
}

test "pairing transcript rejects ambiguous or malformed inputs" {
    const bytes: [fingerprint_length]u8 = @splat(1);
    try std.testing.expectError(error.InvalidPairingCode, pairingSas("12345x", .{
        .certificate_fingerprint = &bytes,
        .tv_nonce = &bytes,
        .controller_nonce = &bytes,
        .controller_name = "Windows Controller",
    }));
    try std.testing.expectError(error.InvalidControllerName, pairingSas("123456", .{
        .certificate_fingerprint = &bytes,
        .tv_nonce = &bytes,
        .controller_nonce = &bytes,
        .controller_name = "\xff",
    }));
}

test "authentication comparison is exact" {
    const expected: [mac_length]u8 = @splat(0x5a);
    var changed = expected;
    try std.testing.expect(authenticate(&expected, &expected));
    changed[31] ^= 1;
    try std.testing.expect(!authenticate(&expected, &changed));
}

test "pairing and authentication golden vectors match Android" {
    var fingerprint: [fingerprint_length]u8 = undefined;
    var tv_nonce: [nonce_length]u8 = undefined;
    var controller_nonce: [nonce_length]u8 = undefined;
    var secret: [pairing_secret_length]u8 = undefined;
    var client_nonce: [nonce_length]u8 = undefined;
    var server_nonce: [nonce_length]u8 = undefined;
    for (0..fingerprint.len) |index| {
        fingerprint[index] = @intCast(index);
        tv_nonce[index] = @intCast(0x20 + index);
        controller_nonce[index] = @intCast(0x40 + index);
        secret[index] = @intCast(0x80 + index);
        client_nonce[index] = @intCast(0xa0 + index);
        server_nonce[index] = @intCast(0xc0 + index);
    }

    var expected_pairing_mac: [mac_length]u8 = undefined;
    _ = try std.fmt.hexToBytes(&expected_pairing_mac, "d7930b6134a464b6bce1a8d23a6da2ab6c4cedb9811bd9562b36536f00dbe970");
    const pairing = PairingTranscript{
        .certificate_fingerprint = &fingerprint,
        .tv_nonce = &tv_nonce,
        .controller_nonce = &controller_nonce,
        .controller_name = "Windows Controller",
    };
    try std.testing.expectEqualSlices(u8, &expected_pairing_mac, &(try pairingMac("123456", pairing)));
    try std.testing.expectEqualStrings("738145", &(try pairingSas("123456", pairing)));

    var expected_auth_mac: [mac_length]u8 = undefined;
    _ = try std.fmt.hexToBytes(&expected_auth_mac, "d9cd5a5424899eee5a4b1188cc79647bf88381c3561b6d4315648e6e750e1296");
    try std.testing.expectEqualSlices(u8, &expected_auth_mac, &(try authMac(&secret, .{
        .certificate_fingerprint = &fingerprint,
        .controller_id = "controller-01",
        .challenge_id = "challenge-2026-07-30",
        .client_nonce = &client_nonce,
        .server_nonce = &server_nonce,
    })));
}
