const std = @import("std");
const adb_manager = @import("adb_manager.zig");

pub const AdbState = enum { authorized, unauthorized, offline, unknown };

pub const AdbDevice = struct {
    serial: []const u8,
    state: AdbState,
    product: ?[]const u8 = null,
    model: ?[]const u8 = null,
    device: ?[]const u8 = null,
};

pub const DeviceFilter = struct {
    authorized_only: bool = true,
    include_emulators: bool = true,

    pub fn matches(self: DeviceFilter, device: AdbDevice) bool {
        if (self.authorized_only and device.state != .authorized) return false;
        if (!self.include_emulators and std.mem.startsWith(u8, device.serial, "emulator-")) return false;
        return true;
    }
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
    var iterator = DeviceIterator{ .lines = std.mem.splitScalar(u8, output, '\n') };
    return iterator.next();
}

pub const DeviceIterator = struct {
    lines: std.mem.SplitIterator(u8, .scalar),

    pub fn next(self: *DeviceIterator) ?AdbDevice {
        while (self.lines.next()) |raw_line| {
            if (parseDeviceLine(raw_line)) |device| return device;
        }
        return null;
    }
};

pub const FilteredDeviceIterator = struct {
    inner: DeviceIterator,
    filter: DeviceFilter,

    pub fn next(self: *FilteredDeviceIterator) ?AdbDevice {
        while (self.inner.next()) |device| {
            if (self.filter.matches(device)) return device;
        }
        return null;
    }
};

pub fn devices(output: []const u8) DeviceIterator {
    return .{ .lines = std.mem.splitScalar(u8, output, '\n') };
}

pub fn filteredDevices(output: []const u8, filter: DeviceFilter) FilteredDeviceIterator {
    return .{ .inner = devices(output), .filter = filter };
}

pub fn authorizedDeviceBySerial(output: []const u8, requested_serial: []const u8) !AdbDevice {
    // 比较前 trim 首尾空白，避免调用方传入带空格的序列号导致误判。
    const serial = std.mem.trim(u8, requested_serial, " \t\r\n");
    if (!adb_manager.isSafeSerial(serial)) return error.InvalidDeviceSerial;
    var result: ?AdbDevice = null;
    var iterator = devices(output);
    while (iterator.next()) |device| {
        if (!std.mem.eql(u8, device.serial, serial)) continue;
        if (result != null) return error.DuplicateDeviceSerial;
        result = device;
    }
    const device = result orelse return error.DeviceNotFound;
    return switch (device.state) {
        .authorized => device,
        .unauthorized => error.DeviceUnauthorized,
        .offline => error.DeviceOffline,
        .unknown => error.DeviceStateUnsupported,
    };
}

fn parseDeviceLine(raw_line: []const u8) ?AdbDevice {
    const line = std.mem.trim(u8, raw_line, " \r\t");
    if (line.len == 0 or
        std.mem.startsWith(u8, line, "List of devices attached") or
        std.mem.startsWith(u8, line, "* daemon") or
        std.mem.startsWith(u8, line, "adb server version")) return null;
    var fields = std.mem.tokenizeAny(u8, line, " \t");
    const serial = fields.next() orelse return null;
    if (!adb_manager.isSafeSerial(serial)) return null;
    const state_text = fields.next() orelse return null;
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
        if (fieldValue(field, "product:")) |value| result.product = value;
        if (fieldValue(field, "model:")) |value| result.model = value;
        if (fieldValue(field, "device:")) |value| result.device = value;
    }
    return result;
}

fn fieldValue(field: []const u8, prefix: []const u8) ?[]const u8 {
    if (!std.mem.startsWith(u8, field, prefix) or field.len == prefix.len) return null;
    const value = field[prefix.len..];
    if (std.mem.indexOfScalar(u8, value, 0) != null) return null;
    return value;
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

test "iterates the complete adb device list" {
    const output =
        \\List of devices attached
        \\first unauthorized transport_id:1
        \\second device model:TV transport_id:2
    ;
    var iterator = devices(output);
    try std.testing.expectEqualStrings("first", iterator.next().?.serial);
    try std.testing.expectEqualStrings("second", iterator.next().?.serial);
    try std.testing.expect(iterator.next() == null);
}

test "filters daemon chatter and devices which cannot be controlled" {
    const output =
        \\* daemon not running; starting now at tcp:5038
        \\* daemon started successfully
        \\List of devices attached
        \\unauthorized-tv unauthorized transport_id:1
        \\offline-tv offline transport_id:2
        \\emulator-5554 device model:Test_TV transport_id:3
        \\living-room device model:Living_Room_TV transport_id:4
    ;
    var iterator = filteredDevices(output, .{ .include_emulators = false });
    try std.testing.expectEqualStrings("living-room", iterator.next().?.serial);
    try std.testing.expect(iterator.next() == null);
}

test "explicit device selection reports authorization state" {
    const output =
        \\List of devices attached
        \\first unauthorized transport_id:1
        \\second device model:TV transport_id:2
    ;
    try std.testing.expectError(error.DeviceUnauthorized, authorizedDeviceBySerial(output, "first"));
    const selected = try authorizedDeviceBySerial(output, "second");
    try std.testing.expectEqualStrings("TV", selected.model.?);
    try std.testing.expectError(error.DeviceNotFound, authorizedDeviceBySerial(output, "missing"));
    try std.testing.expectError(error.InvalidDeviceSerial, authorizedDeviceBySerial(output, "-d"));
}

test "duplicate serials fail explicitly" {
    const output =
        \\List of devices attached
        \\same device transport_id:1
        \\same device transport_id:2
    ;
    try std.testing.expectError(error.DuplicateDeviceSerial, authorizedDeviceBySerial(output, "same"));
}
