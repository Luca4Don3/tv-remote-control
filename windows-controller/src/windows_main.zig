const std = @import("std");

const win = @cImport({
    @cInclude("windows.h");
});

const window_class = "TvRemoteControlWindow";
const window_title = "TV Remote Control";
const ID_REFRESH = 1001;
const ID_ADB_SETUP = 1002;
const ID_UP = 1101;
const ID_DOWN = 1102;
const ID_LEFT = 1103;
const ID_RIGHT = 1104;
const ID_OK = 1105;
const ID_BACK = 1106;
const ID_HOME = 1107;

var status_label: win.HWND = null;

pub fn main(_: std.process.Init) !void {
    const instance = win.GetModuleHandleA(null);
    var window_class_definition = std.mem.zeroes(win.WNDCLASSA);
    window_class_definition.lpfnWndProc = windowProcedure;
    window_class_definition.hInstance = instance;
    window_class_definition.lpszClassName = window_class;
    window_class_definition.hCursor = win.LoadCursorA(null, @ptrFromInt(32512));
    window_class_definition.hbrBackground = win.GetSysColorBrush(win.COLOR_WINDOW);

    if (win.RegisterClassA(&window_class_definition) == 0) return error.WindowClassRegistrationFailed;

    const window = win.CreateWindowExA(
        0,
        window_class,
        window_title,
        win.WS_OVERLAPPEDWINDOW | win.WS_VISIBLE,
        win.CW_USEDEFAULT,
        win.CW_USEDEFAULT,
        1100,
        720,
        null,
        null,
        instance,
        null,
    ) orelse return error.WindowCreationFailed;

    _ = win.ShowWindow(window, win.SW_SHOW);
    _ = win.UpdateWindow(window);

    var message: win.MSG = undefined;
    while (win.GetMessageA(&message, null, 0, 0) > 0) {
        _ = win.TranslateMessage(&message);
        _ = win.DispatchMessageA(&message);
    }
}

fn windowProcedure(window: win.HWND, message: win.UINT, w_param: win.WPARAM, l_param: win.LPARAM) callconv(.winapi) win.LRESULT {
    switch (message) {
        win.WM_CREATE => createControls(window),
        win.WM_COMMAND => handleCommand(@intCast(w_param & 0xffff)),
        win.WM_DESTROY => {
            win.PostQuitMessage(0);
            return 0;
        },
        else => return win.DefWindowProcA(window, message, w_param, l_param),
    }
    return 0;
}

fn createControls(parent: win.HWND) void {
    _ = createStatic(parent, "Devices and capabilities", 20, 18, 300, 24);
    status_label = createStatic(parent, "APK discovery active; ADB enhancement is optional", 20, 48, 620, 24);
    _ = createButton(parent, "Refresh", ID_REFRESH, 660, 42, 110, 32);
    _ = createButton(parent, "ADB setup", ID_ADB_SETUP, 785, 42, 120, 32);

    _ = createStatic(parent, "Video preview (backend not connected)", 20, 90, 760, 430);
    _ = createStatic(parent, "Remote", 820, 100, 180, 24);
    _ = createButton(parent, "Up", ID_UP, 870, 145, 80, 42);
    _ = createButton(parent, "Left", ID_LEFT, 820, 195, 80, 42);
    _ = createButton(parent, "OK", ID_OK, 910, 195, 80, 42);
    _ = createButton(parent, "Right", ID_RIGHT, 1000, 195, 80, 42);
    _ = createButton(parent, "Down", ID_DOWN, 870, 245, 80, 42);
    _ = createButton(parent, "Back", ID_BACK, 820, 315, 120, 42);
    _ = createButton(parent, "Home", ID_HOME, 950, 315, 120, 42);
}

fn handleCommand(id: u16) void {
    const message = switch (id) {
        ID_REFRESH => "Refreshing APK discovery and optional ADB status...",
        ID_ADB_SETUP => "ADB setup requires explicit path and upgrade confirmation",
        ID_UP => "DPAD_UP queued for active authenticated session",
        ID_DOWN => "DPAD_DOWN queued for active authenticated session",
        ID_LEFT => "DPAD_LEFT queued for active authenticated session",
        ID_RIGHT => "DPAD_RIGHT queued for active authenticated session",
        ID_OK => "DPAD_CENTER queued for active authenticated session",
        ID_BACK => "BACK queued for active authenticated session",
        ID_HOME => "HOME queued for active authenticated session",
        else => return,
    };
    if (status_label) |label| _ = win.SetWindowTextA(label, message);
}

fn createStatic(parent: win.HWND, text: [*:0]const u8, x: c_int, y: c_int, width: c_int, height: c_int) win.HWND {
    return win.CreateWindowExA(0, "STATIC", text, win.WS_CHILD | win.WS_VISIBLE | win.SS_LEFT, x, y, width, height, parent, null, null, null);
}

fn createButton(parent: win.HWND, text: [*:0]const u8, id: usize, x: c_int, y: c_int, width: c_int, height: c_int) win.HWND {
    return win.CreateWindowExA(
        0,
        "BUTTON",
        text,
        win.WS_TABSTOP | win.WS_VISIBLE | win.WS_CHILD | win.BS_PUSHBUTTON,
        x,
        y,
        width,
        height,
        parent,
        @ptrFromInt(id),
        null,
        null,
    );
}
