const std = @import("std");
const process_supervisor = @import("process_supervisor.zig");

pub const default_private_server_port: u16 = 5038;
pub const shared_adb_server_port: u16 = 5037;

pub const CandidateSource = enum {
    user_selected,
    system_path,
    managed_default,
};

pub const CandidateState = enum {
    missing,
    incompatible,
    usable,
};

pub const Candidate = struct {
    path: []const u8,
    source: CandidateSource,
    state: CandidateState,
};

pub const Version = struct {
    protocol_major: u16,
    protocol_minor: u16,
    protocol_patch: u16,
    platform_major: u16,
    platform_minor: u16,
    platform_patch: u16,

    pub fn compatible(self: Version) bool {
        return atLeast(self.protocol_major, self.protocol_minor, self.protocol_patch, 1, 0, 41) and
            atLeast(self.platform_major, self.platform_minor, self.platform_patch, 30, 0, 0);
    }

    pub fn lockedManaged(self: Version) bool {
        return self.platform_major == 37 and self.platform_minor == 0 and self.platform_patch == 1;
    }
};

pub fn parseVersion(output: []const u8) !Version {
    const protocol = valueAfter(output, "Android Debug Bridge version ") orelse return error.ProtocolVersionMissing;
    const platform = valueAfter(output, "Version ") orelse return error.PlatformToolsVersionMissing;
    const protocol_parts = try parseTriplet(protocol);
    const platform_parts = try parseTriplet(platform);
    return .{
        .protocol_major = protocol_parts[0],
        .protocol_minor = protocol_parts[1],
        .protocol_patch = protocol_parts[2],
        .platform_major = platform_parts[0],
        .platform_minor = platform_parts[1],
        .platform_patch = platform_parts[2],
    };
}

fn valueAfter(output: []const u8, prefix: []const u8) ?[]const u8 {
    var lines = std.mem.splitScalar(u8, output, '\n');
    while (lines.next()) |raw| {
        const line = std.mem.trim(u8, raw, " \t\r");
        if (std.mem.startsWith(u8, line, prefix)) return line[prefix.len..];
    }
    return null;
}

fn parseTriplet(value: []const u8) ![3]u16 {
    var result: [3]u16 = undefined;
    var parts = std.mem.splitScalar(u8, value, '.');
    for (0..3) |index| {
        const part = parts.next() orelse return error.InvalidVersion;
        const end = std.mem.indexOfAny(u8, part, "- \t\r") orelse part.len;
        if (end == 0) return error.InvalidVersion;
        result[index] = try std.fmt.parseInt(u16, part[0..end], 10);
    }
    // 多余的第四段（如 1.0.41.extra）必须显式报错，不得静默忽略。
    if (parts.next() != null) return error.InvalidVersion;
    return result;
}

fn atLeast(major: u16, minor: u16, patch: u16, wanted_major: u16, wanted_minor: u16, wanted_patch: u16) bool {
    if (major != wanted_major) return major > wanted_major;
    if (minor != wanted_minor) return minor > wanted_minor;
    return patch >= wanted_patch;
}

pub const SearchInputs = struct {
    user_selected: ?Candidate = null,
    system_path: ?Candidate = null,
    managed_default: ?Candidate = null,
};

/// Selects only a fully probed usable executable and preserves the documented
/// order: saved user path, resolved PATH entry, then the managed directory.
pub fn selectAdbCandidate(inputs: SearchInputs) !?Candidate {
    const ordered = [_]?Candidate{
        inputs.user_selected,
        inputs.system_path,
        inputs.managed_default,
    };
    for (ordered) |maybe_candidate| {
        const candidate = maybe_candidate orelse continue;
        if (!isSafeAdbExecutablePath(candidate.path)) {
            return error.AdbPathMustBeAbsolute;
        }
        if (candidate.state == .usable) return candidate;
    }
    return null;
}

/// Produces absolute `adb.exe` candidates from Windows PATH without modifying
/// PATH. Relative, empty, device-namespace and network entries are ignored.
pub const WindowsPathIterator = struct {
    entries: std.mem.SplitIterator(u8, .scalar),

    pub fn init(path_value: []const u8) WindowsPathIterator {
        return .{ .entries = std.mem.splitScalar(u8, path_value, ';') };
    }

    pub fn next(self: *WindowsPathIterator, allocator: std.mem.Allocator) !?[]u8 {
        while (self.entries.next()) |raw_entry| {
            var entry = std.mem.trim(u8, raw_entry, " \t\r\n");
            if (entry.len >= 2 and entry[0] == '"' and entry[entry.len - 1] == '"') {
                entry = std.mem.trim(u8, entry[1 .. entry.len - 1], " \t");
            }
            if (!isSafeLocalWindowsDirectory(entry)) continue;
            return try std.fmt.allocPrint(allocator, "{s}\\adb.exe", .{std.mem.trimEnd(u8, entry, "\\/")});
        }
        return null;
    }
};

pub const WindowsArch = enum {
    x86,
    x86_64,
    arm64,
};

pub const InstallStrategy = enum {
    automatic_native,
    automatic_x64_emulated,
    manual_probe_only,
};

pub fn installStrategy(arch: WindowsArch) InstallStrategy {
    return switch (arch) {
        .x86 => .manual_probe_only,
        .x86_64 => .automatic_native,
        .arm64 => .automatic_x64_emulated,
    };
}

pub const CapabilityProbe = struct {
    version_compatible: bool,
    private_server_supported: bool,
    complete_device_listing: bool,
    explicit_serial_supported: bool,

    pub fn passed(self: CapabilityProbe) bool {
        return self.version_compatible and
            self.private_server_supported and
            self.complete_device_listing and
            self.explicit_serial_supported;
    }
};

pub fn manualBinaryAccepted(arch: WindowsArch, probe: CapabilityProbe) bool {
    return arch == .x86 and probe.passed();
}

pub const AdbManager = struct {
    adb_path: []const u8,
    server_port: u16,
    port_text: [5]u8,
    port_text_len: u8,

    pub fn init(adb_path: []const u8, server_port: u16) !AdbManager {
        if (!process_supervisor.isAbsoluteExecutablePath(adb_path)) return error.AdbPathMustBeAbsolute;
        if (!isSafeAdbExecutablePath(adb_path)) return error.UnsafeAdbPath;
        if (server_port < 1024 or server_port == shared_adb_server_port) {
            return error.InvalidPrivateServerPort;
        }

        var result = AdbManager{
            .adb_path = adb_path,
            .server_port = server_port,
            .port_text = undefined,
            .port_text_len = 0,
        };
        const rendered = try std.fmt.bufPrint(&result.port_text, "{d}", .{server_port});
        result.port_text_len = @intCast(rendered.len);
        return result;
    }

    pub fn hostArgs(
        self: *const AdbManager,
        command: []const []const u8,
        output: [][]const u8,
    ) !usize {
        try validateCommand(command);
        if (output.len < command.len + 3) return error.ArgumentBufferTooSmall;
        output[0] = self.adb_path;
        output[1] = "-P";
        output[2] = self.port_text[0..self.port_text_len];
        @memcpy(output[3 .. command.len + 3], command);
        return command.len + 3;
    }

    pub fn deviceArgs(
        self: *const AdbManager,
        serial: []const u8,
        command: []const []const u8,
        output: [][]const u8,
    ) !usize {
        if (!isSafeSerial(serial)) return error.InvalidDeviceSerial;
        try validateCommand(command);
        if (output.len < command.len + 5) return error.ArgumentBufferTooSmall;
        output[0] = self.adb_path;
        output[1] = "-P";
        output[2] = self.port_text[0..self.port_text_len];
        output[3] = "-s";
        output[4] = serial;
        @memcpy(output[5 .. command.len + 5], command);
        return command.len + 5;
    }

    pub fn runHostCommand(
        self: *const AdbManager,
        supervisor: process_supervisor.ProcessSupervisor,
        command: []const []const u8,
        argument_buffer: [][]const u8,
        timeout_ms: u32,
    ) !process_supervisor.Result {
        const count = try self.hostArgs(command, argument_buffer);
        return supervisor.run(.{
            .argv = argument_buffer[0..count],
            .timeout_ms = timeout_ms,
        });
    }

    pub fn runDeviceCommand(
        self: *const AdbManager,
        supervisor: process_supervisor.ProcessSupervisor,
        serial: []const u8,
        command: []const []const u8,
        argument_buffer: [][]const u8,
        timeout_ms: u32,
    ) !process_supervisor.Result {
        const count = try self.deviceArgs(serial, command, argument_buffer);
        return supervisor.run(.{
            .argv = argument_buffer[0..count],
            .timeout_ms = timeout_ms,
        });
    }

    /// Starts one long-lived private ADB server. The returned process owns the
    /// Job Object for the complete enhancement session.
    pub fn startPrivateServer(
        self: *const AdbManager,
        supervisor: process_supervisor.ProcessSupervisor,
    ) !process_supervisor.ManagedProcess {
        var arguments: [5][]const u8 = undefined;
        const count = try self.hostArgs(&.{ "nodaemon", "server" }, &arguments);
        return supervisor.start(.{ .argv = arguments[0..count] });
    }
};

pub fn isSafeSerial(serial: []const u8) bool {
    // 先单独判断空串，再访问 serial[0]，避免越界。
    if (serial.len == 0) return false;
    if (serial.len > 255 or serial[0] == '-') return false;
    for (serial) |byte| {
        if (byte <= 0x20 or byte == 0x7f) return false;
    }
    return true;
}

pub fn isSafeAdbExecutablePath(path: []const u8) bool {
    if (!process_supervisor.isAbsoluteExecutablePath(path) or !hasAdbExecutableName(path)) return false;
    if (path.len >= 2 and path[1] == ':' and std.mem.indexOfScalarPos(u8, path, 2, ':') != null) return false;
    var components = std.mem.tokenizeAny(u8, path, "\\/");
    while (components.next()) |component| {
        if (std.mem.eql(u8, component, ".") or std.mem.eql(u8, component, "..")) return false;
    }
    return true;
}

fn validateCommand(command: []const []const u8) !void {
    if (command.len == 0) return error.EmptyAdbCommand;
    if (command[0].len == 0 or command[0][0] == '-') return error.InvalidAdbCommand;
    for (command) |argument| {
        if (std.mem.indexOfScalar(u8, argument, 0) != null or
            std.mem.indexOfAny(u8, argument, "\r\n") != null)
        {
            return error.InvalidAdbArgument;
        }
    }
}

fn hasAdbExecutableName(path: []const u8) bool {
    const basename = basenameAnySeparator(path);
    return std.ascii.eqlIgnoreCase(basename, "adb.exe") or std.mem.eql(u8, basename, "adb");
}

fn basenameAnySeparator(path: []const u8) []const u8 {
    const slash = std.mem.lastIndexOfScalar(u8, path, '/') orelse 0;
    const backslash = std.mem.lastIndexOfScalar(u8, path, '\\') orelse 0;
    const index = @max(slash, backslash);
    if (index == 0 and path[0] != '/' and path[0] != '\\') return path;
    return path[index + 1 ..];
}

fn isSafeLocalWindowsDirectory(path: []const u8) bool {
    if (path.len < 4 or !std.ascii.isAlphabetic(path[0]) or path[1] != ':') return false;
    if (path[2] != '\\' and path[2] != '/') return false;
    if (path[3] == '\\' or path[3] == '/') return false;
    if (std.mem.indexOfScalar(u8, path, 0) != null or std.mem.indexOfAny(u8, path, "\r\n") != null) return false;
    if (std.mem.indexOfScalarPos(u8, path, 2, ':') != null) return false;
    var components = std.mem.tokenizeAny(u8, path[3..], "\\/");
    while (components.next()) |component| {
        if (std.mem.eql(u8, component, ".") or std.mem.eql(u8, component, "..")) return false;
    }
    return true;
}

test "search order favors saved path then PATH then managed directory" {
    const selected = (try selectAdbCandidate(.{
        .user_selected = .{ .path = "C:\\chosen\\adb.exe", .source = .user_selected, .state = .usable },
        .system_path = .{ .path = "C:\\path\\adb.exe", .source = .system_path, .state = .usable },
        .managed_default = .{ .path = "C:\\managed\\adb.exe", .source = .managed_default, .state = .usable },
    })).?;
    try std.testing.expectEqual(CandidateSource.user_selected, selected.source);

    const fallback = (try selectAdbCandidate(.{
        .user_selected = .{ .path = "C:\\chosen\\adb.exe", .source = .user_selected, .state = .incompatible },
        .system_path = .{ .path = "C:\\path\\adb.exe", .source = .system_path, .state = .usable },
    })).?;
    try std.testing.expectEqual(CandidateSource.system_path, fallback.source);
}

test "PATH candidates are absolute and PATH is never rewritten" {
    var iterator = WindowsPathIterator.init("relative;C:\\bad\\..\\escape;\"C:\\Program Files\\Android\";\\\\server\\share");
    const candidate = (try iterator.next(std.testing.allocator)).?;
    defer std.testing.allocator.free(candidate);
    try std.testing.expectEqualStrings("C:\\Program Files\\Android\\adb.exe", candidate);
    try std.testing.expect((try iterator.next(std.testing.allocator)) == null);
}

test "all ADB commands use the private port and device commands use serial" {
    var manager = try AdbManager.init("C:\\Android\\platform-tools\\adb.exe", default_private_server_port);
    var arguments: [10][]const u8 = undefined;

    const host_count = try manager.hostArgs(&.{ "devices", "-l" }, &arguments);
    try std.testing.expectEqual(@as(usize, 5), host_count);
    try std.testing.expectEqualStrings("-P", arguments[1]);
    try std.testing.expectEqualStrings("5038", arguments[2]);

    const device_count = try manager.deviceArgs("serial-1", &.{ "shell", "getprop" }, &arguments);
    try std.testing.expectEqual(@as(usize, 7), device_count);
    try std.testing.expectEqualStrings("-P", arguments[1]);
    try std.testing.expectEqualStrings("-s", arguments[3]);
    try std.testing.expectEqualStrings("serial-1", arguments[4]);
}

test "private server uses long lived nodaemon mode on port 5038" {
    var manager = try AdbManager.init("C:\\Android\\platform-tools\\adb.exe", default_private_server_port);
    var arguments: [5][]const u8 = undefined;
    const count = try manager.hostArgs(&.{ "nodaemon", "server" }, &arguments);
    try std.testing.expectEqual(@as(usize, 5), count);
    const expected = [_][]const u8{ "C:\\Android\\platform-tools\\adb.exe", "-P", "5038", "nodaemon", "server" };
    for (expected, arguments[0..count]) |wanted, actual| try std.testing.expectEqualStrings(wanted, actual);
}

test "shared server and serial option injection are rejected" {
    try std.testing.expectError(
        error.InvalidPrivateServerPort,
        AdbManager.init("C:\\Android\\adb.exe", shared_adb_server_port),
    );
    var manager = try AdbManager.init("C:\\Android\\adb.exe", default_private_server_port);
    var arguments: [8][]const u8 = undefined;
    try std.testing.expectError(
        error.InvalidDeviceSerial,
        manager.deviceArgs("-d", &.{"get-state"}, &arguments),
    );
    try std.testing.expectError(
        error.InvalidAdbCommand,
        manager.hostArgs(&.{ "-P", "5037", "devices" }, &arguments),
    );
    try std.testing.expectError(
        error.UnsafeAdbPath,
        AdbManager.init("C:\\Android\\..\\adb.exe", default_private_server_port),
    );
}

test "Windows architecture policy is explicit" {
    try std.testing.expectEqual(InstallStrategy.automatic_native, installStrategy(.x86_64));
    try std.testing.expectEqual(InstallStrategy.automatic_x64_emulated, installStrategy(.arm64));
    try std.testing.expectEqual(InstallStrategy.manual_probe_only, installStrategy(.x86));
    try std.testing.expect(!manualBinaryAccepted(.x86, .{
        .version_compatible = true,
        .private_server_supported = false,
        .complete_device_listing = true,
        .explicit_serial_supported = true,
    }));
    try std.testing.expect(manualBinaryAccepted(.x86, .{
        .version_compatible = true,
        .private_server_supported = true,
        .complete_device_listing = true,
        .explicit_serial_supported = true,
    }));
}

test "ADB version probe requires protocol 1.0.41 and Platform Tools 30.0.0" {
    const locked = try parseVersion(
        "Android Debug Bridge version 1.0.41\nVersion 37.0.1-14196534\nInstalled as C:\\Android\\adb.exe\n",
    );
    try std.testing.expect(locked.compatible());
    try std.testing.expect(locked.lockedManaged());

    const old_protocol = try parseVersion("Android Debug Bridge version 1.0.40\nVersion 37.0.1\n");
    try std.testing.expect(!old_protocol.compatible());
    const old_tools = try parseVersion("Android Debug Bridge version 1.0.41\nVersion 29.0.6\n");
    try std.testing.expect(!old_tools.compatible());
    try std.testing.expectError(error.PlatformToolsVersionMissing, parseVersion("Android Debug Bridge version 1.0.41\n"));
}
