const std = @import("std");
const builtin = @import("builtin");

pub const maximum_output_bytes: usize = 1024 * 1024;
pub const default_timeout_ms: u32 = 15_000;

pub const Containment = enum {
    windows_job_object,
    process_group,
};

pub fn containmentForHost(os: std.Target.Os.Tag) Containment {
    return if (os == .windows) .windows_job_object else .process_group;
}

pub const Termination = union(enum) {
    exited: u8,
    signal: u32,
    stopped: u32,
    unknown: u32,

    pub fn exitCode(self: Termination) ?u8 {
        return switch (self) {
            .exited => |code| code,
            else => null,
        };
    }

    pub fn succeeded(self: Termination) bool {
        return self.exitCode() == 0;
    }
};

pub const RunOptions = struct {
    argv: []const []const u8,
    timeout_ms: u32 = default_timeout_ms,
    output_limit: usize = maximum_output_bytes,
};

pub const StartOptions = struct {
    argv: []const []const u8,
};

pub const Result = struct {
    termination: Termination,
    stdout: []u8,
    stderr: []u8,

    pub fn deinit(self: *Result, allocator: std.mem.Allocator) void {
        allocator.free(self.stdout);
        allocator.free(self.stderr);
        self.* = undefined;
    }
};

/// Runs one bounded child process. Callers may cancel the surrounding Zig I/O
/// future; cancellation is propagated as `error.Canceled`. A timeout or any
/// early return terminates the complete child process tree.
pub const ProcessSupervisor = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    cancel_flag: ?*const std.atomic.Value(bool) = null,

    pub fn init(allocator: std.mem.Allocator, io: std.Io) ProcessSupervisor {
        return .{ .allocator = allocator, .io = io };
    }

    pub fn withCancel(self: ProcessSupervisor, cancel_flag: *const std.atomic.Value(bool)) ProcessSupervisor {
        var result = self;
        result.cancel_flag = cancel_flag;
        return result;
    }

    pub fn run(self: ProcessSupervisor, options: RunOptions) !Result {
        if (options.argv.len == 0) return error.EmptyArgumentVector;
        if (!isAbsoluteExecutablePath(options.argv[0])) return error.ExecutablePathMustBeAbsolute;
        if (options.timeout_ms == 0) return error.InvalidTimeout;
        if (options.output_limit == 0 or options.output_limit > maximum_output_bytes) {
            return error.InvalidOutputLimit;
        }

        var child = try std.process.spawn(self.io, .{
            .argv = options.argv,
            .stdin = .ignore,
            .stdout = .pipe,
            .stderr = .pipe,
            .pgid = if (builtin.os.tag == .windows) null else 0,
            .start_suspended = builtin.os.tag == .windows,
            .create_no_window = true,
        });
        errdefer child.kill(self.io);

        var process_tree = try ProcessTree.attach(&child);
        var running = true;
        defer {
            if (running) process_tree.terminate() catch |err| {
                std.log.warn("process tree termination failed: {s}", .{@errorName(err)});
            };
            process_tree.deinit();
            if (running) {
                std.log.warn("invoking child kill safety net after incomplete process-tree cleanup", .{});
                child.kill(self.io);
            }
        }

        try process_tree.resumeChild(&child);

        var multi_reader_buffer: std.Io.File.MultiReader.Buffer(2) = undefined;
        var multi_reader: std.Io.File.MultiReader = undefined;
        multi_reader.init(
            self.allocator,
            self.io,
            multi_reader_buffer.toStreams(),
            &.{ child.stdout.?, child.stderr.? },
        );
        defer multi_reader.deinit();

        const stdout_reader = multi_reader.reader(0);
        const stderr_reader = multi_reader.reader(1);
        const expires_ms = std.Io.Clock.boot.now(self.io).toMilliseconds() + options.timeout_ms;
        while (true) {
            if (self.cancel_flag) |flag| if (flag.load(.acquire)) return error.Canceled;
            const now_ms = std.Io.Clock.boot.now(self.io).toMilliseconds();
            if (now_ms >= expires_ms) return error.Timeout;
            const poll_ms: u32 = @intCast(@min(expires_ms - now_ms, 100));
            const timeout: std.Io.Timeout = .{ .deadline = .fromNow(self.io, .{
                .clock = .boot,
                .raw = .fromMilliseconds(poll_ms),
            }) };
            multi_reader.fill(64, timeout) catch |err| switch (err) {
                error.EndOfStream => break,
                error.Timeout => continue,
                error.Canceled => return error.Canceled,
                else => |unexpected| return unexpected,
            };
            if (stdout_reader.buffered().len + stderr_reader.buffered().len > options.output_limit) {
                return error.OutputLimitExceeded;
            }
        }
        try multi_reader.checkAnyError();
        if (self.cancel_flag) |flag| if (flag.load(.acquire)) return error.Canceled;

        const term = try child.wait(self.io);
        running = false;

        const stdout = try multi_reader.toOwnedSlice(0);
        errdefer self.allocator.free(stdout);
        const stderr = try multi_reader.toOwnedSlice(1);
        errdefer self.allocator.free(stderr);

        return .{
            .termination = normalizeTermination(term),
            .stdout = stdout,
            .stderr = stderr,
        };
    }

    /// Starts a long-running contained process and retains the combined 64 KiB
    /// stdout/stderr tail. `deinit` always reaps the full process tree.
    pub fn start(self: ProcessSupervisor, options: StartOptions) !ManagedProcess {
        if (options.argv.len == 0) return error.EmptyArgumentVector;
        if (!isAbsoluteExecutablePath(options.argv[0])) return error.ExecutablePathMustBeAbsolute;
        var child = try std.process.spawn(self.io, .{
            .argv = options.argv,
            .stdin = .ignore,
            .stdout = .pipe,
            .stderr = .pipe,
            .pgid = if (builtin.os.tag == .windows) null else 0,
            .start_suspended = builtin.os.tag == .windows,
            .create_no_window = true,
        });
        errdefer child.kill(self.io);
        var process_tree = try ProcessTree.attach(&child);
        errdefer process_tree.deinit();
        try process_tree.resumeChild(&child);
        const diagnostics = try self.allocator.create(ProcessDiagnostics);
        errdefer self.allocator.destroy(diagnostics);
        diagnostics.* = .{ .io = self.io };
        const stdout_file = child.stdout.?;
        child.stdout = null;
        const stderr_file = child.stderr.?;
        child.stderr = null;
        diagnostics.stdout_thread = try std.Thread.spawn(.{}, ProcessDiagnostics.drain, .{ diagnostics, stdout_file });
        errdefer {
            process_tree.terminate() catch |err| {
                std.log.warn("process tree termination failed during startup rollback: {s}", .{@errorName(err)});
            };
            std.log.warn("invoking child kill safety net during startup rollback", .{});
            child.kill(self.io);
            diagnostics.stdout_thread.?.join();
        }
        errdefer stderr_file.close(self.io);
        diagnostics.stderr_thread = try std.Thread.spawn(.{}, ProcessDiagnostics.drain, .{ diagnostics, stderr_file });
        return .{
            .allocator = self.allocator,
            .io = self.io,
            .child = child,
            .process_tree = process_tree,
            .diagnostics = diagnostics,
        };
    }
};

pub const ManagedProcess = struct {
    allocator: std.mem.Allocator,
    io: std.Io,
    child: std.process.Child,
    process_tree: ProcessTree,
    diagnostics: *ProcessDiagnostics,
    active: bool = true,

    pub fn deinit(self: *ManagedProcess) void {
        if (!self.active) return;
        self.process_tree.terminate() catch |err| {
            std.log.warn("managed process tree termination failed: {s}", .{@errorName(err)});
        };
        // Zig 0.16 的 Child.kill 返回 void，不能伪造返回值或句柄状态检查。
        // 真正负责终止完整树的是上面的 Job Object / 进程组操作；这里只是安全网。
        if (self.child.id != null) {
            std.log.warn("invoking managed child kill safety net", .{});
            self.child.kill(self.io);
        } else {
            std.log.debug("managed process already reaped before deinit", .{});
        }
        if (self.diagnostics.stdout_thread) |thread| thread.join();
        if (self.diagnostics.stderr_thread) |thread| thread.join();
        self.allocator.destroy(self.diagnostics);
        self.process_tree.deinit();
        self.active = false;
    }

    pub fn diagnosticTail(self: *ManagedProcess, output: []u8) []u8 {
        return self.diagnostics.copyTail(output);
    }
};

const ProcessDiagnostics = struct {
    io: std.Io,
    mutex: std.Io.Mutex = .init,
    bytes: [64 * 1024]u8 = undefined,
    start: usize = 0,
    len: usize = 0,
    stdout_thread: ?std.Thread = null,
    stderr_thread: ?std.Thread = null,

    fn drain(self: *ProcessDiagnostics, file: std.Io.File) void {
        defer file.close(self.io);
        var reader_buffer: [4096]u8 = undefined;
        var chunk: [4096]u8 = undefined;
        var reader = file.reader(self.io, &reader_buffer);
        while (true) {
            const count = reader.interface.readSliceShort(&chunk) catch return;
            if (count == 0) return;
            self.append(chunk[0..count]);
        }
    }

    fn append(self: *ProcessDiagnostics, input: []const u8) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        for (input) |byte| {
            if (self.len < self.bytes.len) {
                self.bytes[(self.start + self.len) % self.bytes.len] = byte;
                self.len += 1;
            } else {
                self.bytes[self.start] = byte;
                self.start = (self.start + 1) % self.bytes.len;
            }
        }
    }

    fn copyTail(self: *ProcessDiagnostics, output: []u8) []u8 {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        const count = @min(output.len, self.len);
        const offset = self.len - count;
        for (0..count) |index| output[index] = self.bytes[(self.start + offset + index) % self.bytes.len];
        return output[0..count];
    }
};

pub fn isAbsoluteExecutablePath(path: []const u8) bool {
    if (path.len == 0 or std.mem.indexOfScalar(u8, path, 0) != null) return false;
    if (std.mem.indexOfAny(u8, path, "\r\n") != null) return false;
    if (path[0] == '/') return true;
    return path.len >= 4 and
        std.ascii.isAlphabetic(path[0]) and
        path[1] == ':' and
        (path[2] == '\\' or path[2] == '/') and
        path[3] != '\\' and path[3] != '/';
}

fn normalizeTermination(term: std.process.Child.Term) Termination {
    return switch (term) {
        .exited => |code| .{ .exited = code },
        .signal => |signal| .{ .signal = @intFromEnum(signal) },
        .stopped => |signal| .{ .stopped = @intFromEnum(signal) },
        .unknown => |code| .{ .unknown = code },
    };
}

const ProcessTree = struct {
    identifier: if (builtin.os.tag == .windows) ?std.os.windows.HANDLE else ?std.posix.pid_t,

    fn attach(child: *std.process.Child) !ProcessTree {
        if (builtin.os.tag == .windows) {
            const job = CreateJobObjectW(null, null) orelse return error.JobObjectCreationFailed;
            errdefer std.os.windows.CloseHandle(job);

            var limits = std.mem.zeroes(JobObjectExtendedLimitInformation);
            limits.basic_limit_information.limit_flags = job_object_limit_kill_on_close;
            if (!SetInformationJobObject(
                job,
                job_object_extended_limit_information,
                @ptrCast(&limits),
                @sizeOf(JobObjectExtendedLimitInformation),
            ).toBool()) return error.JobObjectConfigurationFailed;
            // child.id 在 wait 前可能为 null（Zig 文档未完全保证），必须显式检查。
            const child_id = child.id orelse return error.ProcessIdentifierMissing;
            if (!AssignProcessToJobObject(job, child_id).toBool()) {
                return error.JobObjectAssignmentFailed;
            }
            return .{ .identifier = job };
        }
        return .{ .identifier = child.id orelse return error.ProcessIdentifierMissing };
    }

    fn resumeChild(self: *ProcessTree, child: *std.process.Child) !void {
        _ = self;
        if (builtin.os.tag == .windows) {
            if (std.os.windows.ntdll.NtResumeThread(child.thread_handle, null) != .SUCCESS) {
                return error.ProcessResumeFailed;
            }
        }
    }

    fn terminate(self: *ProcessTree) !void {
        if (builtin.os.tag == .windows) {
            if (self.identifier) |job| {
                if (!TerminateJobObject(job, 1).toBool()) return error.JobObjectTerminationFailed;
            }
            return;
        }
        if (self.identifier) |pid| try std.posix.kill(-pid, .KILL);
    }

    fn deinit(self: *ProcessTree) void {
        if (builtin.os.tag == .windows) {
            if (self.identifier) |job| std.os.windows.CloseHandle(job);
        }
        self.identifier = null;
    }
};

const JobObjectBasicLimitInformation = extern struct {
    per_process_user_time_limit: i64,
    per_job_user_time_limit: i64,
    limit_flags: u32,
    minimum_working_set_size: usize,
    maximum_working_set_size: usize,
    active_process_limit: u32,
    affinity: usize,
    priority_class: u32,
    scheduling_class: u32,
};

const IoCounters = extern struct {
    read_operation_count: u64,
    write_operation_count: u64,
    other_operation_count: u64,
    read_transfer_count: u64,
    write_transfer_count: u64,
    other_transfer_count: u64,
};

const JobObjectExtendedLimitInformation = extern struct {
    basic_limit_information: JobObjectBasicLimitInformation,
    io_info: IoCounters,
    process_memory_limit: usize,
    job_memory_limit: usize,
    peak_process_memory_used: usize,
    peak_job_memory_used: usize,
};

const job_object_limit_kill_on_close: u32 = 0x00002000;
const job_object_extended_limit_information: u32 = 9;

extern "kernel32" fn CreateJobObjectW(
    job_attributes: ?*anyopaque,
    name: ?[*:0]const u16,
) callconv(.winapi) ?std.os.windows.HANDLE;
extern "kernel32" fn SetInformationJobObject(
    job: std.os.windows.HANDLE,
    information_class: u32,
    information: *anyopaque,
    information_length: u32,
) callconv(.winapi) std.os.windows.BOOL;
extern "kernel32" fn AssignProcessToJobObject(
    job: std.os.windows.HANDLE,
    process: std.os.windows.HANDLE,
) callconv(.winapi) std.os.windows.BOOL;
extern "kernel32" fn TerminateJobObject(
    job: std.os.windows.HANDLE,
    exit_code: u32,
) callconv(.winapi) std.os.windows.BOOL;

test "requires an absolute executable and bounded settings" {
    const supervisor = ProcessSupervisor.init(std.testing.allocator, std.testing.io);
    try std.testing.expectError(error.ExecutablePathMustBeAbsolute, supervisor.run(.{
        .argv = &.{ "adb", "version" },
    }));
    try std.testing.expectError(error.InvalidOutputLimit, supervisor.run(.{
        .argv = &.{"/bin/echo"},
        .output_limit = maximum_output_bytes + 1,
    }));
}

test "normalizes exit status and captures bounded output" {
    if (builtin.os.tag == .windows) return error.SkipZigTest;
    const supervisor = ProcessSupervisor.init(std.testing.allocator, std.testing.io);
    var result = try supervisor.run(.{
        .argv = &.{ "/usr/bin/printf", "adb-ok" },
    });
    defer result.deinit(std.testing.allocator);
    try std.testing.expect(result.termination.succeeded());
    try std.testing.expectEqualStrings("adb-ok", result.stdout);
}

test "enforces the combined output ceiling" {
    if (builtin.os.tag == .windows) return error.SkipZigTest;
    const supervisor = ProcessSupervisor.init(std.testing.allocator, std.testing.io);
    try std.testing.expectError(error.OutputLimitExceeded, supervisor.run(.{
        .argv = &.{ "/usr/bin/printf", "12345" },
        .output_limit = 4,
    }));
}

test "terminates a process group at the command deadline" {
    if (builtin.os.tag == .windows) return error.SkipZigTest;
    const supervisor = ProcessSupervisor.init(std.testing.allocator, std.testing.io);
    try std.testing.expectError(error.Timeout, supervisor.run(.{
        .argv = &.{ "/bin/sleep", "1" },
        .timeout_ms = 5,
    }));
}

test "uses job objects on Windows and process groups elsewhere" {
    try std.testing.expectEqual(Containment.windows_job_object, containmentForHost(.windows));
    try std.testing.expectEqual(Containment.process_group, containmentForHost(.macos));
}

test "long-running process diagnostics retain the combined 64 KiB tail" {
    var diagnostics = ProcessDiagnostics{ .io = std.testing.io };
    var input: [70 * 1024]u8 = undefined;
    for (&input, 0..) |*byte, index| byte.* = @intCast(index % 251);
    diagnostics.append(&input);
    var output: [1024]u8 = undefined;
    const tail = diagnostics.copyTail(&output);
    try std.testing.expectEqualSlices(u8, input[input.len - output.len ..], tail);
}
