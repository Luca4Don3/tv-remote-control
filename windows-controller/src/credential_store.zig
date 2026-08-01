const std = @import("std");

const c = @cImport({
    @cInclude("tvrc_credential_store.h");
});

pub const max_credential_id_length: u32 = 64;
pub const max_secret_length: u32 = 4096;

pub const PutFn = *const fn (?*anyopaque, ?[*]const u8, u32, ?[*]const u8, u32) callconv(.c) i32;
pub const GetFn = *const fn (?*anyopaque, ?[*]const u8, u32, ?[*]u8, u32, ?*u32) callconv(.c) i32;
pub const RemoveFn = *const fn (?*anyopaque, ?[*]const u8, u32) callconv(.c) i32;

pub const Adapter = struct {
    context: ?*anyopaque = null,
    put_fn: ?PutFn = null,
    get_fn: ?GetFn = null,
    remove_fn: ?RemoveFn = null,

    pub fn platformDefault() Adapter {
        return .{
            .put_fn = @ptrCast(&c.tvrc_platform_credentials_put),
            .get_fn = @ptrCast(&c.tvrc_platform_credentials_get),
            .remove_fn = @ptrCast(&c.tvrc_platform_credentials_remove),
        };
    }

    pub fn validate(self: Adapter) !void {
        const configured: u8 = @as(u8, @intFromBool(self.put_fn != null)) +
            @as(u8, @intFromBool(self.get_fn != null)) +
            @as(u8, @intFromBool(self.remove_fn != null));
        if (configured != 0 and configured != 3) return error.IncompleteCredentialAdapter;
    }

    pub fn put(self: Adapter, credential_id: []const u8, secret: []const u8) !void {
        try self.validate();
        try validateId(credential_id);
        if (secret.len == 0 or secret.len > max_secret_length) return error.InvalidSecret;
        const callback = self.put_fn orelse return error.CredentialStoreUnavailable;
        try mapResult(callback(self.context, credential_id.ptr, @intCast(credential_id.len), secret.ptr, @intCast(secret.len)));
    }

    /// Does not consume a credential when the caller buffer is too small.
    pub fn get(self: Adapter, credential_id: []const u8, output: []u8) !usize {
        return self.getWithRequired(credential_id, output, null);
    }

    /// 与 get 相同，但 BufferTooSmall 时把 C 层报告的所需字节数写入
    /// required_out，供上层分配更大缓冲区后重试。
    pub fn getWithRequired(self: Adapter, credential_id: []const u8, output: []u8, required_out: ?*usize) !usize {
        try self.validate();
        try validateId(credential_id);
        const callback = self.get_fn orelse return error.CredentialStoreUnavailable;
        var required: u32 = 0;
        const result = callback(
            self.context,
            credential_id.ptr,
            @intCast(credential_id.len),
            if (output.len == 0) null else output.ptr,
            @intCast(@min(output.len, std.math.maxInt(u32))),
            &required,
        );
        if (result == c.TVRC_CREDENTIAL_BUFFER_TOO_SMALL) {
            if (required_out) |out| out.* = required;
            return error.BufferTooSmall;
        }
        try mapResult(result);
        if (required > output.len or required > max_secret_length) return error.InvalidCredentialData;
        return required;
    }

    pub fn remove(self: Adapter, credential_id: []const u8) !void {
        try self.validate();
        try validateId(credential_id);
        const callback = self.remove_fn orelse return error.CredentialStoreUnavailable;
        try mapResult(callback(self.context, credential_id.ptr, @intCast(credential_id.len)));
    }
};

fn validateId(value: []const u8) !void {
    if (value.len == 0 or value.len > max_credential_id_length) return error.InvalidCredentialId;
}

fn mapResult(result: i32) !void {
    return switch (result) {
        c.TVRC_CREDENTIAL_OK => {},
        c.TVRC_CREDENTIAL_INVALID_ARGUMENT => error.InvalidArgument,
        c.TVRC_CREDENTIAL_UNSUPPORTED => error.CredentialStoreUnavailable,
        c.TVRC_CREDENTIAL_BUFFER_TOO_SMALL => error.BufferTooSmall,
        c.TVRC_CREDENTIAL_NOT_FOUND => error.CredentialNotFound,
        else => error.CredentialStoreFailure,
    };
}

test "platform credential adapter has complete callback surface" {
    const adapter = Adapter.platformDefault();
    try adapter.validate();
    try std.testing.expect(adapter.put_fn != null);
    try std.testing.expect(adapter.get_fn != null);
    try std.testing.expect(adapter.remove_fn != null);
}

test "partial platform adapters fail explicitly" {
    const adapter = Adapter{ .put_fn = fakePut };
    try std.testing.expectError(error.IncompleteCredentialAdapter, adapter.validate());
}

fn fakePut(_: ?*anyopaque, _: ?[*]const u8, _: u32, _: ?[*]const u8, _: u32) callconv(.c) i32 {
    return c.TVRC_CREDENTIAL_OK;
}
