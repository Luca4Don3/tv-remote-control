const std = @import("std");

pub const AdbState = enum { authorized, unauthorized, offline, unknown };

pub const AdbDevice = struct {
    serial: []const u8,
    state: AdbState,
    product: ?[]const u8 = null,
    model: ?[]const u8 = null,
    device: ?[]const u8 = null,
};

pub const AndroidProperties = struct {
    manufacturer: ?[]const u8,
    brand: ?[]const u8,
    model: ?[]const u8,
    device: ?[]const u8,
    product: ?[]const u8,
    firmware: ?[]const u8,
    sdk: ?[]const u8,
    abi: ?[]const u8,
    abi_list: ?[]const u8,
};

pub fn firstDevice(output: []const u8) ?AdbDevice {
    var lines = std.mem.splitScalar(u8, output, '\n');
    while (lines.next()) |raw_line| {
        const line = std.mem.trim(u8, raw_line, " \r\t");
        if (line.len == 0 or std.mem.startsWith(u8, line, "List of devices attached")) continue;
        var fields = std.mem.tokenizeAny(u8, line, " \t");
        const serial = fields.next() orelse continue;
        const state_text = fields.next() orelse continue;
        var result = AdbDevice{
            .serial = serial,
            .state = if (std.mem.eql(u8, state_text, "device"))
                .authorized
            else if (std.mem.eql(u8, state_text, "unauthorized"))
                .unauthorized
            else if (std.mem.eql(u8, state_text, "offline"))
                .offline
            else
                .unknown,
        };
        while (fields.next()) |field| {
            if (std.mem.startsWith(u8, field, "product:")) result.product = field["product:".len..];
            if (std.mem.startsWith(u8, field, "model:")) result.model = field["model:".len..];
            if (std.mem.startsWith(u8, field, "device:")) result.device = field["device:".len..];
        }
        return result;
    }
    return null;
}

pub fn parseProperties(output: []const u8) AndroidProperties {
    return .{
        .manufacturer = property(output, "ro.product.manufacturer"),
        .brand = property(output, "ro.product.brand"),
        .model = property(output, "ro.product.model"),
        .device = property(output, "ro.product.device"),
        .product = property(output, "ro.product.name"),
        .firmware = property(output, "ro.build.display.id"),
        .sdk = property(output, "ro.build.version.sdk"),
        .abi = property(output, "ro.product.cpu.abi"),
        .abi_list = property(output, "ro.product.cpu.abilist"),
    };
}

fn property(output: []const u8, requested_key: []const u8) ?[]const u8 {
    var lines = std.mem.splitScalar(u8, output, '\n');
    while (lines.next()) |raw_line| {
        const line = std.mem.trim(u8, raw_line, " \r\t");
        if (line.len < 6 or line[0] != '[') continue;
        const separator = std.mem.indexOf(u8, line, "]: [") orelse continue;
        if (!std.mem.eql(u8, line[1..separator], requested_key)) continue;
        const value_start = separator + 4;
        if (value_start >= line.len or line[line.len - 1] != ']') return null;
        return line[value_start .. line.len - 1];
    }
    return null;
}

test "parses authorized adb device without persisting it" {
    const output =
        \\List of devices attached
        \\<internal_ip>:5555 device product:tv_product model:Living_Room_TV device:tv_board transport_id:1
    ;
    const device = firstDevice(output).?;
    try std.testing.expectEqual(AdbState.authorized, device.state);
    try std.testing.expectEqualStrings("Living_Room_TV", device.model.?);
}

test "parses Android 4.4 properties and missing ABI list" {
    const output =
        \\[ro.product.manufacturer]: [vendor]
        \\[ro.product.model]: [legacy-tv]
        \\[ro.product.cpu.abi]: [armeabi-v7a]
        \\[ro.build.version.sdk]: [19]
        \\[ro.build.display.id]: [firmware]
    ;
    const properties = parseProperties(output);
    try std.testing.expectEqualStrings("legacy-tv", properties.model.?);
    try std.testing.expectEqualStrings("19", properties.sdk.?);
    try std.testing.expect(properties.abi_list == null);
}
