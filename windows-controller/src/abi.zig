const std = @import("std");
const builtin = @import("builtin");
const adb_manager = @import("adb_manager.zig");
const control_client = @import("control_client.zig");
const control_protocol = @import("control_protocol.zig");
const credential_store = @import("credential_store.zig");
const lan_discovery = @import("lan_discovery.zig");
const media_transport = @import("media_transport.zig");
const platform = @import("platform.zig");
const process_supervisor = @import("process_supervisor.zig");
const adb_devices = @import("device_discovery.zig");
const scrcpy_manager = @import("scrcpy_manager.zig");

extern fn tvrc_windows_companion_path(name: [*:0]const u8, output: [*]u8, capacity: u32, output_len: *u32) callconv(.c) i32;

pub const abi_version: u32 = 1;
pub const default_request_capacity: u32 = 8;
pub const default_event_capacity: u32 = 40;
pub const max_request_capacity: u32 = 64;
pub const max_event_capacity: u32 = 256;
const max_request_argument = 255;
const max_event_payload = 1024;
const max_adb_argument = 1023;
const max_adb_devices = 32;
const adb_request_capacity = 8;
const default_controller_name = switch (builtin.os.tag) {
    .windows => "Windows Controller",
    .macos => "macOS Controller",
    else => "Desktop Controller",
};

pub const Result = enum(i32) {
    ok = 0,
    invalid_argument = 1,
    invalid_state = 2,
    unsupported = 3,
    buffer_too_small = 4,
    queue_full = 5,
    not_found = 6,
    io_error = 7,
    cancelled = 8,
    unauthenticated = 9,
    internal_error = 10,
};

pub const EventType = enum(u32) {
    state_changed = 1,
    device_found = 2,
    pairing_sas = 3,
    capabilities_changed = 4,
    command_ack = 5,
    request_complete = 6,
    error_event = 7,
    media_state_changed = 8,
    adb_state_changed = 9,
    adb_device_found = 10,
};

pub const Config = extern struct {
    struct_size: u32,
    abi_version: u32,
    request_queue_capacity: u32,
    event_queue_capacity: u32,
    flags: u32,
    reserved: u32,
    controller_name: ?[*]const u8,
    controller_name_len: u32,
    controller_name_reserved: u32,
    credential_context: ?*anyopaque,
    credentials_put: ?credential_store.PutFn,
    credentials_get: ?credential_store.GetFn,
    credentials_remove: ?credential_store.RemoveFn,
};

pub const Event = extern struct {
    struct_size: u32,
    abi_version: u32,
    event_type: u32,
    status: i32,
    request_id: u64,
    payload_len: u32,
    reserved: u32,
};

pub const MediaPacket = extern struct {
    struct_size: u32,
    abi_version: u32,
    track: u32,
    flags: u32,
    sequence: u32,
    codec_config_id: u32,
    presentation_time_us: u64,
    payload_len: u32,
    width: u16,
    height: u16,
    reserved: u32,
};

const RequestKind = enum { discover, pair_submit, connect, disconnect, send_key, media_start, media_stop };

const AdbRequestKind = enum { probe, install, media_start, media_stop, disable };

const MediaLifecycle = enum {
    none,
    apk_starting,
    apk_active,
    adb_starting,
    adb_active,
    stopping,
};

const AdbRequest = struct {
    kind: AdbRequestKind,
    request_id: u64,
    flags: u32 = 0,
    argument_len: u16 = 0,
    argument: [max_adb_argument]u8 = undefined,

    fn argumentSlice(self: *const AdbRequest) []const u8 {
        return self.argument[0..self.argument_len];
    }
};

const FoundAdb = struct {
    path: []u8,
    source: adb_manager.CandidateSource,
    version: adb_manager.Version,
};

fn OwnedSlot(comptime T: type) type {
    return struct {
        const Self = @This();

        value: ?T = null,

        fn isEmpty(self: *const Self) bool {
            return self.value == null;
        }

        fn borrow(self: *const Self) ?T {
            return self.value;
        }

        fn publish(self: *Self, value: T) bool {
            if (self.value != null) return false;
            self.value = value;
            return true;
        }

        fn take(self: *Self) ?T {
            const owned = self.value;
            self.value = null;
            return owned;
        }
    };
}

/// The ADB worker publishes these resources. Any thread may take teardown
/// ownership while holding `Handle.adb_media_mutex`; fields remain protected by
/// `Handle.mutex`.
const AdbRuntime = struct {
    server: ?process_supervisor.ManagedProcess = null,
    session: OwnedSlot(*scrcpy_manager.Session) = .{},
    video_worker: ?std.Thread = null,
    audio_worker: ?std.Thread = null,
    media_stop_requested: bool = false,
    video_failed: bool = false,
    audio_failed: bool = false,
};

const ManagedInstallState = enum { install_required, upgrade_required, ready };

fn managedInstallState(directory_exists: bool, adb_exists: bool, marker_matches: bool) ManagedInstallState {
    if (!directory_exists) return .install_required;
    return if (adb_exists and marker_matches) .ready else .upgrade_required;
}

fn canPublishAdbSession(state: platform.ConnectionState, lifecycle: MediaLifecycle, slot_empty: bool, cancelled: bool) bool {
    return state == .connected and lifecycle == .adb_starting and slot_empty and !cancelled;
}

const Request = struct {
    kind: RequestKind,
    request_id: u64,
    argument_len: u16 = 0,
    argument: [max_request_argument]u8 = undefined,
    key: u32 = 0,
    key_state: u32 = 0,

    fn argumentSlice(self: *const Request) []const u8 {
        return self.argument[0..self.argument_len];
    }
};

const QueuedEvent = struct {
    event_type: EventType,
    request_id: u64,
    status: Result,
    payload: [max_event_payload]u8 = undefined,
    payload_len: u32 = 0,

    fn setPayload(self: *QueuedEvent, value: []const u8) !void {
        if (value.len > self.payload.len) return error.EventPayloadTooLarge;
        @memcpy(self.payload[0..value.len], value);
        self.payload_len = @intCast(value.len);
    }
};

const Handle = struct {
    allocator: std.mem.Allocator,
    io_backend: std.Io.Threaded,
    mutex: std.Io.Mutex = .init,
    /// `mutex` protects ADB fields; this mutex serializes session lifetime
    /// publication, borrowing and destruction.
    adb_media_mutex: std.Io.Mutex = .init,
    request_available: std.Io.Condition = .init,
    adb_available: std.Io.Condition = .init,
    state: platform.ConnectionState = .stopped,
    stop_requested: bool = false,
    worker: ?std.Thread = null,
    adb_worker: ?std.Thread = null,
    adb_stop_requested: bool = false,
    adb_cancel_requested: std.atomic.Value(bool) = .init(false),
    adb_cancel_event: std.Io.Event = .unset,
    adb_requests: [adb_request_capacity]AdbRequest = undefined,
    adb_request_head: usize = 0,
    adb_request_count: usize = 0,
    adb_path: [max_adb_argument]u8 = undefined,
    adb_path_len: u16 = 0,
    adb_probe_upgrade_required: bool = false,
    adb_runtime: AdbRuntime = .{},
    media_lifecycle: MediaLifecycle = .none,
    requests: []Request,
    request_head: usize = 0,
    request_count: usize = 0,
    events: []QueuedEvent,
    event_head: usize = 0,
    event_count: usize = 0,
    reserved_events: usize = 0,
    credentials: credential_store.Adapter,
    control: control_client.Client,
    media_queue: media_transport.PacketQueue,
    media_tls: @import("tls_transport.zig").Client,
    media_worker: ?std.Thread = null,
    media_stop_requested: bool = false,
    media_leftover: [4096]u8 = undefined,
    media_leftover_len: usize = 0,
    media_leftover_offset: usize = 0,
    media_sequences: [2]u32 = @splat(0),
    media_config_ids: [2]u32 = @splat(0),

    fn io(self: *Handle) std.Io {
        return self.io_backend.io();
    }

    fn lock(self: *Handle) void {
        self.mutex.lockUncancelable(self.io());
    }

    fn unlock(self: *Handle) void {
        self.mutex.unlock(self.io());
    }

    fn lockAdbMedia(self: *Handle) void {
        self.adb_media_mutex.lockUncancelable(self.io());
    }

    fn unlockAdbMedia(self: *Handle) void {
        self.adb_media_mutex.unlock(self.io());
    }

    fn enqueueRequest(self: *Handle, request: Request) Result {
        self.lock();
        defer self.unlock();
        if (self.state == .stopped or self.stop_requested or self.worker == null) return .invalid_state;
        if (self.request_count == self.requests.len) return .queue_full;
        const reservation = eventReservation(request.kind);
        // Always retain one slot for the terminal stopped state.
        if (self.event_count + self.reserved_events + reservation >= self.events.len) return .queue_full;
        const index = (self.request_head + self.request_count) % self.requests.len;
        self.requests[index] = request;
        self.request_count += 1;
        self.reserved_events += reservation;
        self.request_available.signal(self.io());
        return .ok;
    }

    fn enqueueAdbRequest(self: *Handle, request: AdbRequest) Result {
        self.lock();
        defer self.unlock();
        if (builtin.os.tag != .windows) return .unsupported;
        if (self.state == .stopped or self.adb_stop_requested or self.adb_worker == null) return .invalid_state;
        if (self.adb_request_count == self.adb_requests.len) return .queue_full;
        const reservation = adbEventReservation(request.kind);
        if (self.event_count + self.reserved_events + reservation >= self.events.len) return .queue_full;
        const index = (self.adb_request_head + self.adb_request_count) % self.adb_requests.len;
        self.adb_requests[index] = request;
        self.adb_request_count += 1;
        self.reserved_events += reservation;
        self.adb_available.signal(self.io());
        return .ok;
    }

    fn enqueueAdbDisable(self: *Handle, request: AdbRequest) Result {
        self.lock();
        defer self.unlock();
        if (builtin.os.tag != .windows) return .unsupported;
        if (self.state == .stopped or self.adb_stop_requested or self.adb_worker == null) return .invalid_state;
        if (self.adb_request_count == self.adb_requests.len) return .queue_full;
        const reservation = adbEventReservation(.disable);
        if (self.event_count + self.reserved_events + reservation >= self.events.len) return .queue_full;
        self.adb_cancel_requested.store(true, .release);
        self.adb_cancel_event.set(self.io());
        self.adb_request_head = (self.adb_request_head + self.adb_requests.len - 1) % self.adb_requests.len;
        self.adb_requests[self.adb_request_head] = request;
        self.adb_request_count += 1;
        self.reserved_events += reservation;
        self.adb_available.signal(self.io());
        return .ok;
    }

    fn popAdbRequestLocked(self: *Handle) ?AdbRequest {
        if (self.adb_request_count == 0) return null;
        const request = self.adb_requests[self.adb_request_head];
        std.crypto.secureZero(u8, &self.adb_requests[self.adb_request_head].argument);
        self.adb_request_head = (self.adb_request_head + 1) % self.adb_requests.len;
        self.adb_request_count -= 1;
        return request;
    }

    fn adbWorkerMain(self: *Handle) void {
        while (true) {
            self.lock();
            while (self.adb_request_count == 0 and !self.adb_stop_requested and !self.adb_runtime.video_failed and !self.adb_runtime.audio_failed) {
                self.adb_available.waitUncancelable(self.io(), &self.mutex);
            }
            if (self.adb_runtime.video_failed) {
                self.adb_runtime.video_failed = false;
                self.unlock();
                const cleaned = self.stopAdbMedia();
                if (!cleaned) self.terminateAdbServer();
                _ = self.enqueueUnreserved(.media_state_changed, 0, .io_error, "{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"video_stream_failed\"}");
                continue;
            }
            if (self.adb_runtime.audio_failed) {
                self.adb_runtime.audio_failed = false;
                self.unlock();
                self.lockAdbMedia();
                self.lock();
                const session = if (self.media_lifecycle == .adb_active) self.adb_runtime.session.borrow() else null;
                self.unlock();
                if (session) |active| active.stopAudio();
                self.unlockAdbMedia();
                if (session != null) {
                    _ = self.enqueueUnreserved(.media_state_changed, 0, .ok, "{\"state\":\"video_only\",\"backend\":\"adb_scrcpy\",\"reason\":\"audio_stream_failed\"}");
                }
                continue;
            }
            if (self.adb_request_count == 0 and self.adb_stop_requested) {
                self.unlock();
                return;
            }
            var request = self.popAdbRequestLocked().?;
            const cancelling = self.adb_stop_requested;
            self.unlock();
            defer std.crypto.secureZero(u8, &request.argument);
            if (cancelling) {
                self.cancelAdbRequest(request);
            } else {
                if (request.kind != .disable and request.kind != .media_stop) {
                    self.adb_cancel_requested.store(false, .release);
                    self.adb_cancel_event.reset();
                }
                self.processAdbRequest(request);
            }
        }
    }

    fn cancelAdbRequest(self: *Handle, request: AdbRequest) void {
        self.cancelAdbRequestAfter(request, 0);
    }

    fn cancelAdbRequestAfter(self: *Handle, request: AdbRequest, used_reservations: usize) void {
        const reservation = adbEventReservation(request.kind);
        std.debug.assert(reservation >= used_reservations + 2);
        self.consumeUnusedReservations(reservation - used_reservations - 2);
        switch (request.kind) {
            .media_start, .media_stop => self.enqueueReserved(
                .media_state_changed,
                request.request_id,
                .cancelled,
                "{\"state\":\"stopped\",\"backend\":\"adb_scrcpy\",\"reason\":\"cancelled\"}",
            ),
            else => self.enqueueReserved(
                .adb_state_changed,
                request.request_id,
                .cancelled,
                "{\"state\":\"stopped\",\"reason\":\"cancelled\"}",
            ),
        }
        self.enqueueReserved(.request_complete, request.request_id, .cancelled, "{\"error\":\"cancelled\"}");
    }

    fn processAdbRequest(self: *Handle, request: AdbRequest) void {
        switch (request.kind) {
            .probe => self.processAdbProbe(request),
            .install => self.processAdbInstall(request),
            .media_start => self.processAdbMediaStart(request),
            .media_stop => self.processAdbMediaStop(request),
            .disable => self.processAdbDisable(request),
        }
    }

    fn processAdbProbe(self: *Handle, request: AdbRequest) void {
        self.stopAdbProcesses();
        self.enqueueReserved(.adb_state_changed, request.request_id, .ok, "{\"state\":\"searching\"}");
        const found = self.findUsableAdb(request.argumentSlice()) orelse {
            self.consumeUnusedReservations(max_adb_devices);
            if (self.adb_cancel_requested.load(.acquire)) {
                self.enqueueReserved(.adb_state_changed, request.request_id, .cancelled, "{\"state\":\"stopped\",\"reason\":\"cancelled\"}");
                self.enqueueReserved(.request_complete, request.request_id, .cancelled, "{\"error\":\"cancelled\"}");
                return;
            }
            self.enqueueReserved(.adb_state_changed, request.request_id, .not_found, if (self.adb_probe_upgrade_required) "{\"state\":\"upgrade_required\"}" else "{\"state\":\"install_required\"}");
            self.enqueueReserved(.request_complete, request.request_id, .not_found, "{\"error\":\"adb_not_found_or_probe_failed\"}");
            return;
        };
        defer self.allocator.free(found.path);
        const path = found.path;
        if (path.len > self.adb_path.len) {
            self.consumeUnusedReservations(max_adb_devices);
            self.enqueueReserved(.adb_state_changed, request.request_id, .invalid_argument, "{\"state\":\"failed\",\"reason\":\"adb_path_too_long\"}");
            self.enqueueReserved(.request_complete, request.request_id, .invalid_argument, "{\"error\":\"adb_path_too_long\"}");
            return;
        }
        @memcpy(self.adb_path[0..path.len], path);
        self.adb_path_len = @intCast(path.len);
        var manager = adb_manager.AdbManager.init(self.adb_path[0..self.adb_path_len], adb_manager.default_private_server_port) catch {
            self.consumeUnusedReservations(max_adb_devices);
            self.enqueueReserved(.adb_state_changed, request.request_id, .invalid_argument, "{\"state\":\"failed\",\"reason\":\"unsafe_adb_path\"}");
            self.enqueueReserved(.request_complete, request.request_id, .invalid_argument, "{\"error\":\"unsafe_adb_path\"}");
            return;
        };
        const supervisor = self.adbSupervisor();
        const server = manager.startPrivateServer(supervisor) catch {
            self.consumeUnusedReservations(max_adb_devices);
            self.enqueueReserved(.adb_state_changed, request.request_id, .io_error, "{\"state\":\"failed\",\"reason\":\"private_adb_server_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"private_adb_server_failed\"}");
            return;
        };
        self.lock();
        self.adb_runtime.server = server;
        self.unlock();

        var arguments: [8][]const u8 = undefined;
        var listing = manager.runHostCommand(supervisor, &.{ "devices", "-l" }, &arguments, 10_000) catch |err| {
            self.stopAdbProcesses();
            if (err == error.Canceled) return self.cancelActiveProbe(request, 0);
            self.consumeUnusedReservations(max_adb_devices);
            self.enqueueReserved(.adb_state_changed, request.request_id, .io_error, "{\"state\":\"failed\",\"reason\":\"adb_device_enumeration_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"adb_device_enumeration_failed\"}");
            return;
        };
        defer listing.deinit(self.allocator);
        if (!listing.termination.succeeded()) {
            self.stopAdbProcesses();
            self.consumeUnusedReservations(max_adb_devices);
            self.enqueueReserved(.adb_state_changed, request.request_id, .io_error, "{\"state\":\"failed\",\"reason\":\"adb_device_enumeration_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"adb_device_enumeration_failed\"}");
            return;
        }
        var devices = adb_devices.devices(listing.stdout);
        var device_count: usize = 0;
        while (device_count < max_adb_devices) : (device_count += 1) {
            if (self.adb_cancel_requested.load(.acquire)) return self.cancelActiveProbe(request, device_count);
            const device = devices.next() orelse break;
            var property_result: ?process_supervisor.Result = null;
            defer if (property_result) |*result| result.deinit(self.allocator);
            if (device.state == .authorized) {
                property_result = manager.runDeviceCommand(
                    supervisor,
                    device.serial,
                    &.{ "shell", "getprop" },
                    &arguments,
                    10_000,
                ) catch null;
            }
            if (self.adb_cancel_requested.load(.acquire)) return self.cancelActiveProbe(request, device_count);
            const properties = if (property_result) |result| adb_devices.parseProperties(result.stdout) else null;
            var payload_buffer: [max_event_payload]u8 = undefined;
            var writer: std.Io.Writer = .fixed(&payload_buffer);
            std.json.Stringify.value(.{
                .serial = device.serial,
                .authorization = @tagName(device.state),
                .model = if (properties) |value| value.model orelse device.model orelse "" else device.model orelse "",
                .sdk = if (properties) |value| value.sdk orelse "" else "",
                .abi = if (properties) |value| value.abi orelse "" else "",
            }, .{}, &writer) catch {
                self.enqueueReserved(.adb_device_found, request.request_id, .internal_error, "{\"error\":\"adb_device_event_encode_failed\"}");
                continue;
            };
            self.enqueueReserved(.adb_device_found, request.request_id, .ok, writer.buffered());
        }
        self.consumeUnusedReservations(max_adb_devices - device_count);
        var ready_buffer: [max_event_payload]u8 = undefined;
        var ready_writer: std.Io.Writer = .fixed(&ready_buffer);
        std.json.Stringify.value(.{
            .state = "ready",
            .path = path,
            .source = @tagName(found.source),
            .protocolVersion = .{ .major = found.version.protocol_major, .minor = found.version.protocol_minor, .patch = found.version.protocol_patch },
            .platformToolsVersion = .{ .major = found.version.platform_major, .minor = found.version.platform_minor, .patch = found.version.platform_patch },
        }, .{}, &ready_writer) catch unreachable;
        self.enqueueReserved(.adb_state_changed, request.request_id, .ok, ready_writer.buffered());
        var complete_buffer: [96]u8 = undefined;
        const complete = std.fmt.bufPrint(&complete_buffer, "{{\"status\":\"ready\",\"devicesFound\":{d}}}", .{device_count}) catch "{\"status\":\"ready\"}";
        self.enqueueReserved(.request_complete, request.request_id, .ok, complete);
    }

    fn cancelActiveProbe(self: *Handle, request: AdbRequest, emitted_devices: usize) void {
        self.stopAdbProcesses();
        self.consumeUnusedReservations(max_adb_devices - emitted_devices);
        self.enqueueReserved(.adb_state_changed, request.request_id, .cancelled, "{\"state\":\"stopped\",\"reason\":\"cancelled\"}");
        self.enqueueReserved(.request_complete, request.request_id, .cancelled, "{\"error\":\"cancelled\"}");
    }

    fn findUsableAdb(self: *Handle, saved_path: []const u8) ?FoundAdb {
        self.adb_probe_upgrade_required = false;
        if (saved_path.len != 0) {
            if (self.probeAdbCandidate(saved_path)) |version| return .{ .path = self.allocator.dupe(u8, saved_path) catch return null, .source = .user_selected, .version = version };
            if (builtin.cpu.arch == .x86) return null;
        } else if (builtin.cpu.arch == .x86) return null;

        if (std.c.getenv("PATH")) |raw_path| {
            var iterator = adb_manager.WindowsPathIterator.init(std.mem.span(raw_path));
            // 每个候选路径在独立作用域内分配并在本迭代结束时释放，
            // 避免条件中分配 + defer 的隐式生命周期陷阱（use-after-free）。
            while (true) {
                const candidate = iterator.next(self.allocator) catch null orelse break;
                defer self.allocator.free(candidate);
                if (self.adb_cancel_requested.load(.acquire)) return null;
                if (self.probeAdbCandidate(candidate)) |version| return .{ .path = self.allocator.dupe(u8, candidate) catch return null, .source = .system_path, .version = version };
            }
        }
        if (self.adb_cancel_requested.load(.acquire)) return null;
        if (std.c.getenv("LOCALAPPDATA")) |raw_local| {
            const candidate = std.fmt.allocPrint(self.allocator, "{s}\\TV Remote Control\\platform-tools\\adb.exe", .{std.mem.span(raw_local)}) catch return null;
            defer self.allocator.free(candidate);
            const separator = std.mem.lastIndexOfAny(u8, candidate, "\\/") orelse return null;
            const state = managedInstallState(
                pathExists(self.io(), candidate[0..separator]),
                pathExists(self.io(), candidate),
                self.managedMarkerMatches(candidate),
            );
            switch (state) {
                .install_required => return null,
                .upgrade_required => {
                    self.adb_probe_upgrade_required = true;
                    return null;
                },
                .ready => {},
            }
            if (self.probeAdbCandidate(candidate)) |version| {
                if (version.lockedManaged()) return .{ .path = self.allocator.dupe(u8, candidate) catch return null, .source = .managed_default, .version = version };
                self.adb_probe_upgrade_required = true;
            }
        }
        return null;
    }

    fn managedMarkerMatches(self: *Handle, adb_path: []const u8) bool {
        const separator = std.mem.lastIndexOfAny(u8, adb_path, "\\/") orelse return false;
        const marker_path = std.fmt.allocPrint(
            self.allocator,
            "{s}\\.tv-remote-control-managed.json",
            .{adb_path[0..separator]},
        ) catch return false;
        defer self.allocator.free(marker_path);
        const file = std.Io.Dir.openFileAbsolute(self.io(), marker_path, .{}) catch return false;
        defer file.close(self.io());
        var reader_buffer: [1024]u8 = undefined;
        var contents: [4096]u8 = undefined;
        var reader = file.reader(self.io(), &reader_buffer);
        const count = reader.interface.readSliceShort(&contents) catch return false;
        // readSliceShort 在缓冲区填满时返回 contents.len，此时文件可能还有
        // 剩余数据。恰好 4096 字节的合法文件会在下一次读取返回 0 而通过；
        // 超出上限（截断或恶意超长）的文件在此被拒绝。
        if (count == contents.len) {
            var extra: [1]u8 = undefined;
            if ((reader.interface.readSliceShort(&extra) catch return false) != 0) return false;
        }
        const Marker = struct {
            schemaVersion: u32,
            version: []const u8,
            size: u64,
            sha256: []const u8,
            source: []const u8,
        };
        var parsed = std.json.parseFromSlice(Marker, self.allocator, contents[0..count], .{
            .duplicate_field_behavior = .@"error",
            .ignore_unknown_fields = true,
            .max_value_len = 1024,
        }) catch return false;
        defer parsed.deinit();
        // source 必须指向 Google 官方仓库前缀，与 install.zig 的
        // isSafeManagedWindowsPath / validateLock 约束保持一致：
        // 磁盘上的恶意 marker 无法通过伪造来源绕过来源校验。
        return parsed.value.schemaVersion == 2 and
            std.mem.eql(u8, parsed.value.version, "37.0.1") and
            parsed.value.size == 8_044_989 and
            std.ascii.eqlIgnoreCase(parsed.value.sha256, "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99") and
            std.mem.startsWith(u8, parsed.value.source, "https://dl.google.com/android/repository/");
    }

    fn probeAdbCandidate(self: *Handle, path: []const u8) ?adb_manager.Version {
        if (!adb_manager.isSafeAdbExecutablePath(path)) return null;
        const supervisor = self.adbSupervisor();
        var result = supervisor.run(.{
            .argv = &.{ path, "version" },
            .timeout_ms = 10_000,
            .output_limit = 64 * 1024,
        }) catch return null;
        defer result.deinit(self.allocator);
        if (!result.termination.succeeded()) return null;
        const output = std.mem.concat(self.allocator, u8, &.{ result.stdout, "\n", result.stderr }) catch return null;
        defer self.allocator.free(output);
        const version = adb_manager.parseVersion(output) catch return null;
        return if (version.compatible()) version else null;
    }

    fn processAdbInstall(self: *Handle, request: AdbRequest) void {
        if (builtin.cpu.arch == .x86) {
            self.enqueueReserved(.adb_state_changed, request.request_id, .unsupported, "{\"state\":\"unsupported\",\"reason\":\"x86_manual_probe_only\"}");
            self.enqueueReserved(.request_complete, request.request_id, .unsupported, "{\"error\":\"automatic_install_unsupported\"}");
            return;
        }
        var script_path: [max_adb_argument]u8 = undefined;
        var script_path_len: u32 = 0;
        if (tvrc_windows_companion_path("install-adb.ps1", &script_path, script_path.len, &script_path_len) != 0) {
            self.enqueueReserved(.adb_state_changed, request.request_id, .not_found, "{\"state\":\"failed\",\"reason\":\"bundled_installer_missing\"}");
            self.enqueueReserved(.request_complete, request.request_id, .not_found, "{\"error\":\"bundled_installer_missing\"}");
            return;
        }
        const windows_root = std.c.getenv("WINDIR") orelse {
            self.enqueueReserved(.adb_state_changed, request.request_id, .io_error, "{\"state\":\"failed\",\"reason\":\"windows_directory_unavailable\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"windows_directory_unavailable\"}");
            return;
        };
        const powershell = std.fmt.allocPrint(self.allocator, "{s}\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", .{std.mem.span(windows_root)}) catch {
            self.enqueueReserved(.adb_state_changed, request.request_id, .internal_error, "{\"state\":\"failed\",\"reason\":\"installer_path_allocation_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"installer_path_allocation_failed\"}");
            return;
        };
        defer self.allocator.free(powershell);
        const base_arguments = [_][]const u8{
            powershell,        "-NoLogo",                                                          "-NoProfile",       "-NonInteractive", "-ExecutionPolicy", "RemoteSigned",
            "-File",           script_path[0..script_path_len],                                    "-ExpectedVersion", "37.0.1",          "-ExpectedSize",    "8044989",
            "-ExpectedSha256", "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99",
        };
        var arguments: [base_arguments.len + 1][]const u8 = undefined;
        @memcpy(arguments[0..base_arguments.len], &base_arguments);
        var argument_count = base_arguments.len;
        if (request.flags == 2) {
            arguments[argument_count] = "-ConfirmUpgrade";
            argument_count += 1;
        }
        const supervisor = self.adbSupervisor();
        var result = supervisor.run(.{
            .argv = arguments[0..argument_count],
            .timeout_ms = 300_000,
            .output_limit = 64 * 1024,
        }) catch |err| {
            if (err == error.Canceled) return self.cancelAdbRequest(request);
            self.enqueueReserved(.adb_state_changed, request.request_id, .io_error, "{\"state\":\"failed\",\"reason\":\"adb_install_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"adb_install_failed\"}");
            return;
        };
        defer result.deinit(self.allocator);
        if (!result.termination.succeeded()) {
            self.enqueueReserved(.adb_state_changed, request.request_id, .io_error, "{\"state\":\"failed\",\"reason\":\"adb_install_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"adb_install_failed\"}");
            return;
        }
        self.enqueueReserved(.adb_state_changed, request.request_id, .ok, "{\"state\":\"stopped\",\"reason\":\"install_complete_reprobe_required\"}");
        self.enqueueReserved(.request_complete, request.request_id, .ok, "{\"status\":\"adb_installed\"}");
    }

    fn processAdbMediaStart(self: *Handle, request: AdbRequest) void {
        self.enqueueReserved(.adb_state_changed, request.request_id, .ok, "{\"state\":\"starting_media\"}");
        self.lock();
        const authenticated = self.state == .connected;
        const probed = self.adb_path_len != 0 and self.adb_runtime.server != null and self.adb_runtime.session.isEmpty();
        const available = self.media_lifecycle == .none;
        if (authenticated and probed and available) self.media_lifecycle = .adb_starting;
        self.unlock();
        if (!authenticated or !probed or !available) {
            const reason = if (!available) "media_busy" else "adb_media_prerequisite_failed";
            var payload_buffer: [160]u8 = undefined;
            const payload = std.fmt.bufPrint(&payload_buffer, "{{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"{s}\"}}", .{reason}) catch unreachable;
            self.enqueueReserved(.media_state_changed, request.request_id, .invalid_state, payload);
            self.enqueueReserved(.request_complete, request.request_id, .invalid_state, if (!available) "{\"error\":\"media_busy\"}" else "{\"error\":\"adb_media_prerequisite_failed\"}");
            return;
        }
        var starting_owned = true;
        defer if (starting_owned) {
            self.lock();
            if (self.media_lifecycle == .adb_starting) self.media_lifecycle = .none;
            self.unlock();
        };
        var manager = adb_manager.AdbManager.init(self.adb_path[0..self.adb_path_len], adb_manager.default_private_server_port) catch {
            self.enqueueReserved(.media_state_changed, request.request_id, .invalid_state, "{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"adb_probe_state_invalid\"}");
            self.enqueueReserved(.request_complete, request.request_id, .invalid_state, "{\"error\":\"adb_probe_state_invalid\"}");
            return;
        };
        const supervisor = self.adbSupervisor();
        var arguments: [12][]const u8 = undefined;
        var listing = manager.runHostCommand(supervisor, &.{ "devices", "-l" }, &arguments, 10_000) catch |err| {
            if (err == error.Canceled) return self.cancelAdbRequestAfter(request, 1);
            self.enqueueReserved(.media_state_changed, request.request_id, .io_error, "{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"device_revalidation_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"device_revalidation_failed\"}");
            return;
        };
        defer listing.deinit(self.allocator);
        _ = adb_devices.authorizedDeviceBySerial(listing.stdout, request.argumentSlice()) catch {
            self.enqueueReserved(.media_state_changed, request.request_id, .unauthenticated, "{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"device_not_authorized\"}");
            self.enqueueReserved(.request_complete, request.request_id, .unauthenticated, "{\"error\":\"device_not_authorized\"}");
            return;
        };
        var properties = manager.runDeviceCommand(supervisor, request.argumentSlice(), &.{ "shell", "getprop" }, &arguments, 10_000) catch |err| {
            if (err == error.Canceled) return self.cancelAdbRequestAfter(request, 1);
            self.enqueueReserved(.media_state_changed, request.request_id, .io_error, "{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"device_property_probe_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"device_property_probe_failed\"}");
            return;
        };
        defer properties.deinit(self.allocator);
        const android = adb_devices.parseProperties(properties.stdout);
        const sdk = std.fmt.parseInt(u16, android.sdk orelse "", 10) catch 0;
        if (sdk < 21) {
            self.enqueueReserved(.media_state_changed, request.request_id, .unsupported, "{\"state\":\"unsupported\",\"backend\":\"adb_scrcpy\",\"reason\":\"android_api_below_21\"}");
            self.enqueueReserved(.request_complete, request.request_id, .unsupported, "{\"error\":\"android_api_below_21\"}");
            return;
        }
        var artifact_path: [max_adb_argument]u8 = undefined;
        var artifact_path_len: u32 = 0;
        if (tvrc_windows_companion_path("scrcpy-server-v4.1", &artifact_path, artifact_path.len, &artifact_path_len) != 0) {
            self.enqueueReserved(.media_state_changed, request.request_id, .not_found, "{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"scrcpy_server_missing\"}");
            self.enqueueReserved(.request_complete, request.request_id, .not_found, "{\"error\":\"scrcpy_server_missing\"}");
            return;
        }
        var forward_cleanup_failed = false;
        var process_diagnostic: [512]u8 = undefined;
        var process_diagnostic_len: usize = 0;
        const session = scrcpy_manager.Session.start(self.allocator, self.io(), .{
            .adb = manager,
            .supervisor = supervisor,
            .serial = request.argumentSlice(),
            .server_artifact_path = artifact_path[0..artifact_path_len],
            .enable_audio = request.flags & 1 != 0,
            .cancel_event = &self.adb_cancel_event,
            .forward_cleanup_failed = &forward_cleanup_failed,
            .diagnostic_buffer = &process_diagnostic,
            .diagnostic_len = &process_diagnostic_len,
        }) catch |err| {
            if (forward_cleanup_failed) self.terminateAdbServer();
            const cancelled = err == error.Canceled and !forward_cleanup_failed;
            self.enqueueAdbMediaFailure(
                request.request_id,
                if (cancelled) .cancelled else .io_error,
                if (forward_cleanup_failed) "forward_cleanup_failed" else if (cancelled) "cancelled" else "scrcpy_start_failed",
                process_diagnostic[0..process_diagnostic_len],
            );
            self.enqueueReserved(
                .request_complete,
                request.request_id,
                if (err == error.Canceled and !forward_cleanup_failed) .cancelled else .io_error,
                if (forward_cleanup_failed) "{\"error\":\"forward_cleanup_failed\"}" else if (err == error.Canceled) "{\"error\":\"cancelled\"}" else "{\"error\":\"scrcpy_start_failed\"}",
            );
            return;
        };
        // Session.start may take up to the bounded startup deadline. Revalidate
        // the control connection before publishing its result, then keep final
        // publication and all local pointer use serialized against teardown.
        self.lockAdbMedia();
        defer self.unlockAdbMedia();
        self.lock();
        const connection_lost = self.state != .connected;
        const can_publish = canPublishAdbSession(
            self.state,
            self.media_lifecycle,
            self.adb_runtime.session.isEmpty(),
            self.stop_requested or self.adb_stop_requested or self.adb_cancel_requested.load(.acquire),
        );
        if (!can_publish) {
            self.unlock();
            const cleaned = cleanupUnpublishedAdbSession(session);
            if (!cleaned) self.terminateAdbServer();
            self.enqueueReserved(
                .media_state_changed,
                request.request_id,
                .cancelled,
                if (connection_lost)
                    "{\"state\":\"stopped\",\"backend\":\"adb_scrcpy\",\"reason\":\"control_connection_lost\"}"
                else
                    "{\"state\":\"stopped\",\"backend\":\"adb_scrcpy\",\"reason\":\"cancelled\"}",
            );
            self.enqueueReserved(
                .request_complete,
                request.request_id,
                .cancelled,
                if (connection_lost) "{\"error\":\"control_connection_lost\"}" else "{\"error\":\"cancelled\"}",
            );
            return;
        }
        self.adb_runtime.media_stop_requested = false;
        const video_worker = std.Thread.spawn(.{}, Handle.adbVideoThreadMain, .{ self, session }) catch {
            self.unlock();
            const cleaned = cleanupUnpublishedAdbSession(session);
            if (!cleaned) self.terminateAdbServer();
            self.enqueueReserved(.media_state_changed, request.request_id, .internal_error, "{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"video_worker_start_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"video_worker_start_failed\"}");
            return;
        };
        var audio_worker: ?std.Thread = null;
        if (session.audio_stream != null) {
            audio_worker = std.Thread.spawn(.{}, Handle.adbAudioThreadMain, .{ self, session }) catch null;
            if (audio_worker == null) session.stopAudio();
        }
        std.debug.assert(self.adb_runtime.session.publish(session));
        self.adb_runtime.video_worker = video_worker;
        self.adb_runtime.audio_worker = audio_worker;
        const audio_active = session.audio_stream != null and audio_worker != null;
        self.media_lifecycle = .adb_active;
        self.unlock();
        starting_owned = false;
        self.enqueueReserved(
            .media_state_changed,
            request.request_id,
            .ok,
            if (audio_active)
                "{\"state\":\"streaming\",\"backend\":\"adb_scrcpy\"}"
            else
                "{\"state\":\"video_only\",\"backend\":\"adb_scrcpy\",\"reason\":\"audio_unavailable\"}",
        );
        self.enqueueReserved(.request_complete, request.request_id, .ok, "{\"status\":\"adb_media_started\"}");
    }

    fn enqueueAdbMediaFailure(self: *Handle, request_id: u64, status: Result, reason: []const u8, diagnostic: []const u8) void {
        var payload_buffer: [max_event_payload]u8 = undefined;
        var writer: std.Io.Writer = .fixed(&payload_buffer);
        const diagnostic_count = @min(diagnostic.len, 384);
        const diagnostic_tail = diagnostic[diagnostic.len - diagnostic_count ..];
        const safe_diagnostic = if (std.unicode.utf8ValidateSlice(diagnostic_tail)) diagnostic_tail else "non_utf8_process_output";
        std.json.Stringify.value(.{
            .state = if (status == .cancelled) "stopped" else "failed",
            .backend = "adb_scrcpy",
            .reason = reason,
            .diagnostic = safe_diagnostic,
        }, .{}, &writer) catch return self.enqueueReserved(
            .media_state_changed,
            request_id,
            status,
            "{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"diagnostic_encode_failed\"}",
        );
        self.enqueueReserved(.media_state_changed, request_id, status, writer.buffered());
    }

    fn processAdbMediaStop(self: *Handle, request: AdbRequest) void {
        self.enqueueReserved(.adb_state_changed, request.request_id, .ok, "{\"state\":\"stopped\"}");
        if (self.stopAdbMedia()) {
            self.enqueueReserved(.media_state_changed, request.request_id, .ok, "{\"state\":\"stopped\",\"backend\":\"adb_scrcpy\"}");
            self.enqueueReserved(.request_complete, request.request_id, .ok, "{\"status\":\"adb_media_stopped\"}");
        } else {
            self.terminateAdbServer();
            self.enqueueReserved(.media_state_changed, request.request_id, .io_error, "{\"state\":\"failed\",\"backend\":\"adb_scrcpy\",\"reason\":\"forward_cleanup_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"forward_cleanup_failed\"}");
        }
    }

    fn processAdbDisable(self: *Handle, request: AdbRequest) void {
        self.stopAdbProcesses();
        self.enqueueReserved(.adb_state_changed, request.request_id, .ok, "{\"state\":\"disabled\"}");
        self.enqueueReserved(.request_complete, request.request_id, .ok, "{\"status\":\"adb_disabled\"}");
        while (true) {
            self.lock();
            const queued = self.popAdbRequestLocked();
            self.unlock();
            if (queued == null) break;
            if (queued.?.kind == .disable) {
                self.enqueueReserved(.adb_state_changed, queued.?.request_id, .ok, "{\"state\":\"disabled\"}");
                self.enqueueReserved(.request_complete, queued.?.request_id, .ok, "{\"status\":\"adb_disabled\"}");
            } else {
                self.cancelAdbRequest(queued.?);
            }
        }
        self.adb_cancel_requested.store(false, .release);
    }

    fn stopAdbProcesses(self: *Handle) void {
        _ = self.stopAdbMedia();
        self.terminateAdbServer();
    }

    fn terminateAdbServer(self: *Handle) void {
        self.lock();
        var server = self.adb_runtime.server;
        self.adb_runtime.server = null;
        self.adb_path_len = 0;
        self.unlock();
        if (server) |*running_server| running_server.deinit();
    }

    fn adbSupervisor(self: *Handle) process_supervisor.ProcessSupervisor {
        return process_supervisor.ProcessSupervisor.init(self.allocator, self.io()).withCancel(&self.adb_cancel_requested);
    }

    fn adbVideoThreadMain(self: *Handle, session: *scrcpy_manager.Session) void {
        const payload = self.allocator.alloc(u8, media_transport.max_packet_size) catch return self.adbVideoFailed();
        defer self.allocator.free(payload);
        const normalized = self.allocator.alloc(u8, media_transport.max_packet_size) catch return self.adbVideoFailed();
        defer self.allocator.free(normalized);
        while (true) {
            self.lock();
            const stopping = self.adb_runtime.media_stop_requested or self.adb_stop_requested or self.stop_requested;
            self.unlock();
            if (stopping) return;
            const packet = session.readVideo(payload, normalized) catch return self.adbVideoFailed();
            self.lock();
            self.media_queue.push(packet.header, packet.payload) catch |err| switch (err) {
                error.DroppedIncomingVideo => {},
                else => {
                    self.unlock();
                    return self.adbVideoFailed();
                },
            };
            self.unlock();
        }
    }

    fn adbAudioThreadMain(self: *Handle, session: *scrcpy_manager.Session) void {
        const payload = self.allocator.alloc(u8, media_transport.max_packet_size) catch return self.adbAudioFailed();
        defer self.allocator.free(payload);
        while (true) {
            self.lock();
            const stopping = self.adb_runtime.media_stop_requested or self.adb_stop_requested or self.stop_requested;
            self.unlock();
            if (stopping) return;
            const packet = session.readAudio(payload) catch return self.adbAudioFailed();
            self.lock();
            self.media_queue.push(packet.header, packet.payload) catch {
                self.unlock();
                return self.adbAudioFailed();
            };
            self.unlock();
        }
    }

    fn adbVideoFailed(self: *Handle) void {
        self.lock();
        self.adb_runtime.video_failed = true;
        self.adb_available.signal(self.io());
        self.unlock();
    }

    fn adbAudioFailed(self: *Handle) void {
        self.lock();
        self.adb_runtime.audio_failed = true;
        self.adb_available.signal(self.io());
        self.unlock();
    }

    fn stopAdbMedia(self: *Handle) bool {
        self.lockAdbMedia();
        defer self.unlockAdbMedia();
        self.lock();
        if (self.media_lifecycle == .adb_starting or self.media_lifecycle == .adb_active) self.media_lifecycle = .stopping;
        self.adb_runtime.media_stop_requested = true;
        const session = self.adb_runtime.session.take();
        const video_worker = self.adb_runtime.video_worker;
        const audio_worker = self.adb_runtime.audio_worker;
        self.adb_runtime.video_worker = null;
        self.adb_runtime.audio_worker = null;
        self.adb_runtime.video_failed = false;
        self.adb_runtime.audio_failed = false;
        self.unlock();
        var cleanup_ok = true;
        const cleanup_deadline = if (session) |active| active.beginStop() else null;
        if (video_worker) |thread| thread.join();
        if (audio_worker) |thread| thread.join();
        if (session) |active| {
            if (cleanup_deadline) |deadline| active.finishStop(deadline) catch {
                cleanup_ok = false;
            };
            active.destroy();
        }
        self.lock();
        self.media_queue.deinit();
        self.media_queue = media_transport.PacketQueue.init(self.allocator);
        if (self.media_lifecycle == .stopping) self.media_lifecycle = .none;
        self.unlock();
        return cleanup_ok;
    }

    fn cleanupUnpublishedAdbSession(session: *scrcpy_manager.Session) bool {
        var cleanup_ok = true;
        if (session.beginStop()) |deadline| {
            session.finishStop(deadline) catch {
                cleanup_ok = false;
            };
        }
        session.destroy();
        return cleanup_ok;
    }

    fn popRequestLocked(self: *Handle) ?Request {
        if (self.request_count == 0) return null;
        const request = self.requests[self.request_head];
        std.crypto.secureZero(u8, &self.requests[self.request_head].argument);
        self.request_head = (self.request_head + 1) % self.requests.len;
        self.request_count -= 1;
        return request;
    }

    fn enqueueReserved(self: *Handle, event_type: EventType, request_id: u64, status: Result, payload: []const u8) void {
        self.lock();
        defer self.unlock();
        std.debug.assert(self.reserved_events > 0);
        std.debug.assert(self.event_count < self.events.len);
        self.reserved_events -= 1;
        self.enqueueLocked(event_type, request_id, status, payload) catch {
            self.enqueueLocked(.error_event, request_id, .internal_error, "{\"error\":\"event_payload_too_large\"}") catch unreachable;
        };
    }

    fn enqueueUnreserved(self: *Handle, event_type: EventType, request_id: u64, status: Result, payload: []const u8) Result {
        self.lock();
        defer self.unlock();
        if (self.event_count + self.reserved_events + 1 >= self.events.len) return .queue_full;
        self.enqueueLocked(event_type, request_id, status, payload) catch return .internal_error;
        return .ok;
    }

    fn enqueueLocked(self: *Handle, event_type: EventType, request_id: u64, status: Result, payload: []const u8) !void {
        if (self.event_count == self.events.len) return error.EventQueueFull;
        const index = (self.event_head + self.event_count) % self.events.len;
        var event = QueuedEvent{ .event_type = event_type, .request_id = request_id, .status = status };
        if (event_type == .media_state_changed and std.mem.indexOf(u8, payload, "\"backend\":") == null) {
            if (payload.len == 0 or payload[payload.len - 1] != '}') return error.InvalidMediaEventPayload;
            var normalized: [max_event_payload]u8 = undefined;
            const with_backend = try std.fmt.bufPrint(
                &normalized,
                "{s},\"backend\":\"apk_tvrm\"}}",
                .{payload[0 .. payload.len - 1]},
            );
            try event.setPayload(with_backend);
        } else {
            try event.setPayload(payload);
        }
        self.events[index] = event;
        self.event_count += 1;
    }

    fn workerMain(self: *Handle) void {
        while (true) {
            self.lock();
            while (self.request_count == 0 and !self.stop_requested and self.state != .connected) {
                self.request_available.waitUncancelable(self.io(), &self.mutex);
            }
            if (self.request_count == 0 and self.stop_requested) {
                self.unlock();
                return;
            }
            if (self.request_count == 0 and self.state == .connected) {
                self.unlock();
                self.pollConnected();
                continue;
            }
            var request = self.popRequestLocked().?;
            defer std.crypto.secureZero(u8, &request.argument);
            const cancelling = self.stop_requested;
            if (!cancelling and (request.kind == .pair_submit or request.kind == .connect)) {
                self.control.prepareNetwork();
            }
            self.unlock();
            if (cancelling) {
                self.cancelRequest(request);
            } else {
                self.processRequest(request);
            }
        }
    }

    fn cancelRequest(self: *Handle, request: Request) void {
        const count = eventReservation(request.kind);
        for (0..count) |index| {
            self.enqueueReserved(
                if (index + 1 == count) .request_complete else .state_changed,
                request.request_id,
                .cancelled,
                "{\"status\":\"cancelled\"}",
            );
        }
    }

    fn processRequest(self: *Handle, request: Request) void {
        switch (request.kind) {
            .discover => self.processDiscovery(request),
            .pair_submit => self.processPair(request),
            .connect => self.processConnect(request),
            .disconnect => self.processDisconnect(request),
            .send_key => self.processSendKey(request),
            .media_start => self.processMediaStart(request),
            .media_stop => self.processMediaStop(request),
        }
    }

    fn processDiscovery(self: *Handle, request: Request) void {
        self.setState(.discovering);
        self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"discovering\"}");
        const found = lan_discovery.discoverMany(
            self.allocator,
            self.io(),
            .{ .raw = .fromMilliseconds(700), .clock = .awake },
        ) catch {
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"discovery_transport_failed\"}");
            self.setState(.idle);
            self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
            self.consumeUnusedReservations(lan_discovery.max_devices);
            return;
        };
        for (found.slice(), 0..) |device, device_index| {
            var address_buffer: [96]u8 = undefined;
            var address_writer: std.Io.Writer = .fixed(&address_buffer);
            device.source.format(&address_writer) catch {
                self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"address_format_failed\"}");
                self.setState(.idle);
                self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
                self.consumeUnusedReservations(lan_discovery.max_devices - device_index);
                return;
            };
            if (device_index == 0) self.control.setTarget(address_writer.buffered()) catch {
                self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"discovered_target_invalid\"}");
                self.setState(.idle);
                self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
                self.consumeUnusedReservations(lan_discovery.max_devices - device_index);
                return;
            };
            var payload_buffer: [max_event_payload]u8 = undefined;
            var payload_writer: std.Io.Writer = .fixed(&payload_buffer);
            std.json.Stringify.value(.{
                .sourceAddress = address_writer.buffered(),
                .instanceId = &device.response.instance_id,
                .displayName = device.response.displayName(),
                .controlPort = device.response.control_port,
            }, .{}, &payload_writer) catch {
                self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"device_event_encode_failed\"}");
                self.setState(.idle);
                self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
                self.consumeUnusedReservations(lan_discovery.max_devices - device_index);
                return;
            };
            self.enqueueReserved(.device_found, request.request_id, .ok, payload_writer.buffered());
        }
        self.consumeUnusedReservations(lan_discovery.max_devices - found.count);
        var complete_buffer: [96]u8 = undefined;
        const complete_payload = std.fmt.bufPrint(&complete_buffer, "{{\"status\":\"complete\",\"devicesFound\":{d}}}", .{found.count}) catch
            "{\"error\":\"discovery_result_encode_failed\"}";
        self.enqueueReserved(.request_complete, request.request_id, .ok, complete_payload);
        self.setState(.idle);
        self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
    }

    fn processPair(self: *Handle, request: Request) void {
        self.setState(.pairing);
        self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"pairing\"}");
        const details = self.control.beginPair(request.request_id, request.argumentSlice()) catch |err| {
            self.enqueueReserved(.error_event, request.request_id, requestResult(err), requestErrorPayload(err));
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            self.setState(.idle);
            self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
            return;
        };
        var sas_payload: [256]u8 = undefined;
        var sas_writer: std.Io.Writer = .fixed(&sas_payload);
        std.json.Stringify.value(.{
            .sas = &details.sas,
            .pairingId = &details.pairing_id,
            .expiresInMs = details.expires_in_ms,
        }, .{}, &sas_writer) catch {
            self.control.dropConnection();
            self.enqueueReserved(.error_event, request.request_id, .internal_error, "{\"error\":\"pairing_event_encode_failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"pairing_event_encode_failed\"}");
            self.setState(.idle);
            self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
            return;
        };
        self.enqueueReserved(.pairing_sas, request.request_id, .ok, sas_writer.buffered());
        self.control.finishPair(request.request_id) catch |err| {
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            self.setState(.idle);
            self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
            return;
        };
        self.enqueueReserved(.request_complete, request.request_id, .ok, "{\"status\":\"paired\"}");
        self.setState(.idle);
        self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
    }

    fn processConnect(self: *Handle, request: Request) void {
        self.setState(.connecting);
        self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"connecting\"}");
        if (request.argument_len != 0) self.control.setTarget(request.argumentSlice()) catch |err| {
            self.enqueueReserved(.error_event, request.request_id, requestResult(err), requestErrorPayload(err));
            self.setState(.idle);
            self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            return;
        };
        self.control.connect(request.request_id) catch |err| {
            self.enqueueReserved(.error_event, request.request_id, requestResult(err), requestErrorPayload(err));
            self.setState(.idle);
            self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            return;
        };
        var capability_payload: [max_event_payload]u8 = undefined;
        const capabilities = self.control.capabilities.writeJson(&capability_payload) catch {
            self.control.dropConnection();
            self.enqueueReserved(.error_event, request.request_id, .internal_error, "{\"error\":\"capability_event_encode_failed\"}");
            self.setState(.idle);
            self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
            self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"capability_event_encode_failed\"}");
            return;
        };
        self.enqueueReserved(.capabilities_changed, request.request_id, .ok, capabilities);
        self.setState(.connected);
        self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"connected\"}");
        self.enqueueReserved(.request_complete, request.request_id, .ok, "{\"status\":\"connected\"}");
    }

    fn processDisconnect(self: *Handle, request: Request) void {
        // 与 processSendKey 相同：state 必须在锁内读取，避免与 setState 竞争。
        self.lock();
        const connected = self.state == .connected;
        self.unlock();
        if (!connected) {
            self.enqueueReserved(.request_complete, request.request_id, .invalid_state, "{\"error\":\"no_active_connection\"}");
            self.setState(.idle);
            self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
            return;
        }
        self.stopMediaTransport();
        _ = self.stopAdbMedia();
        self.control.disconnect(request.request_id) catch |err| {
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            self.setState(.idle);
            self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
            return;
        };
        self.enqueueReserved(.request_complete, request.request_id, .ok, "{\"status\":\"disconnected\"}");
        self.setState(.idle);
        self.enqueueReserved(.state_changed, request.request_id, .ok, "{\"state\":\"idle\"}");
    }

    fn processSendKey(self: *Handle, request: Request) void {
        // state 由 setState 在锁内写入，worker 与外部 API 线程均可能更新，
        // 必须在锁内读取（与 processMediaStart 保持一致）。
        self.lock();
        const connected = self.state == .connected;
        self.unlock();
        if (!connected) {
            self.enqueueReserved(.error_event, request.request_id, .unauthenticated, "{\"error\":\"no_authenticated_control_session\"}");
            return;
        }
        const ack = self.control.sendKey(
            request.request_id,
            @enumFromInt(request.key),
            @enumFromInt(request.key_state),
        ) catch |err| {
            self.enqueueReserved(.error_event, request.request_id, requestResult(err), requestErrorPayload(err));
            if (connectionError(err)) self.connectionLost(err);
            return;
        };
        var ack_payload: [512]u8 = undefined;
        const payload = ack.writeJson(&ack_payload) catch {
            self.enqueueReserved(.error_event, request.request_id, .internal_error, "{\"error\":\"command_ack_encode_failed\"}");
            return;
        };
        self.enqueueReserved(.command_ack, request.request_id, ackResult(ack.status), payload);
    }

    fn processMediaStart(self: *Handle, request: Request) void {
        self.lock();
        const can_start = self.state == .connected and self.media_lifecycle == .none;
        if (can_start) self.media_lifecycle = .apk_starting;
        self.unlock();
        if (!can_start) {
            self.enqueueReserved(.media_state_changed, request.request_id, .invalid_state, "{\"state\":\"failed\",\"backend\":\"apk_tvrm\",\"reason\":\"media_busy\"}");
            self.enqueueReserved(.request_complete, request.request_id, .invalid_state, "{\"error\":\"media_busy\"}");
            return;
        }
        var starting_owned = true;
        defer if (starting_owned) {
            self.lock();
            if (self.media_lifecycle == .apk_starting) self.media_lifecycle = .none;
            self.unlock();
        };
        const offer = self.control.startMedia(request.request_id) catch |err| {
            self.enqueueReserved(.media_state_changed, request.request_id, requestResult(err), "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            return;
        };
        const info = self.control.mediaConnectionInfo() catch |err| {
            self.enqueueReserved(.media_state_changed, request.request_id, requestResult(err), "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            return;
        };
        self.media_tls.prepare();
        self.media_tls.connectPinned(.{
            .host = info.hostSlice(),
            .port = info.port,
            .read_timeout_ms = 1_000,
        }, &info.fingerprint) catch |err| {
            _ = self.control.stopMedia(request.request_id) catch {};
            self.enqueueReserved(.media_state_changed, request.request_id, requestResult(err), "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            return;
        };
        var request_id_buffer: [64]u8 = undefined;
        const attach_id = std.fmt.bufPrint(&request_id_buffer, "media-attach-{d}", .{request.request_id}) catch {
            self.media_tls.close();
            self.enqueueReserved(.media_state_changed, request.request_id, .internal_error, "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"media_attach_id_failed\"}");
            return;
        };
        var frame_buffer: [@import("transport_protocol.zig").max_frame_size + @import("transport_protocol.zig").frame_header_size]u8 = undefined;
        const frame = control_protocol.encodeFrame(&frame_buffer, attach_id, info.sessionId(), 1, "media_attach", .{
            .token = &offer.token,
        }) catch {
            self.media_tls.close();
            self.enqueueReserved(.media_state_changed, request.request_id, .internal_error, "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"media_attach_encode_failed\"}");
            return;
        };
        self.media_tls.write(frame) catch |err| {
            self.media_tls.close();
            self.enqueueReserved(.media_state_changed, request.request_id, requestResult(err), "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            return;
        };
        var decoder = @import("transport_protocol.zig").FrameDecoder{};
        while (decoder.peek() catch null == null) {
            var chunk: [4096]u8 = undefined;
            const count = self.media_tls.read(&chunk) catch |err| {
                self.media_tls.close();
                self.enqueueReserved(.media_state_changed, request.request_id, requestResult(err), "{\"state\":\"failed\"}");
                self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
                return;
            };
            decoder.append(chunk[0..count]) catch {
                self.media_tls.close();
                self.enqueueReserved(.media_state_changed, request.request_id, .io_error, "{\"state\":\"failed\"}");
                self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"media_attach_frame_invalid\"}");
                return;
            };
        }
        const payload = (decoder.peek() catch null) orelse unreachable;
        var acknowledgement = control_protocol.decode(self.allocator, payload) catch {
            self.media_tls.close();
            self.enqueueReserved(.media_state_changed, request.request_id, .io_error, "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"media_attach_ack_invalid\"}");
            return;
        };
        defer acknowledgement.deinit();
        if (acknowledgement.sequence != 1 or !std.mem.eql(u8, acknowledgement.request_id, attach_id) or
            !std.mem.eql(u8, acknowledgement.session_id, info.sessionId()) or
            !std.mem.eql(u8, acknowledgement.message_type, "media_attach_ack"))
        {
            self.media_tls.close();
            self.enqueueReserved(.media_state_changed, request.request_id, .io_error, "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"media_attach_ack_mismatch\"}");
            return;
        }
        acknowledgement.requirePayloadFields(&.{}, &.{}) catch {
            self.media_tls.close();
            self.enqueueReserved(.media_state_changed, request.request_id, .io_error, "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .io_error, "{\"error\":\"media_attach_ack_invalid\"}");
            return;
        };
        decoder.consume() catch unreachable;
        self.media_leftover_len = decoder.copyBuffered(&self.media_leftover) catch 0;
        self.media_leftover_offset = 0;
        self.media_sequences = @splat(0);
        self.media_config_ids = @splat(0);
        self.media_stop_requested = false;
        self.media_worker = std.Thread.spawn(.{}, Handle.mediaThreadMain, .{self}) catch {
            self.media_tls.close();
            self.enqueueReserved(.media_state_changed, request.request_id, .internal_error, "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, .internal_error, "{\"error\":\"media_thread_start_failed\"}");
            return;
        };
        self.lock();
        self.media_lifecycle = .apk_active;
        self.unlock();
        starting_owned = false;
        self.enqueueReserved(.media_state_changed, request.request_id, .ok, "{\"state\":\"waiting_tv_authorization\",\"backend\":\"apk_tvrm\"}");
        self.enqueueReserved(.request_complete, request.request_id, .ok, "{\"status\":\"media_attached\"}");
    }

    fn processMediaStop(self: *Handle, request: Request) void {
        self.stopMediaTransport();
        self.control.stopMedia(request.request_id) catch |err| {
            self.enqueueReserved(.media_state_changed, request.request_id, requestResult(err), "{\"state\":\"failed\"}");
            self.enqueueReserved(.request_complete, request.request_id, requestResult(err), requestErrorPayload(err));
            return;
        };
        self.enqueueReserved(.media_state_changed, request.request_id, .ok, "{\"state\":\"stopped\",\"backend\":\"apk_tvrm\"}");
        self.enqueueReserved(.request_complete, request.request_id, .ok, "{\"status\":\"media_stopped\"}");
    }

    fn mediaThreadMain(self: *Handle) void {
        var failed = false;
        while (true) {
            self.lock();
            const stopping = self.media_stop_requested or self.stop_requested;
            self.unlock();
            if (stopping) break;
            var header_bytes: [media_transport.header_size]u8 = undefined;
            self.readMediaExact(&header_bytes) catch {
                failed = true;
                break;
            };
            const header = media_transport.Header.decode(&header_bytes) catch {
                failed = true;
                break;
            };
            media_transport.validateStreamHeader(header, &self.media_sequences, &self.media_config_ids) catch {
                failed = true;
                break;
            };
            const packet_payload = self.allocator.alloc(u8, header.payload_len) catch {
                failed = true;
                break;
            };
            defer self.allocator.free(packet_payload);
            self.readMediaExact(packet_payload) catch {
                failed = true;
                break;
            };
            self.lock();
            self.media_queue.push(header, packet_payload) catch |err| switch (err) {
                error.DroppedIncomingVideo => {},
                else => failed = true,
            };
            self.unlock();
            if (failed) break;
        }
        self.media_tls.close();
        if (failed) {
            self.lock();
            self.media_queue.deinit();
            self.media_queue = media_transport.PacketQueue.init(self.allocator);
            self.unlock();
            _ = self.enqueueUnreserved(.media_state_changed, 0, .io_error, "{\"state\":\"failed\",\"backend\":\"apk_tvrm\"}");
        }
    }

    fn readMediaExact(self: *Handle, output: []u8) !void {
        var offset: usize = 0;
        while (offset < output.len) {
            if (self.media_leftover_offset < self.media_leftover_len) {
                const count = @min(output.len - offset, self.media_leftover_len - self.media_leftover_offset);
                @memcpy(output[offset..][0..count], self.media_leftover[self.media_leftover_offset..][0..count]);
                self.media_leftover_offset += count;
                offset += count;
                continue;
            }
            const count = self.media_tls.read(output[offset..]) catch |err| switch (err) {
                error.Timeout => continue,
                else => return err,
            };
            offset += count;
        }
    }

    fn stopMediaTransport(self: *Handle) void {
        self.lock();
        if (self.media_lifecycle == .apk_starting or self.media_lifecycle == .apk_active) self.media_lifecycle = .stopping;
        self.media_stop_requested = true;
        self.media_tls.cancel();
        const worker = self.media_worker;
        self.media_worker = null;
        self.unlock();
        if (worker) |thread| thread.join();
        self.media_tls.close();
        self.lock();
        self.media_queue.deinit();
        self.media_queue = media_transport.PacketQueue.init(self.allocator);
        self.media_leftover_len = 0;
        self.media_leftover_offset = 0;
        self.media_sequences = @splat(0);
        self.media_config_ids = @splat(0);
        self.media_stop_requested = false;
        if (self.media_lifecycle == .stopping) self.media_lifecycle = .none;
        self.unlock();
    }

    fn pollConnected(self: *Handle) void {
        const result = self.control.poll() catch |err| {
            self.lock();
            const stopping = self.stop_requested;
            self.unlock();
            if (!stopping) self.connectionLost(err);
            return;
        };
        if (result == .capabilities_changed) {
            var payload_buffer: [max_event_payload]u8 = undefined;
            const payload = self.control.capabilities.writeJson(&payload_buffer) catch return;
            _ = self.enqueueUnreserved(.capabilities_changed, 0, .ok, payload);
        } else if (result == .media_state_changed) {
            var payload_buffer: [96]u8 = undefined;
            const payload = std.fmt.bufPrint(&payload_buffer, "{{\"state\":\"{s}\"}}", .{self.control.mediaState()}) catch return;
            _ = self.enqueueUnreserved(.media_state_changed, 0, .ok, payload);
        }
    }

    fn connectionLost(self: *Handle, err: anyerror) void {
        // Invalidate authentication before waiting for ADB teardown so a
        // concurrent Session.start result cannot be published afterward.
        self.setState(.idle);
        self.adb_cancel_event.set(self.io());
        self.stopMediaTransport();
        _ = self.stopAdbMedia();
        self.control.dropConnection();
        _ = self.enqueueUnreserved(.state_changed, 0, requestResult(err), "{\"state\":\"idle\",\"reason\":\"connection_lost\"}");
    }

    fn setState(self: *Handle, state: platform.ConnectionState) void {
        self.lock();
        self.state = state;
        self.unlock();
    }

    fn consumeUnusedReservation(self: *Handle) void {
        self.lock();
        defer self.unlock();
        std.debug.assert(self.reserved_events > 0);
        self.reserved_events -= 1;
    }

    fn consumeUnusedReservations(self: *Handle, count: usize) void {
        for (0..count) |_| self.consumeUnusedReservation();
    }
};

pub export fn tvrc_config_init(config: ?*Config) void {
    const output = config orelse return;
    const store = credential_store.Adapter.platformDefault();
    output.* = .{
        .struct_size = @sizeOf(Config),
        .abi_version = abi_version,
        .request_queue_capacity = default_request_capacity,
        .event_queue_capacity = default_event_capacity,
        .flags = 0,
        .reserved = 0,
        .controller_name = default_controller_name.ptr,
        .controller_name_len = default_controller_name.len,
        .controller_name_reserved = 0,
        .credential_context = store.context,
        .credentials_put = store.put_fn,
        .credentials_get = store.get_fn,
        .credentials_remove = store.remove_fn,
    };
}

pub export fn tvrc_event_init(event: ?*Event) void {
    const output = event orelse return;
    output.* = .{
        .struct_size = @sizeOf(Event),
        .abi_version = abi_version,
        .event_type = 0,
        .status = 0,
        .request_id = 0,
        .payload_len = 0,
        .reserved = 0,
    };
}

pub export fn tvrc_media_packet_init(packet: ?*MediaPacket) void {
    const output = packet orelse return;
    output.* = std.mem.zeroes(MediaPacket);
    output.struct_size = @sizeOf(MediaPacket);
    output.abi_version = abi_version;
}

pub export fn tvrc_create(config_pointer: ?*const Config, output: ?*?*anyopaque) Result {
    const config = config_pointer orelse return .invalid_argument;
    const destination = output orelse return .invalid_argument;
    destination.* = null;
    if (!validConfig(config)) return .invalid_argument;

    const allocator = std.heap.page_allocator;
    const handle = allocator.create(Handle) catch return .internal_error;
    errdefer allocator.destroy(handle);
    const requests = allocator.alloc(Request, config.request_queue_capacity) catch return .internal_error;
    errdefer allocator.free(requests);
    const events = allocator.alloc(QueuedEvent, config.event_queue_capacity) catch return .internal_error;
    errdefer allocator.free(events);
    var io_backend = std.Io.Threaded.init(allocator, .{});
    errdefer io_backend.deinit();
    const configured_adapter = credential_store.Adapter{
        .context = config.credential_context,
        .put_fn = config.credentials_put,
        .get_fn = config.credentials_get,
        .remove_fn = config.credentials_remove,
    };
    configured_adapter.validate() catch return .invalid_argument;
    const configured_name = inputSlice(config.controller_name, config.controller_name_len) orelse return .invalid_argument;
    var control = control_client.Client.init(allocator, io_backend.io(), configured_adapter, configured_name) catch return .invalid_argument;
    errdefer control.deinit();
    var media_tls = @import("tls_transport.zig").Client.init() catch return .internal_error;
    errdefer media_tls.deinit();
    handle.* = .{
        .allocator = allocator,
        .io_backend = io_backend,
        .requests = requests,
        .events = events,
        .credentials = configured_adapter,
        .control = control,
        .media_queue = media_transport.PacketQueue.init(allocator),
        .media_tls = media_tls,
    };
    destination.* = handle;
    return .ok;
}

pub export fn tvrc_destroy(raw: ?*anyopaque) void {
    const handle = cast(raw) orelse return;
    _ = tvrc_stop(handle);
    handle.control.deinit();
    handle.media_tls.deinit();
    handle.media_queue.deinit();
    handle.allocator.free(handle.requests);
    handle.allocator.free(handle.events);
    handle.io_backend.deinit();
    const allocator = handle.allocator;
    allocator.destroy(handle);
}

pub export fn tvrc_start(raw: ?*anyopaque) Result {
    const handle = cast(raw) orelse return .invalid_argument;
    handle.lock();
    defer handle.unlock();
    if (handle.worker != null or handle.state != .stopped) return .invalid_state;
    if (handle.event_count + 1 >= handle.events.len) return .queue_full;
    handle.stop_requested = false;
    handle.state = .idle;
    handle.worker = std.Thread.spawn(.{}, Handle.workerMain, .{handle}) catch {
        handle.state = .stopped;
        return .internal_error;
    };
    if (builtin.os.tag == .windows) {
        handle.adb_stop_requested = false;
        handle.adb_worker = std.Thread.spawn(.{}, Handle.adbWorkerMain, .{handle}) catch {
            handle.stop_requested = true;
            handle.request_available.broadcast(handle.io());
            const worker = handle.worker;
            handle.worker = null;
            handle.unlock();
            if (worker) |thread| thread.join();
            handle.lock();
            handle.state = .stopped;
            return .internal_error;
        };
    }
    handle.enqueueLocked(.state_changed, 0, .ok, "{\"state\":\"idle\"}") catch {
        handle.stop_requested = true;
        handle.request_available.broadcast(handle.io());
        return .queue_full;
    };
    return .ok;
}

pub export fn tvrc_stop(raw: ?*anyopaque) Result {
    const handle = cast(raw) orelse return .invalid_argument;
    handle.lock();
    if (handle.worker == null and handle.state == .stopped) {
        handle.unlock();
        return .ok;
    }
    handle.stop_requested = true;
    handle.adb_stop_requested = true;
    handle.adb_cancel_requested.store(true, .release);
    handle.adb_cancel_event.set(handle.io());
    handle.control.cancel();
    handle.request_available.broadcast(handle.io());
    handle.adb_available.broadcast(handle.io());
    const worker = handle.worker;
    const adb_worker = handle.adb_worker;
    handle.worker = null;
    handle.adb_worker = null;
    handle.unlock();
    if (worker) |thread| thread.join();
    if (adb_worker) |thread| thread.join();
    handle.stopMediaTransport();
    handle.stopAdbProcesses();
    handle.control.dropConnection();
    handle.lock();
    handle.state = .stopped;
    handle.stop_requested = false;
    handle.adb_stop_requested = false;
    handle.enqueueLocked(.state_changed, 0, .ok, "{\"state\":\"stopped\"}") catch {
        handle.unlock();
        return .queue_full;
    };
    handle.unlock();
    return .ok;
}

pub export fn tvrc_discover(raw: ?*anyopaque, request_id: u64) Result {
    const handle = cast(raw) orelse return .invalid_argument;
    if (request_id == 0) return .invalid_argument;
    return handle.enqueueRequest(.{ .kind = .discover, .request_id = request_id });
}

pub export fn tvrc_pair_submit(raw: ?*anyopaque, request_id: u64, code: ?[*]const u8, code_len: u32) Result {
    const bytes = inputSlice(code, code_len) orelse return .invalid_argument;
    if (request_id == 0 or bytes.len != 6) return .invalid_argument;
    for (bytes) |byte| if (!std.ascii.isDigit(byte)) return .invalid_argument;
    var request = Request{ .kind = .pair_submit, .request_id = request_id, .argument_len = @intCast(bytes.len) };
    @memcpy(request.argument[0..bytes.len], bytes);
    return (cast(raw) orelse return .invalid_argument).enqueueRequest(request);
}

pub export fn tvrc_target_set(raw: ?*anyopaque, address: ?[*]const u8, address_len: u32) Result {
    const handle = cast(raw) orelse return .invalid_argument;
    const bytes = inputSlice(address, address_len) orelse return .invalid_argument;
    if (bytes.len == 0 or bytes.len > max_request_argument or !std.unicode.utf8ValidateSlice(bytes)) return .invalid_argument;
    handle.lock();
    defer handle.unlock();
    if (handle.state != .idle or handle.stop_requested or handle.request_count != 0) return .invalid_state;
    handle.control.setTarget(bytes) catch |err| return requestResult(err);
    return .ok;
}

pub export fn tvrc_connect(raw: ?*anyopaque, request_id: u64, address: ?[*]const u8, address_len: u32) Result {
    const bytes = inputSlice(address, address_len) orelse return .invalid_argument;
    if (request_id == 0 or bytes.len > max_request_argument or !std.unicode.utf8ValidateSlice(bytes)) return .invalid_argument;
    var request = Request{ .kind = .connect, .request_id = request_id, .argument_len = @intCast(bytes.len) };
    @memcpy(request.argument[0..bytes.len], bytes);
    return (cast(raw) orelse return .invalid_argument).enqueueRequest(request);
}

pub export fn tvrc_disconnect(raw: ?*anyopaque, request_id: u64) Result {
    if (request_id == 0) return .invalid_argument;
    return (cast(raw) orelse return .invalid_argument).enqueueRequest(.{ .kind = .disconnect, .request_id = request_id });
}

pub export fn tvrc_send_key(raw: ?*anyopaque, request_id: u64, key: u32, key_state: u32) Result {
    if (request_id == 0 or key > 17 or key_state > 3) return .invalid_argument;
    return (cast(raw) orelse return .invalid_argument).enqueueRequest(.{
        .kind = .send_key,
        .request_id = request_id,
        .key = key,
        .key_state = key_state,
    });
}

pub export fn tvrc_media_start(raw: ?*anyopaque, request_id: u64) Result {
    if (request_id == 0) return .invalid_argument;
    return (cast(raw) orelse return .invalid_argument).enqueueRequest(.{ .kind = .media_start, .request_id = request_id });
}

pub export fn tvrc_media_stop(raw: ?*anyopaque, request_id: u64) Result {
    if (request_id == 0) return .invalid_argument;
    return (cast(raw) orelse return .invalid_argument).enqueueRequest(.{ .kind = .media_stop, .request_id = request_id });
}

pub export fn tvrc_media_read(raw: ?*anyopaque, output_packet: ?*MediaPacket, payload: ?[*]u8, payload_capacity: u32) Result {
    const handle = cast(raw) orelse return .invalid_argument;
    const destination = output_packet orelse return .invalid_argument;
    if (destination.struct_size != @sizeOf(MediaPacket) or destination.abi_version != abi_version) return .invalid_argument;
    handle.lock();
    defer handle.unlock();
    const packet = handle.media_queue.peek() orelse return .not_found;
    destination.payload_len = packet.header.payload_len;
    if (packet.header.payload_len > payload_capacity or (packet.header.payload_len > 0 and payload == null)) return .buffer_too_small;
    const output: []u8 = if (payload) |pointer| pointer[0..payload_capacity] else &.{};
    const header = handle.media_queue.pop(output) catch |err| return switch (err) {
        error.BufferTooSmall => .buffer_too_small,
        else => .internal_error,
    };
    destination.* = .{
        .struct_size = @sizeOf(MediaPacket),
        .abi_version = abi_version,
        .track = @intFromEnum(header.track),
        .flags = @as(u16, @bitCast(header.flags)),
        .sequence = header.sequence,
        .codec_config_id = header.codec_config_id,
        .presentation_time_us = header.presentation_time_us,
        .payload_len = header.payload_len,
        .width = header.width,
        .height = header.height,
        .reserved = 0,
    };
    return .ok;
}

pub export fn tvrc_adb_probe(raw: ?*anyopaque, request_id: u64, saved_path: ?[*]const u8, saved_path_len: u32) Result {
    if (builtin.os.tag != .windows) return .unsupported;
    const path = inputSlice(saved_path, saved_path_len) orelse return .invalid_argument;
    if (request_id == 0 or path.len > max_adb_argument or !std.unicode.utf8ValidateSlice(path)) return .invalid_argument;
    if (path.len != 0 and !adb_manager.isSafeAdbExecutablePath(path)) return .invalid_argument;
    var request = AdbRequest{ .kind = .probe, .request_id = request_id, .argument_len = @intCast(path.len) };
    @memcpy(request.argument[0..path.len], path);
    return (cast(raw) orelse return .invalid_argument).enqueueAdbRequest(request);
}

pub export fn tvrc_adb_install(raw: ?*anyopaque, request_id: u64, flags: u32) Result {
    if (builtin.os.tag != .windows or builtin.cpu.arch == .x86) return .unsupported;
    if (request_id == 0 or (flags != 1 and flags != 2)) return .invalid_argument;
    return (cast(raw) orelse return .invalid_argument).enqueueAdbRequest(.{ .kind = .install, .request_id = request_id, .flags = flags });
}

pub export fn tvrc_adb_media_start(raw: ?*anyopaque, request_id: u64, serial: ?[*]const u8, serial_len: u32, flags: u32) Result {
    if (builtin.os.tag != .windows or builtin.cpu.arch == .x86) return .unsupported;
    const selected_serial = inputSlice(serial, serial_len) orelse return .invalid_argument;
    if (request_id == 0 or flags > 1 or selected_serial.len > max_adb_argument or !adb_manager.isSafeSerial(selected_serial)) return .invalid_argument;
    var request = AdbRequest{ .kind = .media_start, .request_id = request_id, .flags = flags, .argument_len = @intCast(selected_serial.len) };
    @memcpy(request.argument[0..selected_serial.len], selected_serial);
    return (cast(raw) orelse return .invalid_argument).enqueueAdbRequest(request);
}

pub export fn tvrc_adb_media_stop(raw: ?*anyopaque, request_id: u64) Result {
    if (builtin.os.tag != .windows or builtin.cpu.arch == .x86) return .unsupported;
    if (request_id == 0) return .invalid_argument;
    const handle = cast(raw) orelse return .invalid_argument;
    const result = handle.enqueueAdbRequest(.{ .kind = .media_stop, .request_id = request_id });
    if (result == .ok) {
        handle.adb_cancel_requested.store(true, .release);
        handle.adb_cancel_event.set(handle.io());
    }
    return result;
}

pub export fn tvrc_adb_disable(raw: ?*anyopaque, request_id: u64) Result {
    if (builtin.os.tag != .windows) return .unsupported;
    if (request_id == 0) return .invalid_argument;
    return (cast(raw) orelse return .invalid_argument).enqueueAdbDisable(.{ .kind = .disable, .request_id = request_id });
}

pub export fn tvrc_poll_event(raw: ?*anyopaque, output_event: ?*Event, payload: ?[*]u8, payload_capacity: u32) Result {
    const handle = cast(raw) orelse return .invalid_argument;
    const destination = output_event orelse return .invalid_argument;
    if (destination.struct_size != @sizeOf(Event) or destination.abi_version != abi_version) return .invalid_argument;
    handle.lock();
    defer handle.unlock();
    if (handle.event_count == 0) return .not_found;
    const queued = &handle.events[handle.event_head];
    destination.event_type = @intFromEnum(queued.event_type);
    destination.request_id = queued.request_id;
    destination.status = @intFromEnum(queued.status);
    destination.payload_len = queued.payload_len;
    destination.reserved = 0;
    if (queued.payload_len > payload_capacity or (queued.payload_len > 0 and payload == null)) return .buffer_too_small;
    if (queued.payload_len > 0) @memcpy(payload.?[0..queued.payload_len], queued.payload[0..queued.payload_len]);
    handle.event_head = (handle.event_head + 1) % handle.events.len;
    handle.event_count -= 1;
    return .ok;
}

pub export fn tvrc_credentials_put(raw: ?*anyopaque, credential_id: ?[*]const u8, credential_id_len: u32, secret: ?[*]const u8, secret_len: u32) Result {
    const handle = cast(raw) orelse return .invalid_argument;
    const id = inputSlice(credential_id, credential_id_len) orelse return .invalid_argument;
    const value = inputSlice(secret, secret_len) orelse return .invalid_argument;
    handle.credentials.put(id, value) catch |err| return credentialResult(err);
    return .ok;
}

pub export fn tvrc_credentials_get(raw: ?*anyopaque, credential_id: ?[*]const u8, credential_id_len: u32, secret: ?[*]u8, secret_capacity: u32, secret_len: ?*u32) Result {
    const handle = cast(raw) orelse return .invalid_argument;
    const id = inputSlice(credential_id, credential_id_len) orelse return .invalid_argument;
    const output_len = secret_len orelse return .invalid_argument;
    const callback = handle.credentials.get_fn orelse return .unsupported;
    const result = callback(handle.credentials.context, id.ptr, @intCast(id.len), secret, secret_capacity, output_len);
    return switch (result) {
        0 => .ok,
        3 => .unsupported,
        4 => .buffer_too_small,
        6 => .not_found,
        else => .io_error,
    };
}

pub export fn tvrc_credentials_remove(raw: ?*anyopaque, credential_id: ?[*]const u8, credential_id_len: u32) Result {
    const handle = cast(raw) orelse return .invalid_argument;
    const id = inputSlice(credential_id, credential_id_len) orelse return .invalid_argument;
    handle.credentials.remove(id) catch |err| return credentialResult(err);
    return .ok;
}

fn validConfig(config: *const Config) bool {
    if (config.struct_size != @sizeOf(Config) or config.abi_version != abi_version) return false;
    if (config.flags != 0 or config.reserved != 0 or config.controller_name_reserved != 0) return false;
    const name = inputSlice(config.controller_name, config.controller_name_len) orelse return false;
    if (name.len == 0 or name.len > 64 or !std.unicode.utf8ValidateSlice(name)) return false;
    if (config.request_queue_capacity == 0 or config.request_queue_capacity > max_request_capacity) return false;
    if (config.event_queue_capacity < 5 or config.event_queue_capacity > max_event_capacity) return false;
    const adapter = credential_store.Adapter{
        .context = config.credential_context,
        .put_fn = config.credentials_put,
        .get_fn = config.credentials_get,
        .remove_fn = config.credentials_remove,
    };
    adapter.validate() catch return false;
    return true;
}

fn eventReservation(kind: RequestKind) usize {
    return switch (kind) {
        .discover => lan_discovery.max_devices + 3,
        .pair_submit, .connect => 4,
        .disconnect => 2,
        .send_key => 1,
        .media_start, .media_stop => 2,
    };
}

fn adbEventReservation(kind: AdbRequestKind) usize {
    return switch (kind) {
        .probe => max_adb_devices + 3,
        .media_start, .media_stop => 3,
        .install, .disable => 2,
    };
}

fn cast(raw: ?*anyopaque) ?*Handle {
    const pointer = raw orelse return null;
    return @ptrCast(@alignCast(pointer));
}

fn pathExists(io: std.Io, path: []const u8) bool {
    std.Io.Dir.accessAbsolute(io, path, .{}) catch return false;
    return true;
}

fn inputSlice(pointer: ?[*]const u8, len: u32) ?[]const u8 {
    if (len == 0) return "";
    const value = pointer orelse return null;
    return value[0..len];
}

fn credentialResult(err: anyerror) Result {
    return switch (err) {
        error.InvalidCredentialId, error.InvalidSecret, error.InvalidArgument => .invalid_argument,
        error.CredentialStoreUnavailable => .unsupported,
        error.BufferTooSmall => .buffer_too_small,
        error.CredentialNotFound => .not_found,
        else => .io_error,
    };
}

fn requestResult(err: anyerror) Result {
    return switch (err) {
        error.InvalidTarget, error.InvalidRequestId, error.InvalidEnvelope => .invalid_argument,
        error.InvalidState, error.TargetRequired => .invalid_state,
        error.CredentialNotFound, error.AuthenticationFailed, error.InvalidSession => .unauthenticated,
        error.CredentialStoreUnavailable, error.UnsupportedKey, error.PairingRejected => .unsupported,
        error.BufferTooSmall => .buffer_too_small,
        error.EntropyUnavailable => .internal_error,
        else => .io_error,
    };
}

fn requestErrorPayload(err: anyerror) []const u8 {
    return switch (err) {
        error.InvalidTarget => "{\"error\":\"invalid_target_address\"}",
        error.TargetRequired => "{\"error\":\"target_required\"}",
        error.CredentialNotFound => "{\"error\":\"pairing_required\"}",
        error.CredentialStoreUnavailable => "{\"error\":\"credential_store_unavailable\"}",
        error.InvalidCredentialData => "{\"error\":\"stored_credential_invalid\"}",
        error.PairingSasMismatch => "{\"error\":\"pairing_sas_mismatch\"}",
        error.PairingRejected => "{\"error\":\"pairing_rejected\"}",
        error.AuthenticationFailed => "{\"error\":\"authentication_failed\"}",
        error.UnsupportedKey => "{\"error\":\"key_not_enabled_by_capabilities\"}",
        error.HeartbeatTimeout => "{\"error\":\"heartbeat_timeout\"}",
        error.ProtocolViolation, error.ReplayedSequence, error.InvalidSession => "{\"error\":\"control_protocol_violation\"}",
        error.InvalidState => "{\"error\":\"invalid_state\"}",
        else => "{\"error\":\"secure_transport_failed\"}",
    };
}

fn connectionError(err: anyerror) bool {
    return switch (err) {
        error.UnsupportedKey, error.InvalidState => false,
        else => true,
    };
}

fn ackResult(status: control_client.AckStatus) Result {
    return switch (status) {
        .success => .ok,
        .unsupported, .permission_denied, .mapping_missing => .unsupported,
        .rejected => .invalid_state,
        .execution_failed => .io_error,
    };
}

test "C ABI uses versioned fixed-width length fields" {
    try std.testing.expectEqual(@as(usize, 32), @sizeOf(Event));
    try std.testing.expectEqual(@as(usize, 16), @offsetOf(Event, "request_id"));
    try std.testing.expectEqual(@as(usize, 24), @offsetOf(Event, "payload_len"));
    try std.testing.expectEqual(@as(usize, 48), @sizeOf(MediaPacket));
    try std.testing.expectEqual(@as(usize, 24), @offsetOf(MediaPacket, "presentation_time_us"));
    try std.testing.expectEqual(@as(u32, 9), @intFromEnum(EventType.adb_state_changed));
    try std.testing.expectEqual(@as(u32, 10), @intFromEnum(EventType.adb_device_found));
    try std.testing.expectEqual(@as(usize, 32), max_adb_devices);
}

test "managed ADB distinguishes first install from damaged managed state" {
    try std.testing.expectEqual(ManagedInstallState.install_required, managedInstallState(false, false, false));
    try std.testing.expectEqual(ManagedInstallState.upgrade_required, managedInstallState(true, false, false));
    try std.testing.expectEqual(ManagedInstallState.upgrade_required, managedInstallState(true, true, false));
    try std.testing.expectEqual(ManagedInstallState.ready, managedInstallState(true, true, true));
}

test "ADB session ownership transfers exactly once" {
    var slot: OwnedSlot(u32) = .{};
    try std.testing.expect(slot.isEmpty());
    try std.testing.expect(slot.publish(7));
    try std.testing.expect(!slot.publish(8));
    try std.testing.expectEqual(@as(?u32, 7), slot.borrow());
    try std.testing.expectEqual(@as(?u32, 7), slot.take());
    try std.testing.expectEqual(@as(?u32, null), slot.take());
    try std.testing.expect(slot.isEmpty());
}

test "ADB session publication requires a live authenticated startup" {
    try std.testing.expect(canPublishAdbSession(.connected, .adb_starting, true, false));
    try std.testing.expect(!canPublishAdbSession(.idle, .adb_starting, true, false));
    try std.testing.expect(!canPublishAdbSession(.connected, .stopping, true, false));
    try std.testing.expect(!canPublishAdbSession(.connected, .adb_starting, false, false));
    try std.testing.expect(!canPublishAdbSession(.connected, .adb_starting, true, true));
}

test "C ABI lifecycle is idempotent and caller buffer remains owned" {
    var config: Config = undefined;
    tvrc_config_init(&config);
    var raw: ?*anyopaque = null;
    try std.testing.expectEqual(Result.ok, tvrc_create(&config, &raw));
    defer tvrc_destroy(raw);
    try std.testing.expectEqual(Result.ok, tvrc_start(raw));
    try std.testing.expectEqual(Result.ok, tvrc_media_start(raw, 42));

    var event: Event = undefined;
    tvrc_event_init(&event);
    var payload: [256]u8 = undefined;
    try std.testing.expectEqual(Result.ok, tvrc_poll_event(raw, &event, &payload, payload.len));
    try std.testing.expectEqual(@as(u32, @intFromEnum(EventType.state_changed)), event.event_type);
    while (tvrc_poll_event(raw, &event, &payload, payload.len) == .not_found) std.atomic.spinLoopHint();
    try std.testing.expectEqual(@as(u64, 42), event.request_id);
    try std.testing.expectEqual(@as(u32, @intFromEnum(EventType.media_state_changed)), event.event_type);
    try std.testing.expectEqual(Result.invalid_state, @as(Result, @enumFromInt(event.status)));
    try std.testing.expect(std.mem.indexOf(u8, payload[0..event.payload_len], "\"backend\":\"apk_tvrm\"") != null);
    while (tvrc_poll_event(raw, &event, &payload, payload.len) == .not_found) std.atomic.spinLoopHint();
    try std.testing.expectEqual(@as(u32, @intFromEnum(EventType.request_complete)), event.event_type);
    try std.testing.expectEqual(Result.invalid_state, @as(Result, @enumFromInt(event.status)));
    try std.testing.expectEqual(Result.ok, tvrc_stop(raw));
    try std.testing.expectEqual(Result.ok, tvrc_stop(raw));
}

test "non-Windows ADB ABI is explicitly unsupported" {
    if (builtin.os.tag == .windows) return;
    try std.testing.expectEqual(Result.unsupported, tvrc_adb_probe(null, 1, null, 0));
    try std.testing.expectEqual(Result.unsupported, tvrc_adb_install(null, 1, 1));
    try std.testing.expectEqual(Result.unsupported, tvrc_adb_media_start(null, 1, null, 0, 0));
    try std.testing.expectEqual(Result.unsupported, tvrc_adb_media_stop(null, 1));
    try std.testing.expectEqual(Result.unsupported, tvrc_adb_disable(null, 1));
}

test "event buffer-too-small does not consume an event" {
    var config: Config = undefined;
    tvrc_config_init(&config);
    var raw: ?*anyopaque = null;
    try std.testing.expectEqual(Result.ok, tvrc_create(&config, &raw));
    defer tvrc_destroy(raw);
    try std.testing.expectEqual(Result.ok, tvrc_start(raw));
    var event: Event = undefined;
    tvrc_event_init(&event);
    try std.testing.expectEqual(Result.buffer_too_small, tvrc_poll_event(raw, &event, null, 0));
    var payload: [64]u8 = undefined;
    try std.testing.expectEqual(Result.ok, tvrc_poll_event(raw, &event, &payload, payload.len));
    try std.testing.expectEqualStrings("{\"state\":\"idle\"}", payload[0..event.payload_len]);
}

test "empty media queue is not found rather than unsupported" {
    var config: Config = undefined;
    tvrc_config_init(&config);
    var raw: ?*anyopaque = null;
    try std.testing.expectEqual(Result.ok, tvrc_create(&config, &raw));
    defer tvrc_destroy(raw);
    var packet: MediaPacket = undefined;
    tvrc_media_packet_init(&packet);
    var payload: [1]u8 = undefined;
    try std.testing.expectEqual(Result.not_found, tvrc_media_read(raw, &packet, &payload, payload.len));
}
