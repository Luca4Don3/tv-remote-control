const std = @import("std");

extern fn tvrc_windows_run() callconv(.c) c_int;

pub fn main(_: std.process.Init) !void {
    if (tvrc_windows_run() != 0) return error.WindowsUiFailed;
}
