const std = @import("std");
const adb_manager = @import("adb_manager.zig");
const media_transport = @import("media_transport.zig");
const process_supervisor = @import("process_supervisor.zig");
const protocol = @import("scrcpy_protocol.zig");

pub const remote_server_path = "/data/local/tmp/tvrc-scrcpy-server-v4.1.jar";
pub const maximum_serial_length = 255;
pub const startup_deadline_ms: u32 = 60_000;
pub const cleanup_deadline_ms: u32 = 2_000;
pub const startup_attempts = 40;
pub const startup_retry_ms = 50;
pub const locked_server_size: u64 = 733706;
pub const locked_server_sha256 = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae";

pub const StartupDeadline = struct {
    expires_ms: i64,

    pub fn init(io: std.Io) StartupDeadline {
        return .{ .expires_ms = nowMs(io) + startup_deadline_ms };
    }

    pub fn remaining(self: StartupDeadline, io: std.Io, operation_cap_ms: u32) !u32 {
        return remainingAt(self.expires_ms, nowMs(io), operation_cap_ms);
    }

    pub fn remainingAt(expires_ms: i64, now_ms: i64, operation_cap_ms: u32) !u32 {
        if (operation_cap_ms == 0) return error.InvalidTimeout;
        const available = expires_ms - now_ms;
        if (available <= 0) return error.ScrcpyStartupDeadlineExceeded;
        return @intCast(@min(available, @as(i64, operation_cap_ms)));
    }
};

pub const CleanupDeadline = struct {
    expires_ms: i64,

    pub fn init(io: std.Io) CleanupDeadline {
        return .{ .expires_ms = nowMs(io) + cleanup_deadline_ms };
    }

    pub fn remaining(self: CleanupDeadline, io: std.Io) !u32 {
        return remainingAt(self.expires_ms, nowMs(io));
    }

    pub fn remainingAt(expires_ms: i64, now_ms: i64) !u32 {
        const available = expires_ms - now_ms;
        if (available <= 0) return error.ScrcpyCleanupDeadlineExceeded;
        return @intCast(@min(available, @as(i64, cleanup_deadline_ms)));
    }
};

pub const StartOptions = struct {
    adb: adb_manager.AdbManager,
    supervisor: process_supervisor.ProcessSupervisor,
    serial: []const u8,
    server_artifact_path: []const u8,
    enable_audio: bool,
    cancel_event: *std.Io.Event,
    forward_cleanup_failed: *bool,
    diagnostic_buffer: []u8,
    diagnostic_len: *usize,
};

pub const Session = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    adb: adb_manager.AdbManager,
    supervisor: process_supervisor.ProcessSupervisor,
    serial: [maximum_serial_length]u8 = undefined,
    serial_len: u8,
    local_port: u16,
    server_process: process_supervisor.ManagedProcess,
    video_stream: std.Io.net.Stream,
    audio_stream: ?std.Io.net.Stream,
    video_reader: std.Io.net.Stream.Reader = undefined,
    audio_reader: std.Io.net.Stream.Reader = undefined,
    video_reader_buffer: [16 * 1024]u8 = undefined,
    audio_reader_buffer: [8 * 1024]u8 = undefined,
    device_name: [protocol.device_name_length]u8 = undefined,
    device_name_len: u8 = 0,
    video_adapter: protocol.H264Adapter,
    audio_adapter: protocol.AudioAdapter = .{},
    active: bool = true,
    cancel_event: *std.Io.Event,

    pub fn start(allocator: std.mem.Allocator, io: std.Io, options: StartOptions) !*Session {
        options.forward_cleanup_failed.* = false;
        options.diagnostic_len.* = 0;
        if (options.serial.len == 0 or options.serial.len > maximum_serial_length) return error.InvalidDeviceSerial;
        if (!process_supervisor.isAbsoluteExecutablePath(options.server_artifact_path)) {
            return error.ServerArtifactPathMustBeAbsolute;
        }
        try verifyServerArtifact(io, options.server_artifact_path);
        const deadline = StartupDeadline.init(io);

        try runChecked(options.adb, options.supervisor, options.serial, &.{
            "push", options.server_artifact_path, remote_server_path,
        }, try deadline.remaining(io, 25_000));

        var scid_bytes: [4]u8 = undefined;
        // 使用完整 32 位随机值（不做 & 0x7fff_ffff 折叠，避免冲突概率翻倍）；
        // 0 时重新生成而非置 1，保持均匀分布。
        var scid: u32 = 0;
        while (scid == 0) {
            try io.randomSecure(&scid_bytes);
            scid = std.mem.readInt(u32, &scid_bytes, .big);
        }
        var scid_text_buffer: [8]u8 = undefined;
        const scid_text = try std.fmt.bufPrint(&scid_text_buffer, "{x:0>8}", .{scid});
        var socket_name_buffer: [32]u8 = undefined;
        const socket_name = try std.fmt.bufPrint(&socket_name_buffer, "localabstract:scrcpy_{s}", .{scid_text});

        const local_port = try createForward(options.adb, options.supervisor, options.serial, socket_name, try deadline.remaining(io, 8_000));
        errdefer removeForward(options.adb, options.supervisor, options.serial, local_port, cleanup_deadline_ms) catch {
            options.forward_cleanup_failed.* = true;
        };

        var server_port_buffer: [5]u8 = undefined;
        const server_port = try std.fmt.bufPrint(&server_port_buffer, "{d}", .{options.adb.server_port});
        var bitrate_buffer: [32]u8 = undefined;
        const bitrate = try std.fmt.bufPrint(&bitrate_buffer, "video_bit_rate={d}", .{2_000_000});
        const audio_option = if (options.enable_audio) "audio=true" else "audio=false";
        const server_command = [_][]const u8{
            "shell",
            "CLASSPATH=" ++ remote_server_path,
            "app_process",
            "/",
            "com.genymobile.scrcpy.Server",
            protocol.locked_server_version,
            try std.fmt.bufPrint(&socket_name_buffer, "scid={s}", .{scid_text}),
            "log_level=warn",
            "video=true",
            audio_option,
            "control=false",
            "video_codec=h264",
            "audio_codec=aac",
            "max_size=1280",
            "max_fps=15",
            bitrate,
            "tunnel_forward=true",
            "send_device_meta=true",
            "send_frame_meta=true",
            "send_dummy_byte=true",
            "cleanup=true",
        };
        var process_arguments: [server_command.len + 5][]const u8 = undefined;
        const process_argument_count = try options.adb.deviceArgs(
            options.serial,
            &server_command,
            &process_arguments,
        );
        // Keep an explicit private-server argument buffer. `deviceArgs` already
        // wrote the same value; this check prevents accidental lifetime bugs if
        // that contract changes.
        if (!std.mem.eql(u8, process_arguments[2], server_port)) return error.PrivateServerPortMismatch;
        var managed = try options.supervisor.start(.{ .argv = process_arguments[0..process_argument_count] });
        errdefer managed.deinit();
        errdefer {
            const tail = managed.diagnosticTail(options.diagnostic_buffer);
            options.diagnostic_len.* = tail.len;
        }

        const video_stream = try connectWithRetry(io, local_port, deadline);
        errdefer video_stream.close(io);
        var audio_stream = if (options.enable_audio) try connectWithRetry(io, local_port, deadline) else null;
        errdefer if (audio_stream) |stream| stream.close(io);

        // 握手与媒体元数据读取全部在 Session 分配与 `session.* =` 赋值之前完成：
        // 一切失败都发生在 managed/video_stream/audio_stream 被复制进 session
        // 之前，errdefer 只操作未 move 的局部变量，不依赖值复制语义；Session
        // 创建之后不存在可能失败的操作，因此无需依赖外层 errdefer 清理 session。
        var video_reader_buffer: [16 * 1024]u8 = undefined;
        var video_reader = std.Io.net.Stream.Reader.init(video_stream, io, &video_reader_buffer);
        var dummy: [1]u8 = undefined;
        _ = try deadline.remaining(io, startup_deadline_ms);
        try readAllUntil(&video_reader.interface, &dummy, io, options.cancel_event, deadline);
        if (dummy[0] != 0) return error.InvalidScrcpyHandshake;
        var device_name_bytes: [protocol.device_name_length]u8 = undefined;
        try readAllUntil(&video_reader.interface, &device_name_bytes, io, options.cancel_event, deadline);
        const device_name = try protocol.validateDeviceName(&device_name_bytes);
        var video_metadata_bytes: [protocol.video_codec_metadata_length]u8 = undefined;
        try readAllUntil(&video_reader.interface, &video_metadata_bytes, io, options.cancel_event, deadline);
        const video_metadata = try protocol.VideoMetadata.decode(&video_metadata_bytes);
        var audio_reader_buffer: [8 * 1024]u8 = undefined;
        var audio_reader = if (audio_stream) |stream| std.Io.net.Stream.Reader.init(stream, io, &audio_reader_buffer) else null;
        if (audio_reader != null) {
            _ = try deadline.remaining(io, startup_deadline_ms);
            var audio_metadata_bytes: [protocol.audio_codec_metadata_length]u8 = undefined;
            // 音频元数据不可用仅降级为纯视频会话（与原有行为一致）：取消与
            // 启动超时按启动失败处理，其余错误关闭音频流后继续返回会话。
            const audio_metadata_result = readAllUntil(&audio_reader.?.interface, &audio_metadata_bytes, io, options.cancel_event, deadline);
            if (audio_metadata_result) |_| {
                if (!audioMetadataAvailable(&audio_metadata_bytes)) {
                    audio_stream.?.close(io);
                    audio_stream = null;
                }
            } else |err| {
                switch (err) {
                    error.Canceled, error.ScrcpyStartupDeadlineExceeded => return err,
                    else => {
                        audio_stream.?.close(io);
                        audio_stream = null;
                    },
                }
            }
        }

        const session = try allocator.create(Session);
        session.* = .{
            .allocator = allocator,
            .io = io,
            .adb = options.adb,
            .supervisor = options.supervisor,
            .serial_len = @intCast(options.serial.len),
            .local_port = local_port,
            .server_process = managed,
            .video_stream = video_stream,
            .audio_stream = audio_stream,
            .device_name_len = @intCast(device_name.len),
            .video_adapter = .init(video_metadata),
            .cancel_event = options.cancel_event,
        };
        @memcpy(session.serial[0..options.serial.len], options.serial);
        @memcpy(session.device_name[0..device_name.len], device_name);
        session.video_reader = .init(session.video_stream, io, &session.video_reader_buffer);
        if (session.audio_stream != null) {
            session.audio_reader = .init(session.audio_stream.?, io, &session.audio_reader_buffer);
        }
        return session;
    }

    pub fn deviceName(self: *const Session) []const u8 {
        return self.device_name[0..self.device_name_len];
    }

    pub fn readVideo(self: *Session, payload: []u8, normalized: []u8) !protocol.AdaptedPacket {
        var encoded_header: [protocol.packet_header_length]u8 = undefined;
        try readAllCancelable(&self.video_reader.interface, &encoded_header, self.io, self.cancel_event);
        const header = try protocol.PacketHeader.decode(&encoded_header);
        if (payload.len < header.payload_len) return error.BufferTooSmall;
        try readAllCancelable(&self.video_reader.interface, payload[0..header.payload_len], self.io, self.cancel_event);
        return self.video_adapter.adapt(header, payload[0..header.payload_len], normalized);
    }

    pub fn readAudio(self: *Session, payload: []u8) !protocol.AdaptedPacket {
        if (self.audio_stream == null) return error.AudioDisabled;
        var encoded_header: [protocol.packet_header_length]u8 = undefined;
        try readAllCancelable(&self.audio_reader.interface, &encoded_header, self.io, self.cancel_event);
        const header = try protocol.PacketHeader.decode(&encoded_header);
        if (payload.len < header.payload_len) return error.BufferTooSmall;
        try readAllCancelable(&self.audio_reader.interface, payload[0..header.payload_len], self.io, self.cancel_event);
        return self.audio_adapter.adapt(header, payload[0..header.payload_len]);
    }

    pub fn stopAudio(self: *Session) void {
        if (self.audio_stream) |stream| stream.close(self.io);
        self.audio_stream = null;
    }

    pub fn beginStop(self: *Session) ?CleanupDeadline {
        if (!self.active) return null;
        self.active = false;
        const deadline = CleanupDeadline.init(self.io);
        self.cancel_event.set(self.io);
        self.video_stream.close(self.io);
        self.stopAudio();
        self.server_process.deinit();
        return deadline;
    }

    pub fn finishStop(self: *Session, deadline: CleanupDeadline) !void {
        try removeForward(
            self.adb,
            self.supervisor,
            self.serial[0..self.serial_len],
            self.local_port,
            try deadline.remaining(self.io),
        );
    }

    pub fn destroy(self: *Session) void {
        std.debug.assert(!self.active);
        self.allocator.destroy(self);
    }
};

pub fn audioMetadataAvailable(metadata: *const [protocol.audio_codec_metadata_length]u8) bool {
    _ = protocol.AudioMetadata.decode(metadata) catch return false;
    return true;
}

pub fn verifyServerArtifact(io: std.Io, path: []const u8) !void {
    if (!process_supervisor.isAbsoluteExecutablePath(path)) return error.ServerArtifactPathMustBeAbsolute;
    const file = try std.Io.Dir.openFileAbsolute(io, path, .{});
    defer file.close(io);
    const stat = try file.stat(io);
    if (stat.kind != .file) return error.ServerArtifactNotRegularFile;
    if (stat.size != locked_server_size) return error.ServerArtifactSizeMismatch;

    var hash = std.crypto.hash.sha2.Sha256.init(.{});
    var reader_buffer: [16 * 1024]u8 = undefined;
    var chunk: [16 * 1024]u8 = undefined;
    var reader = file.reader(io, &reader_buffer);
    while (true) {
        const count = reader.interface.readSliceShort(&chunk) catch return error.ServerArtifactReadFailed;
        if (count == 0) break;
        hash.update(chunk[0..count]);
    }
    var actual: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
    hash.final(&actual);
    var expected: [std.crypto.hash.sha2.Sha256.digest_length]u8 = undefined;
    _ = std.fmt.hexToBytes(&expected, locked_server_sha256) catch unreachable;
    if (!std.crypto.timing_safe.eql([actual.len]u8, actual, expected)) {
        return error.ServerArtifactChecksumMismatch;
    }
}

fn runChecked(
    adb: adb_manager.AdbManager,
    supervisor: process_supervisor.ProcessSupervisor,
    serial: []const u8,
    command: []const []const u8,
    timeout_ms: u32,
) !void {
    var argument_buffer: [32][]const u8 = undefined;
    var result = try adb.runDeviceCommand(supervisor, serial, command, &argument_buffer, timeout_ms);
    defer result.deinit(supervisor.allocator);
    if (!result.termination.succeeded()) return error.AdbCommandFailed;
}

fn createForward(
    adb: adb_manager.AdbManager,
    supervisor: process_supervisor.ProcessSupervisor,
    serial: []const u8,
    socket_name: []const u8,
    timeout_ms: u32,
) !u16 {
    var argument_buffer: [16][]const u8 = undefined;
    var result = try adb.runDeviceCommand(
        supervisor,
        serial,
        &.{ "forward", "tcp:0", socket_name },
        &argument_buffer,
        timeout_ms,
    );
    defer result.deinit(supervisor.allocator);
    if (!result.termination.succeeded()) return error.AdbForwardFailed;
    const output = std.mem.trim(u8, result.stdout, " \t\r\n");
    const port = std.fmt.parseInt(u16, output, 10) catch return error.InvalidForwardPort;
    if (port < 1024) return error.InvalidForwardPort;
    return port;
}

fn removeForward(
    adb: adb_manager.AdbManager,
    supervisor: process_supervisor.ProcessSupervisor,
    serial: []const u8,
    port: u16,
    timeout_ms: u32,
) !void {
    var port_buffer: [16]u8 = undefined;
    const local = try std.fmt.bufPrint(&port_buffer, "tcp:{d}", .{port});
    try runChecked(adb, supervisor, serial, &.{ "forward", "--remove", local }, timeout_ms);
}

fn connectWithRetry(io: std.Io, port: u16, deadline: StartupDeadline) !std.Io.net.Stream {
    const address: std.Io.net.IpAddress = .{ .ip4 = .loopback(port) };
    var attempts: u32 = 0;
    while (attempts < startup_attempts) : (attempts += 1) {
        const remaining = try deadline.remaining(io, 250);
        return address.connect(io, .{
            .mode = .stream,
            .protocol = .tcp,
            .timeout = .{ .duration = .{ .raw = .fromMilliseconds(remaining), .clock = .awake } },
        }) catch |err| switch (err) {
            error.ConnectionRefused, error.ConnectionResetByPeer, error.Timeout => {
                const retry = try deadline.remaining(io, startup_retry_ms);
                try std.Io.sleep(io, .fromMilliseconds(retry), .awake);
                continue;
            },
            else => |other| return other,
        };
    }
    return error.ScrcpyStartupDeadlineExceeded;
}

fn nowMs(io: std.Io) i64 {
    return std.Io.Clock.awake.now(io).toMilliseconds();
}

const ReadContext = struct {
    reader: *std.Io.Reader,
    destination: []u8,

    fn run(context: ReadContext) anyerror!void {
        return context.reader.readSliceAll(context.destination);
    }
};

const CancelContext = struct {
    io: std.Io,
    event: *std.Io.Event,

    fn run(context: CancelContext) anyerror!void {
        return context.event.wait(context.io);
    }
};

const DeadlineContext = struct {
    io: std.Io,
    expires_ms: i64,

    fn run(context: DeadlineContext) anyerror!void {
        const remaining = context.expires_ms - nowMs(context.io);
        if (remaining > 0) try std.Io.sleep(context.io, .fromMilliseconds(@intCast(remaining)), .awake);
    }
};

fn readAllCancelable(reader: *std.Io.Reader, destination: []u8, io: std.Io, cancel_event: *std.Io.Event) !void {
    const Result = union(enum) { read: anyerror!void, canceled: anyerror!void };
    var results: [2]Result = undefined;
    var select: std.Io.Select(Result) = .init(io, &results);
    defer select.cancelDiscard();
    select.async(.read, ReadContext.run, .{ReadContext{ .reader = reader, .destination = destination }});
    select.async(.canceled, CancelContext.run, .{CancelContext{ .io = io, .event = cancel_event }});
    switch (try select.await()) {
        .read => |result| try result,
        .canceled => |result| {
            try result;
            return error.Canceled;
        },
    }
}

fn readAllUntil(
    reader: *std.Io.Reader,
    destination: []u8,
    io: std.Io,
    cancel_event: *std.Io.Event,
    deadline: StartupDeadline,
) !void {
    const Result = union(enum) { read: anyerror!void, canceled: anyerror!void, deadline: anyerror!void };
    var results: [3]Result = undefined;
    var select: std.Io.Select(Result) = .init(io, &results);
    defer select.cancelDiscard();
    select.async(.read, ReadContext.run, .{ReadContext{ .reader = reader, .destination = destination }});
    select.async(.canceled, CancelContext.run, .{CancelContext{ .io = io, .event = cancel_event }});
    select.async(.deadline, DeadlineContext.run, .{DeadlineContext{ .io = io, .expires_ms = deadline.expires_ms }});
    switch (try select.await()) {
        .read => |result| try result,
        .canceled => |result| {
            try result;
            return error.Canceled;
        },
        .deadline => |result| {
            try result;
            return error.ScrcpyStartupDeadlineExceeded;
        },
    }
}

test "scrcpy manager pins server path and never enables control channel" {
    std.testing.refAllDecls(@This());
    try std.testing.expectEqualStrings(
        "/data/local/tmp/tvrc-scrcpy-server-v4.1.jar",
        remote_server_path,
    );
    try std.testing.expectEqual(@as(usize, media_transport.max_packet_size), 4 * 1024 * 1024);
}

test "scrcpy server artifact lock is complete" {
    try std.testing.expectEqual(@as(usize, 64), locked_server_sha256.len);
    try std.testing.expect(locked_server_size > 0);
    var expected: [32]u8 = undefined;
    _ = try std.fmt.hexToBytes(&expected, locked_server_sha256);
}

test "scrcpy startup work is bounded by the sixty second budget" {
    const connection_budget = startup_attempts * (250 + startup_retry_ms);
    try std.testing.expect(25_000 + 8_000 + connection_budget * 2 <= startup_deadline_ms);
}

test "startup deadline is absolute and never refreshed by an operation" {
    try std.testing.expectEqual(@as(u32, 5_000), StartupDeadline.remainingAt(65_000, 60_000, 25_000));
    try std.testing.expectEqual(@as(u32, 8_000), StartupDeadline.remainingAt(65_000, 50_000, 8_000));
    try std.testing.expectError(error.ScrcpyStartupDeadlineExceeded, StartupDeadline.remainingAt(65_000, 65_000, 1));
}

test "cleanup uses one two second forward deadline" {
    try std.testing.expectEqual(@as(u32, 2_000), cleanup_deadline_ms);
    try std.testing.expectEqual(@as(u32, 1_250), CleanupDeadline.remainingAt(3_000, 1_750));
    try std.testing.expectError(error.ScrcpyCleanupDeadlineExceeded, CleanupDeadline.remainingAt(3_000, 3_000));
}

test "disabled or error audio metadata degrades to video only" {
    const disabled = [_]u8{ 0, 0, 0, 0 };
    const failed = [_]u8{ 0, 0, 0, 1 };
    const aac = [_]u8{ 'a', 'a', 'c', ' ' };
    try std.testing.expect(!audioMetadataAvailable(&disabled));
    try std.testing.expect(!audioMetadataAvailable(&failed));
    try std.testing.expect(audioMetadataAvailable(&aac));
}
