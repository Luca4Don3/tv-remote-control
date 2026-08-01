#define UNICODE
#define _UNICODE
#include <windows.h>
#include <commdlg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "tv_remote_core.h"
#if defined(_WIN64)
#include "tvrc_windows_media.h"
#endif

enum {
    ID_REFRESH = 1001,
    ID_ADB_SETUP = 1002,
    ID_TARGET = 1003,
    ID_PAIR_CODE = 1004,
    ID_PAIR = 1005,
    ID_CONNECT = 1006,
    ID_DISCONNECT = 1007,
    ID_MEDIA = 1008,
    ID_UP = 1101,
    ID_DOWN = 1102,
    ID_LEFT = 1103,
    ID_RIGHT = 1104,
    ID_OK = 1105,
    ID_BACK = 1106,
    ID_HOME = 1107,
    ID_VOLUME_UP = 1108,
    ID_VOLUME_DOWN = 1109,
    ID_VOLUME_MUTE = 1110,
    ID_MEDIA_PLAY_PAUSE = 1111,
    ID_MEDIA_STOP = 1112,
    ID_MEDIA_NEXT = 1113,
    ID_MEDIA_PREVIOUS = 1114,
    ID_ADB_ENABLED = 1201,
    ID_ADB_PATH = 1202,
    ID_ADB_BROWSE = 1203,
    ID_ADB_PROBE = 1204,
    ID_ADB_DEVICES = 1205,
    ID_ADB_INSTALL = 1206,
    ID_ADB_DIAGNOSTICS = 1207,
    ID_ADB_CLOSE = 1208,
    ID_CLOSE_TIMEOUT = 1901,
    ID_KEY_REPEAT_TIMER = 1,
    WM_TVRC_EVENT = WM_APP + 17,
    WM_TVRC_MEDIA_ERROR = WM_APP + 18,
};

static HWND status_label;
static HWND preview_label;
static HWND refresh_button;
static HWND adb_button;
static HWND target_edit;
static HWND pairing_code_edit;
static HWND pair_button;
static HWND connect_button;
static HWND disconnect_button;
static HWND media_button;
static HWND sas_label;
static HWND remote_buttons[14];
static const char *remote_capability_names[14] = {
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "BACK", "HOME",
    "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE", "MEDIA_PLAY_PAUSE", "MEDIA_STOP",
    "MEDIA_NEXT", "MEDIA_PREVIOUS"
};
static BOOL remote_capabilities[14];
static BOOL media_transport_capable;
static BOOL core_connected;
static BOOL core_busy;
static BOOL remote_pressed[5];
static BOOL remote_repeating[5];
static uint64_t remote_down_request_ids[5];
static WNDPROC remote_button_procs[5];
static BOOL pending_close;
static BOOL media_active;
static BOOL media_request_pending;
static BOOL media_requested_start;
static uint64_t media_request_id;
static BOOL media_backend_adb;
static BOOL adb_enabled;
static BOOL adb_ready;
static BOOL adb_upgrade_required;
static HWND adb_settings_window;
static HWND adb_enable_check;
static HWND adb_intro_label;
static HWND adb_path_label;
static HWND adb_device_label;
static HWND adb_path_edit;
static HWND adb_device_list;
static HWND adb_diagnostics_edit;
static char adb_selected_serial[256];
static uint8_t adb_persisted_path[1024];
static uint32_t adb_persisted_path_len;
static char adb_device_serials[32][256];
static int adb_device_serial_count;
#if defined(_WIN64)
static PVOID volatile native_media_pointer;
static volatile LONG native_media_reset_requested;
#endif
static tvrc_handle *core_handle;
static HWND main_window;
static HANDLE event_thread;
static volatile LONG event_thread_stop;
static volatile LONG64 next_request_id;

typedef struct posted_core_event {
    tvrc_event event;
    uint8_t payload[1025];
} posted_core_event;

static void cancel_remote_key_local(int index);
static void show_adb_settings(HWND owner);
static void update_control_enablement(void);

static const wchar_t adb_registry_path[] = L"Software\\Lucas Done\\TV Remote Control";

static void save_adb_enabled(void) {
    HKEY key;
    DWORD value = adb_enabled ? 1u : 0u;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, adb_registry_path, 0, NULL, 0, KEY_SET_VALUE,
                        NULL, &key, NULL) == ERROR_SUCCESS) {
        RegSetValueExW(key, L"AdbEnabled", 0, REG_DWORD, (const BYTE *)&value, sizeof(value));
        RegCloseKey(key);
    }
}

static BOOL save_adb_path_utf8(const char *utf8_path) {
    HKEY key;
    wchar_t path[1024];
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8_path, -1, path, 1024);
    if (length <= 1) return FALSE;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, adb_registry_path, 0, NULL, 0, KEY_SET_VALUE,
                        NULL, &key, NULL) == ERROR_SUCCESS) {
        LONG result = RegSetValueExW(key, L"AdbPath", 0, REG_SZ, (const BYTE *)path,
                                     (DWORD)(length * sizeof(wchar_t)));
        RegCloseKey(key);
        SecureZeroMemory(path, sizeof(path));
        return result == ERROR_SUCCESS;
    }
    SecureZeroMemory(path, sizeof(path));
    return FALSE;
}

typedef struct json_cursor {
    const char *position;
    const char *end;
    unsigned depth;
} json_cursor;

static void json_skip_space(json_cursor *cursor) {
    while (cursor->position < cursor->end && strchr(" \t\r\n", *cursor->position) != NULL) cursor->position++;
}

static int json_hex(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static BOOL json_codepoint(char *output, size_t capacity, size_t *used, uint32_t codepoint) {
    unsigned count;
    if (codepoint == 0 || codepoint > 0x10ffff || (codepoint >= 0xd800 && codepoint <= 0xdfff)) return FALSE;
    count = codepoint < 0x80 ? 1 : codepoint < 0x800 ? 2 : codepoint < 0x10000 ? 3 : 4;
    if (output == NULL) return TRUE;
    if (*used + count >= capacity) return FALSE;
    if (count == 1) output[(*used)++] = (char)codepoint;
    else if (count == 2) {
        output[(*used)++] = (char)(0xc0 | (codepoint >> 6));
        output[(*used)++] = (char)(0x80 | (codepoint & 0x3f));
    } else if (count == 3) {
        output[(*used)++] = (char)(0xe0 | (codepoint >> 12));
        output[(*used)++] = (char)(0x80 | ((codepoint >> 6) & 0x3f));
        output[(*used)++] = (char)(0x80 | (codepoint & 0x3f));
    } else {
        output[(*used)++] = (char)(0xf0 | (codepoint >> 18));
        output[(*used)++] = (char)(0x80 | ((codepoint >> 12) & 0x3f));
        output[(*used)++] = (char)(0x80 | ((codepoint >> 6) & 0x3f));
        output[(*used)++] = (char)(0x80 | (codepoint & 0x3f));
    }
    return TRUE;
}

static BOOL json_string(json_cursor *cursor, char *output, size_t capacity) {
    size_t used = 0;
    if (cursor->position >= cursor->end || *cursor->position++ != '"') return FALSE;
    while (cursor->position < cursor->end) {
        unsigned char value = (unsigned char)*cursor->position++;
        if (value == '"') {
            if (output != NULL) output[used] = '\0';
            return TRUE;
        }
        if (value < 0x20) return FALSE;
        if (value != '\\') {
            if (output != NULL) {
                if (used + 1 >= capacity) return FALSE;
                output[used++] = (char)value;
            }
            continue;
        }
        if (cursor->position >= cursor->end) return FALSE;
        value = (unsigned char)*cursor->position++;
        if (value == '"' || value == '\\' || value == '/') {
            if (output != NULL) {
                if (used + 1 >= capacity) return FALSE;
                output[used++] = (char)value;
            }
        } else if (strchr("bfnrt", value) != NULL) {
            static const char escaped[] = "\b\f\n\r\t";
            const char *names = "bfnrt";
            if (output != NULL) {
                if (used + 1 >= capacity) return FALSE;
                output[used++] = escaped[strchr(names, value) - names];
            }
        } else if (value == 'u') {
            uint32_t codepoint = 0;
            int index;
            if (cursor->end - cursor->position < 4) return FALSE;
            for (index = 0; index < 4; ++index) {
                int digit = json_hex(*cursor->position++);
                if (digit < 0) return FALSE;
                codepoint = (codepoint << 4) | (uint32_t)digit;
            }
            if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
                uint32_t low = 0;
                if (cursor->end - cursor->position < 6 || cursor->position[0] != '\\' || cursor->position[1] != 'u') return FALSE;
                cursor->position += 2;
                for (index = 0; index < 4; ++index) {
                    int digit = json_hex(*cursor->position++);
                    if (digit < 0) return FALSE;
                    low = (low << 4) | (uint32_t)digit;
                }
                if (low < 0xdc00 || low > 0xdfff) return FALSE;
                codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (low - 0xdc00);
            }
            if (!json_codepoint(output, capacity, &used, codepoint)) return FALSE;
        } else return FALSE;
    }
    return FALSE;
}

static BOOL json_value(json_cursor *cursor);

static BOOL json_compound(json_cursor *cursor, char opening) {
    char closing = opening == '{' ? '}' : ']';
    if (++cursor->depth > 16) return FALSE;
    cursor->position++;
    json_skip_space(cursor);
    if (cursor->position < cursor->end && *cursor->position == closing) {
        cursor->position++;
        cursor->depth--;
        return TRUE;
    }
    for (;;) {
        if (opening == '{') {
            if (!json_string(cursor, NULL, 0)) return FALSE;
            json_skip_space(cursor);
            if (cursor->position >= cursor->end || *cursor->position++ != ':') return FALSE;
            json_skip_space(cursor);
        }
        if (!json_value(cursor)) return FALSE;
        json_skip_space(cursor);
        if (cursor->position >= cursor->end) return FALSE;
        if (*cursor->position == closing) {
            cursor->position++;
            cursor->depth--;
            return TRUE;
        }
        if (*cursor->position++ != ',') return FALSE;
        json_skip_space(cursor);
    }
}

static BOOL json_value(json_cursor *cursor) {
    const char *start;
    json_skip_space(cursor);
    if (cursor->position >= cursor->end) return FALSE;
    if (*cursor->position == '"') return json_string(cursor, NULL, 0);
    if (*cursor->position == '{' || *cursor->position == '[') return json_compound(cursor, *cursor->position);
    start = cursor->position;
    if (*cursor->position == '-') cursor->position++;
    if (cursor->position < cursor->end && *cursor->position == '0') cursor->position++;
    else {
        if (cursor->position >= cursor->end || *cursor->position < '1' || *cursor->position > '9') goto literal;
        while (cursor->position < cursor->end && *cursor->position >= '0' && *cursor->position <= '9') cursor->position++;
    }
    if (cursor->position < cursor->end && *cursor->position == '.') {
        cursor->position++;
        if (cursor->position >= cursor->end || *cursor->position < '0' || *cursor->position > '9') return FALSE;
        while (cursor->position < cursor->end && *cursor->position >= '0' && *cursor->position <= '9') cursor->position++;
    }
    if (cursor->position < cursor->end && (*cursor->position == 'e' || *cursor->position == 'E')) {
        cursor->position++;
        if (cursor->position < cursor->end && (*cursor->position == '+' || *cursor->position == '-')) cursor->position++;
        if (cursor->position >= cursor->end || *cursor->position < '0' || *cursor->position > '9') return FALSE;
        while (cursor->position < cursor->end && *cursor->position >= '0' && *cursor->position <= '9') cursor->position++;
    }
    return cursor->position > start;
literal:
    cursor->position = start;
    if (cursor->end - cursor->position >= 4 && memcmp(cursor->position, "true", 4) == 0) cursor->position += 4;
    else if (cursor->end - cursor->position >= 5 && memcmp(cursor->position, "false", 5) == 0) cursor->position += 5;
    else if (cursor->end - cursor->position >= 4 && memcmp(cursor->position, "null", 4) == 0) cursor->position += 4;
    else return FALSE;
    return TRUE;
}

static BOOL json_string_field(const char *json, const char *field, char *output, size_t capacity) {
    json_cursor cursor;
    char key[80];
    char candidate[1024];
    BOOL found = FALSE;
    size_t length = strnlen(json, 1025);
    if (length == 0 || length > 1024 || capacity == 0) return FALSE;
    cursor.position = json;
    cursor.end = json + length;
    cursor.depth = 0;
    json_skip_space(&cursor);
    if (cursor.position >= cursor.end || *cursor.position++ != '{') return FALSE;
    json_skip_space(&cursor);
    if (cursor.position < cursor.end && *cursor.position == '}') cursor.position++;
    else for (;;) {
        if (!json_string(&cursor, key, sizeof(key))) return FALSE;
        json_skip_space(&cursor);
        if (cursor.position >= cursor.end || *cursor.position++ != ':') return FALSE;
        json_skip_space(&cursor);
        if (strcmp(key, field) == 0) {
            if (found || !json_string(&cursor, candidate, sizeof(candidate))) return FALSE;
            found = TRUE;
        } else if (!json_value(&cursor)) return FALSE;
        json_skip_space(&cursor);
        if (cursor.position >= cursor.end) return FALSE;
        if (*cursor.position == '}') { cursor.position++; break; }
        if (*cursor.position++ != ',') return FALSE;
        json_skip_space(&cursor);
    }
    json_skip_space(&cursor);
    if (!found || cursor.position != cursor.end || candidate[0] == '\0' || strlen(candidate) >= capacity) return FALSE;
    memcpy(output, candidate, strlen(candidate) + 1);
    SecureZeroMemory(candidate, sizeof(candidate));
    return TRUE;
}

static void load_adb_settings(void) {
    HKEY key;
    DWORD type;
    DWORD enabled = 0;
    DWORD size = sizeof(enabled);
    wchar_t path[1024];
    if (RegOpenKeyExW(HKEY_CURRENT_USER, adb_registry_path, 0, KEY_QUERY_VALUE, &key) != ERROR_SUCCESS) return;
    if (RegQueryValueExW(key, L"AdbEnabled", NULL, &type, (BYTE *)&enabled, &size) == ERROR_SUCCESS &&
        type == REG_DWORD) adb_enabled = enabled != 0;
    size = sizeof(path);
    if (RegQueryValueExW(key, L"AdbPath", NULL, &type, (BYTE *)path, &size) == ERROR_SUCCESS &&
        type == REG_SZ && size >= sizeof(wchar_t)) path[1023] = L'\0';
    else path[0] = L'\0';
    RegCloseKey(key);
    adb_persisted_path_len = (uint32_t)WideCharToMultiByte(
        CP_UTF8, WC_ERR_INVALID_CHARS, path, -1, (char *)adb_persisted_path,
        (int)sizeof(adb_persisted_path), NULL, NULL);
    if (adb_persisted_path_len > 0) adb_persisted_path_len--;
    else adb_persisted_path_len = 0;
    if (adb_path_edit != NULL) SetWindowTextW(adb_path_edit, path);
    SecureZeroMemory(path, sizeof(path));
}

static HWND make_control(HWND parent, const wchar_t *class_name, const wchar_t *text, DWORD style, int id) {
    return CreateWindowExW(0, class_name, text, WS_CHILD | WS_VISIBLE | style, 0, 0, 1, 1,
                           parent, (HMENU)(INT_PTR)id, GetModuleHandleW(NULL), NULL);
}

static void layout(HWND window) {
    RECT bounds;
    GetClientRect(window, &bounds);
    const int width = bounds.right - bounds.left;
    const int height = bounds.bottom - bounds.top;
    const int margin = 20;
    const int remote_width = width >= 900 ? 260 : width - margin * 2;
    const int preview_width = width >= 900 ? width - remote_width - margin * 3 : width - margin * 2;
    const int preview_height = width >= 900 ? height - 230 : (height - 300) / 2;
    MoveWindow(status_label, margin, 18, width - 356, 40, TRUE);
    MoveWindow(media_button, width - 316, 20, 88, 36, TRUE);
    MoveWindow(refresh_button, width - 220, 20, 88, 36, TRUE);
    MoveWindow(adb_button, width - 122, 20, 102, 36, TRUE);
    MoveWindow(target_edit, margin, 66, 250, 160, TRUE);
    MoveWindow(pairing_code_edit, margin + 260, 66, 92, 30, TRUE);
    MoveWindow(pair_button, margin + 362, 66, 76, 30, TRUE);
    MoveWindow(connect_button, margin + 448, 66, 76, 30, TRUE);
    MoveWindow(disconnect_button, margin + 534, 66, 76, 30, TRUE);
    MoveWindow(sas_label, margin, 108, width - margin * 2, 54, TRUE);
    MoveWindow(preview_label, margin, 172, preview_width, preview_height, TRUE);

    const int origin_x = width >= 900 ? width - remote_width - margin : margin;
    const int origin_y = width >= 900 ? 190 : 190 + preview_height;
    const int button_width = 72;
    const int button_height = 40;
    MoveWindow(remote_buttons[0], origin_x + 82, origin_y, button_width, button_height, TRUE);
    MoveWindow(remote_buttons[1], origin_x + 82, origin_y + 96, button_width, button_height, TRUE);
    MoveWindow(remote_buttons[2], origin_x, origin_y + 48, button_width, button_height, TRUE);
    MoveWindow(remote_buttons[3], origin_x + 164, origin_y + 48, button_width, button_height, TRUE);
    MoveWindow(remote_buttons[4], origin_x + 82, origin_y + 48, button_width, button_height, TRUE);
    MoveWindow(remote_buttons[5], origin_x, origin_y + 152, 108, button_height, TRUE);
    MoveWindow(remote_buttons[6], origin_x + 128, origin_y + 152, 108, button_height, TRUE);
    MoveWindow(remote_buttons[7], origin_x, origin_y + 200, 72, button_height, TRUE);
    MoveWindow(remote_buttons[8], origin_x + 82, origin_y + 200, 72, button_height, TRUE);
    MoveWindow(remote_buttons[9], origin_x + 164, origin_y + 200, 72, button_height, TRUE);
    MoveWindow(remote_buttons[10], origin_x, origin_y + 248, 108, button_height, TRUE);
    MoveWindow(remote_buttons[11], origin_x + 128, origin_y + 248, 108, button_height, TRUE);
    MoveWindow(remote_buttons[12], origin_x, origin_y + 296, 108, button_height, TRUE);
    MoveWindow(remote_buttons[13], origin_x + 128, origin_y + 296, 108, button_height, TRUE);
}

static void set_status_utf8(const uint8_t *text, uint32_t length) {
    wchar_t wide[1025];
    int count;
    if (length == 0 || length > 1024) return;
    count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, (const char *)text,
                                (int)length, wide, 1024);
    if (count <= 0) {
        SetWindowTextW(status_label, L"核心返回了无效 UTF-8 事件；操作未标记成功。");
        return;
    }
    wide[count] = L'\0';
    SetWindowTextW(status_label, wide);
}

static BOOL read_edit_utf8(HWND edit, uint8_t *output, uint32_t capacity, uint32_t *length) {
    wchar_t wide[280];
    int wide_length = GetWindowTextLengthW(edit);
    int utf8_length;
    if (wide_length <= 0 || wide_length >= (int)(sizeof(wide) / sizeof(wide[0])) || capacity == 0) return FALSE;
    if (GetWindowTextW(edit, wide, wide_length + 1) != wide_length) return FALSE;
    utf8_length = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, wide, wide_length,
                                      (char *)output, (int)capacity, NULL, NULL);
    SecureZeroMemory(wide, sizeof(wide));
    if (utf8_length <= 0) return FALSE;
    *length = (uint32_t)utf8_length;
    return TRUE;
}

static void set_edit_utf8(HWND edit, const char *text, size_t length) {
    wchar_t wide[280];
    int count;
    if (length == 0 || length >= sizeof(wide) / sizeof(wide[0])) return;
    count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, (int)length,
                                wide, (int)(sizeof(wide) / sizeof(wide[0]) - 1));
    if (count <= 0) return;
    wide[count] = L'\0';
    if (SendMessageW(edit, CB_FINDSTRINGEXACT, (WPARAM)-1, (LPARAM)wide) == CB_ERR) {
        SendMessageW(edit, CB_ADDSTRING, 0, (LPARAM)wide);
    }
    SetWindowTextW(edit, wide);
}

static void append_adb_diagnostic(const uint8_t *text, uint32_t length) {
    wchar_t wide[1100];
    int count;
    int existing;
    if (adb_diagnostics_edit == NULL || length == 0 || length > 1024) return;
    count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, (const char *)text,
                                (int)length, wide, 1024);
    if (count <= 0) return;
    wide[count++] = L'\r';
    wide[count++] = L'\n';
    wide[count] = L'\0';
    existing = GetWindowTextLengthW(adb_diagnostics_edit);
    if (existing + count > 16384) SetWindowTextW(adb_diagnostics_edit, L"");
    SendMessageW(adb_diagnostics_edit, EM_SETSEL, (WPARAM)-1, (LPARAM)-1);
    SendMessageW(adb_diagnostics_edit, EM_REPLACESEL, FALSE, (LPARAM)wide);
}

static void submit_adb_probe(void) {
    uint8_t path[1024];
    uint32_t path_len = 0;
    uint64_t request_id;
    if (!adb_enabled) {
        SetWindowTextW(adb_diagnostics_edit, L"请先显式启用 ADB 媒体增强。\r\n");
        return;
    }
    if (adb_path_edit != NULL && GetWindowTextLengthW(adb_path_edit) > 0 &&
        !read_edit_utf8(adb_path_edit, path, sizeof(path), &path_len)) {
        SetWindowTextW(adb_diagnostics_edit, L"ADB 路径不是有效的 UTF-8 绝对路径。\r\n");
        return;
    }
    if (adb_path_edit == NULL && adb_persisted_path_len > 0) {
        memcpy(path, adb_persisted_path, adb_persisted_path_len);
        path_len = adb_persisted_path_len;
    }
    request_id = (uint64_t)InterlockedIncrement64(&next_request_id);
    if (tvrc_adb_probe(core_handle, request_id, path_len ? path : NULL, path_len) != TVRC_OK) {
        SetWindowTextW(adb_diagnostics_edit, L"ADB 探针未入队；请等待当前 ADB 操作结束。\r\n");
    }
    SecureZeroMemory(path, sizeof(path));
}

static void browse_adb_path(HWND window) {
    OPENFILENAMEW picker = {0};
    wchar_t path[1024] = L"";
    picker.lStructSize = sizeof(picker);
    picker.hwndOwner = window;
    picker.lpstrFilter = L"Android Debug Bridge (adb.exe)\0adb.exe\0Executables\0*.exe\0\0";
    picker.lpstrFile = path;
    picker.nMaxFile = 1024;
    picker.lpstrTitle = L"选择 adb.exe";
    picker.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR | OFN_DONTADDTORECENT;
    if (GetOpenFileNameW(&picker)) SetWindowTextW(adb_path_edit, path);
    SecureZeroMemory(path, sizeof(path));
}

static LRESULT CALLBACK adb_settings_proc(HWND window, UINT message, WPARAM w_param, LPARAM l_param) {
    (void)l_param;
    switch (message) {
    case WM_CREATE:
        adb_intro_label = make_control(window, L"STATIC", L"ADB/scrcpy 仅增强媒体；按键始终由已认证 APK 控制。", SS_LEFT, 0);
        adb_enable_check = make_control(window, L"BUTTON", L"启用 ADB 媒体增强（默认关闭）", BS_AUTOCHECKBOX, ID_ADB_ENABLED);
        adb_path_label = make_control(window, L"STATIC", L"adb.exe 路径（留空时按保存路径、PATH、管理目录搜索）：", SS_LEFT, 0);
        adb_path_edit = make_control(window, L"EDIT", L"", WS_BORDER | ES_AUTOHSCROLL, ID_ADB_PATH);
        make_control(window, L"BUTTON", L"浏览…", BS_PUSHBUTTON, ID_ADB_BROWSE);
        make_control(window, L"BUTTON", L"重新探测", BS_PUSHBUTTON, ID_ADB_PROBE);
        adb_device_label = make_control(window, L"STATIC", L"已发现设备（必须显式选择）：", SS_LEFT, 0);
        adb_device_list = make_control(window, L"LISTBOX", L"", WS_BORDER | LBS_NOTIFY | WS_VSCROLL, ID_ADB_DEVICES);
#if defined(_WIN64)
        make_control(window, L"BUTTON", L"安装/升级官方 Platform Tools…", BS_PUSHBUTTON, ID_ADB_INSTALL);
#endif
        adb_diagnostics_edit = make_control(window, L"EDIT", L"", WS_BORDER | ES_MULTILINE | ES_AUTOVSCROLL | ES_READONLY | WS_VSCROLL, ID_ADB_DIAGNOSTICS);
        make_control(window, L"BUTTON", L"关闭", BS_DEFPUSHBUTTON, ID_ADB_CLOSE);
        SendMessageW(adb_path_edit, EM_SETLIMITTEXT, 1023, 0);
        SendMessageW(adb_diagnostics_edit, EM_SETLIMITTEXT, 16384, 0);
        load_adb_settings();
        SendMessageW(adb_enable_check, BM_SETCHECK, adb_enabled ? BST_CHECKED : BST_UNCHECKED, 0);
        return 0;
    case WM_SIZE: {
        RECT bounds;
        HWND child;
        int width;
        GetClientRect(window, &bounds);
        width = bounds.right - bounds.left;
        child = GetDlgItem(window, ID_ADB_ENABLED);
        MoveWindow(adb_intro_label, 16, 14, width - 32, 28, TRUE);
        MoveWindow(child, 16, 50, width - 32, 28, TRUE);
        MoveWindow(adb_path_label, 16, 84, width - 32, 22, TRUE);
        MoveWindow(GetDlgItem(window, ID_ADB_PATH), 16, 110, width - 210, 28, TRUE);
        MoveWindow(GetDlgItem(window, ID_ADB_BROWSE), width - 184, 110, 78, 28, TRUE);
        MoveWindow(GetDlgItem(window, ID_ADB_PROBE), width - 98, 110, 82, 28, TRUE);
        MoveWindow(adb_device_label, 16, 146, width - 32, 22, TRUE);
        MoveWindow(GetDlgItem(window, ID_ADB_DEVICES), 16, 170, width - 32, 105, TRUE);
#if defined(_WIN64)
        MoveWindow(GetDlgItem(window, ID_ADB_INSTALL), 16, 284, 230, 30, TRUE);
#endif
        MoveWindow(GetDlgItem(window, ID_ADB_DIAGNOSTICS), 16, 324, width - 32, 145, TRUE);
        MoveWindow(GetDlgItem(window, ID_ADB_CLOSE), width - 96, 480, 80, 30, TRUE);
        return 0;
    }
    case WM_COMMAND:
        switch (LOWORD(w_param)) {
        case ID_ADB_ENABLED:
            adb_enabled = SendMessageW(adb_enable_check, BM_GETCHECK, 0, 0) == BST_CHECKED;
            save_adb_enabled();
            if (!adb_enabled) {
                uint64_t request_id = (uint64_t)InterlockedIncrement64(&next_request_id);
                adb_ready = FALSE;
                adb_selected_serial[0] = '\0';
                (void)tvrc_adb_disable(core_handle, request_id);
            }
            return 0;
        case ID_ADB_BROWSE: browse_adb_path(window); return 0;
        case ID_ADB_PROBE:
            SendMessageW(adb_device_list, LB_RESETCONTENT, 0, 0);
            adb_selected_serial[0] = '\0';
            SecureZeroMemory(adb_device_serials, sizeof(adb_device_serials));
            adb_device_serial_count = 0;
            submit_adb_probe();
            return 0;
        case ID_ADB_DEVICES:
            if (HIWORD(w_param) == LBN_SELCHANGE) {
                int selected = (int)SendMessageW(adb_device_list, LB_GETCURSEL, 0, 0);
                if (selected >= 0 && selected < adb_device_serial_count) {
                    memcpy(adb_selected_serial, adb_device_serials[selected], sizeof(adb_selected_serial));
                    update_control_enablement();
                }
            }
            return 0;
#if defined(_WIN64)
        case ID_ADB_INSTALL: {
            int answer = MessageBoxW(window,
                L"仅安装或升级 Google 官方 Platform Tools 到应用管理目录。外部 ADB 不会被覆盖。是否继续？",
                L"确认安装/升级 ADB", MB_ICONWARNING | MB_YESNO | MB_DEFBUTTON2);
            if (answer == IDYES) {
                uint64_t request_id = (uint64_t)InterlockedIncrement64(&next_request_id);
                (void)tvrc_adb_install(core_handle, request_id,
                    adb_upgrade_required ? TVRC_ADB_UPGRADE_CONFIRM : TVRC_ADB_INSTALL_CONFIRM);
            }
            return 0;
        }
#endif
        case ID_ADB_CLOSE: DestroyWindow(window); return 0;
        default: return 0;
        }
    case WM_CLOSE: DestroyWindow(window); return 0;
    case WM_DESTROY:
        adb_settings_window = NULL;
        adb_enable_check = NULL;
        adb_intro_label = NULL;
        adb_path_label = NULL;
        adb_device_label = NULL;
        adb_path_edit = NULL;
        adb_device_list = NULL;
        adb_diagnostics_edit = NULL;
        EnableWindow(main_window, TRUE);
        SetForegroundWindow(main_window);
        return 0;
    default: return DefWindowProcW(window, message, w_param, l_param);
    }
}

static void show_adb_settings(HWND owner) {
    WNDCLASSW definition = {0};
    MSG message;
    if (adb_settings_window != NULL) {
        SetForegroundWindow(adb_settings_window);
        return;
    }
    definition.lpfnWndProc = adb_settings_proc;
    definition.hInstance = GetModuleHandleW(NULL);
    definition.lpszClassName = L"TvRemoteControlAdbSettings";
    definition.hCursor = LoadCursorW(NULL, IDC_ARROW);
    definition.hbrBackground = GetSysColorBrush(COLOR_WINDOW);
    RegisterClassW(&definition);
    adb_settings_window = CreateWindowExW(WS_EX_DLGMODALFRAME, definition.lpszClassName,
        L"ADB 设置（可选媒体增强）", WS_CAPTION | WS_SYSMENU | WS_SIZEBOX | WS_VISIBLE,
        CW_USEDEFAULT, CW_USEDEFAULT, 700, 570, owner, NULL, definition.hInstance, NULL);
    if (adb_settings_window == NULL) return;
    EnableWindow(owner, FALSE);
    while (adb_settings_window != NULL && GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
}

static void update_control_enablement(void) {
    int index;
    EnableWindow(refresh_button, !core_busy && !core_connected);
    EnableWindow(target_edit, !core_busy && !core_connected);
    EnableWindow(pairing_code_edit, !core_busy && !core_connected);
    EnableWindow(pair_button, !core_busy && !core_connected);
    EnableWindow(connect_button, !core_busy && !core_connected);
    EnableWindow(disconnect_button, core_connected && !core_busy);
#if defined(_WIN64)
    EnableWindow(media_button, core_connected && !core_busy && !media_request_pending &&
                 (media_transport_capable || (adb_enabled && adb_ready && adb_selected_serial[0] != '\0')));
#else
    EnableWindow(media_button, FALSE);
#endif
    for (index = 0; index < 14; ++index) {
        EnableWindow(remote_buttons[index], core_connected && !core_busy && remote_capabilities[index]);
    }
}

static void apply_state_event(const char *payload) {
    if (strstr(payload, "\"state\":\"connected\"") != NULL) {
        core_connected = TRUE;
        core_busy = FALSE;
    } else if (strstr(payload, "\"state\":\"pairing\"") != NULL ||
               strstr(payload, "\"state\":\"connecting\"") != NULL ||
               strstr(payload, "\"state\":\"discovering\"") != NULL) {
        core_connected = FALSE;
        core_busy = TRUE;
    } else if (strstr(payload, "\"state\":\"idle\"") != NULL ||
               strstr(payload, "\"state\":\"stopped\"") != NULL) {
        core_connected = FALSE;
        core_busy = FALSE;
        media_active = FALSE;
        media_request_pending = FALSE;
        SetWindowTextW(media_button, L"画面");
        SetWindowTextW(preview_label, L"实时画面：等待媒体会话");
#if defined(_WIN64)
        InterlockedExchange(&native_media_reset_requested, 1);
#endif
        if (pending_close && main_window != NULL) PostMessageW(main_window, WM_CLOSE, 0, 0);
    }
    update_control_enablement();
}

static void apply_capabilities_event(const char *payload) {
    int index;
    char pattern[96];
    for (index = 0; index < 14; ++index) {
        wsprintfA(pattern, "\"%s\":\"SUPPORTED\"", remote_capability_names[index]);
        remote_capabilities[index] = strstr(payload, pattern) != NULL;
        if (!remote_capabilities[index]) {
            wsprintfA(pattern, "\"%s\":\"BEST_EFFORT\"", remote_capability_names[index]);
            remote_capabilities[index] = strstr(payload, pattern) != NULL;
        }
    }
    media_transport_capable = strstr(payload, "\"mediaTransport\":\"SUPPORTED\"") != NULL ||
        strstr(payload, "\"mediaTransport\":\"PERMISSION_REQUIRED\"") != NULL;
    update_control_enablement();
}

static void apply_device_event(const char *payload) {
    static const char prefix[] = "\"sourceAddress\":\"";
    const char *start = strstr(payload, prefix);
    const char *end;
    if (start == NULL) return;
    start += sizeof(prefix) - 1;
    end = strchr(start, '"');
    if (end == NULL || end == start || (size_t)(end - start) > 270) return;
    set_edit_utf8(target_edit, start, (size_t)(end - start));
}

static DWORD WINAPI event_pump(void *unused) {
    (void)unused;
#if defined(_WIN64)
    tvrc_windows_media *native_media = NULL;
    uint8_t *media_payload = (uint8_t *)malloc(4u * 1024u * 1024u);
    BOOL audio_failure_reported = FALSE;
    BOOL video_failure_reported = FALSE;
    if (media_payload == NULL || FAILED(tvrc_windows_media_create(preview_label, &native_media))) {
        free(media_payload);
        media_payload = NULL;
        native_media = NULL;
        PostMessageW(main_window, WM_TVRC_MEDIA_ERROR, 0, (LPARAM)E_FAIL);
    } else {
        InterlockedExchangePointer(&native_media_pointer, native_media);
    }
#endif
    while (InterlockedCompareExchange(&event_thread_stop, 0, 0) == 0) {
        posted_core_event *posted = (posted_core_event *)calloc(1, sizeof(*posted));
        tvrc_result result;
#if defined(_WIN64)
        BOOL media_processed = FALSE;
        if (native_media != NULL && InterlockedExchange(&native_media_reset_requested, 0) != 0) {
            tvrc_windows_media_reset(native_media);
            audio_failure_reported = FALSE;
            video_failure_reported = FALSE;
        }
        if (native_media != NULL && media_payload != NULL) {
            tvrc_media_packet packet;
            tvrc_media_packet_init(&packet);
            result = tvrc_media_read(core_handle, &packet, media_payload, 4u * 1024u * 1024u);
            if (result == TVRC_OK) {
                HRESULT media_result;
                media_processed = TRUE;
                if (packet.track == 1) {
                    media_result = tvrc_windows_media_submit_video(
                        native_media, packet.flags, packet.codec_config_id,
                        packet.presentation_time_us, packet.width, packet.height,
                        media_payload, packet.payload_len);
                    if (FAILED(media_result) && !video_failure_reported) {
                        video_failure_reported = TRUE;
                        PostMessageW(main_window, WM_TVRC_MEDIA_ERROR, 1, (LPARAM)media_result);
                    }
                } else if (packet.track == 2) {
                    media_result = tvrc_windows_media_submit_audio(
                        native_media, packet.flags, packet.codec_config_id,
                        packet.presentation_time_us, media_payload, packet.payload_len);
                    if (FAILED(media_result) && !audio_failure_reported) {
                        audio_failure_reported = TRUE;
                        PostMessageW(main_window, WM_TVRC_MEDIA_ERROR, 2, (LPARAM)media_result);
                    }
                }
            }
        }
#endif
        if (posted == NULL) return 1;
        tvrc_event_init(&posted->event);
        result = tvrc_poll_event(core_handle, &posted->event, posted->payload, 1024);
        if (result == TVRC_NOT_FOUND) {
            free(posted);
#if defined(_WIN64)
            if (!media_processed) Sleep(20);
#else
            Sleep(20);
#endif
            continue;
        }
        if (result != TVRC_OK) {
            free(posted);
            Sleep(20);
            continue;
        }
        posted->payload[posted->event.payload_len] = 0;
        if (!PostMessageW(main_window, WM_TVRC_EVENT, 0, (LPARAM)posted)) {
            free(posted);
            return 1;
        }
    }
#if defined(_WIN64)
    InterlockedExchangePointer(&native_media_pointer, NULL);
    if (native_media != NULL) tvrc_windows_media_destroy(native_media);
    free(media_payload);
#endif
    return 0;
}

static void handle_core_event(posted_core_event *posted) {
    const char *payload;
    if (posted == NULL) return;
    payload = (const char *)posted->payload;
    if (posted->event.event_type == TVRC_EVENT_DEVICE_FOUND) {
        apply_device_event(payload);
        set_status_utf8(posted->payload, posted->event.payload_len);
    } else if (posted->event.event_type == TVRC_EVENT_PAIRING_SAS) {
        static const char sas_prefix[] = "\"sas\":\"";
        const char *sas = strstr(payload, sas_prefix);
        if (sas != NULL) {
            wchar_t message[96];
            sas += sizeof(sas_prefix) - 1;
            if (strlen(sas) >= 6) {
                wsprintfW(message, L"SAS：%c%c%c%c%c%c（请与电视画面核对后在电视端确认）",
                          sas[0], sas[1], sas[2], sas[3], sas[4], sas[5]);
                SetWindowTextW(sas_label, message);
            }
        }
        SetWindowTextW(status_label, L"已安全计算 SAS；等待电视端本地确认并下发凭据…");
    } else if (posted->event.event_type == TVRC_EVENT_CAPABILITIES_CHANGED) {
        apply_capabilities_event(payload);
    } else if (posted->event.event_type == TVRC_EVENT_STATE_CHANGED) {
        apply_state_event(payload);
        set_status_utf8(posted->payload, posted->event.payload_len);
    } else if (posted->event.event_type == TVRC_EVENT_COMMAND_ACK) {
        int index;
        for (index = 0; index < 5; ++index) {
            if (remote_down_request_ids[index] == posted->event.request_id) {
                remote_down_request_ids[index] = 0;
                if (posted->event.status != TVRC_OK) cancel_remote_key_local(index);
                break;
            }
        }
        set_status_utf8(posted->payload, posted->event.payload_len);
    } else if (posted->event.event_type == TVRC_EVENT_ADB_STATE_CHANGED) {
        char state[64];
        if (!json_string_field(payload, "state", state, sizeof(state))) {
            append_adb_diagnostic((const uint8_t *)"{\"error\":\"adb_event_json_invalid\"}", 34);
        } else if (strcmp(state, "ready") == 0) {
            char ready_path[1024];
            adb_ready = FALSE;
            adb_upgrade_required = FALSE;
            if (json_string_field(payload, "path", ready_path, sizeof(ready_path))) {
                int count;
                wchar_t wide_path[1024];
                count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, ready_path, -1, wide_path, 1024);
                if (count > 0) {
                    adb_persisted_path_len = (uint32_t)strlen(ready_path);
                    memcpy(adb_persisted_path, ready_path, adb_persisted_path_len);
                    if (adb_path_edit != NULL) SetWindowTextW(adb_path_edit, wide_path);
                    if (!save_adb_path_utf8(ready_path)) append_adb_diagnostic((const uint8_t *)"{\"error\":\"adb_path_registry_write_failed\"}", 42);
                    adb_ready = TRUE;
                }
                SecureZeroMemory(wide_path, sizeof(wide_path));
                SecureZeroMemory(ready_path, sizeof(ready_path));
            }
        } else if (strcmp(state, "upgrade_required") == 0) {
            adb_ready = FALSE;
            adb_upgrade_required = TRUE;
        } else if (strcmp(state, "disabled") == 0 || strcmp(state, "failed") == 0 ||
                   strcmp(state, "unsupported") == 0) {
            adb_ready = FALSE;
        }
        SecureZeroMemory(state, sizeof(state));
        append_adb_diagnostic(posted->payload, posted->event.payload_len);
        set_status_utf8(posted->payload, posted->event.payload_len);
        update_control_enablement();
    } else if (posted->event.event_type == TVRC_EVENT_ADB_DEVICE_FOUND) {
        char serial[256];
        char authorization[32];
        append_adb_diagnostic(posted->payload, posted->event.payload_len);
        if (adb_device_list != NULL && adb_device_serial_count < 32 &&
            json_string_field(payload, "authorization", authorization, sizeof(authorization)) &&
            strcmp(authorization, "authorized") == 0 &&
            json_string_field(payload, "serial", serial, sizeof(serial))) {
            wchar_t serial_w[256];
            int count;
            count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, serial, -1, serial_w, 256);
            if (count > 0 && SendMessageW(adb_device_list, LB_ADDSTRING, 0, (LPARAM)serial_w) != LB_ERR) {
                memcpy(adb_device_serials[adb_device_serial_count], serial, sizeof(serial));
                adb_device_serial_count++;
            }
            SecureZeroMemory(serial_w, sizeof(serial_w));
            SecureZeroMemory(serial, sizeof(serial));
        }
        SecureZeroMemory(authorization, sizeof(authorization));
    } else if (posted->event.event_type == TVRC_EVENT_MEDIA_STATE_CHANGED) {
        if (strstr(payload, "\"state\":\"streaming\"") != NULL) {
            media_active = TRUE;
            SetWindowTextW(preview_label, L"");
            SetWindowTextW(media_button, L"停止画面");
        } else if (strstr(payload, "\"state\":\"video_only\"") != NULL) {
            media_active = TRUE;
            SetWindowTextW(status_label, L"系统音频不可用；继续显示静音画面。");
            SetWindowTextW(media_button, L"停止画面");
        } else if (strstr(payload, "\"state\":\"stopped\"") != NULL ||
                   strstr(payload, "\"state\":\"failed\"") != NULL ||
                   strstr(payload, "\"state\":\"unsupported\"") != NULL) {
            media_active = FALSE;
            media_request_pending = FALSE;
            SetWindowTextW(media_button, L"画面");
            SetWindowTextW(preview_label, L"实时画面：等待媒体会话");
#if defined(_WIN64)
            InterlockedExchange(&native_media_reset_requested, 1);
#endif
        }
        update_control_enablement();
    } else if (posted->event.event_type == TVRC_EVENT_REQUEST_COMPLETE ||
               posted->event.event_type == TVRC_EVENT_ERROR) {
        if (posted->event.event_type == TVRC_EVENT_REQUEST_COMPLETE &&
            posted->event.request_id == media_request_id) {
            media_request_pending = FALSE;
            if (posted->event.status == TVRC_OK) {
                media_active = media_requested_start;
                SetWindowTextW(media_button, media_active ? L"停止画面" : L"画面");
                SetWindowTextW(
                    preview_label,
                    media_active ? L"" : L"实时画面：等待媒体会话");
                if (!media_active) {
#if defined(_WIN64)
                    InterlockedExchange(&native_media_reset_requested, 1);
#endif
                }
            }
            update_control_enablement();
        }
        if (posted->event.event_type == TVRC_EVENT_REQUEST_COMPLETE &&
            posted->event.status == TVRC_OK && strstr(payload, "\"status\":\"adb_installed\"") != NULL &&
            adb_enabled) {
            submit_adb_probe();
        }
        set_status_utf8(posted->payload, posted->event.payload_len);
    }
    free(posted);
}

static BOOL submit_key_state(uint32_t key, uint32_t state, uint64_t *submitted_request_id) {
    uint64_t request_id = (uint64_t)InterlockedIncrement64(&next_request_id);
    tvrc_result result = tvrc_send_key(core_handle, request_id, key, state);
    if (result != TVRC_OK) {
        SetWindowTextW(status_label, L"按键未入队：当前没有可用的认证控制会话。");
        return FALSE;
    }
    if (submitted_request_id != NULL) *submitted_request_id = request_id;
    return TRUE;
}

static void submit_key(uint32_t key) {
    (void)submit_key_state(key, TVRC_KEY_PRESS, NULL);
}

static BOOL select_target(void) {
    uint8_t address[256];
    uint32_t address_len = 0;
    tvrc_result result;
    if (!read_edit_utf8(target_edit, address, sizeof(address), &address_len)) {
        SetWindowTextW(status_label, L"请输入电视 IP，例如 192.168.1.20；端口固定为 47832。");
        return FALSE;
    }
    result = tvrc_target_set(core_handle, address, address_len);
    if (result != TVRC_OK) {
        SetWindowTextW(status_label, L"目标地址无效或核心正忙；仅接受 IP 与固定端口 47832。");
        return FALSE;
    }
    return TRUE;
}

static void submit_pairing(void) {
    uint8_t code[16];
    uint32_t code_len = 0;
    uint64_t request_id;
    int index;
    if (!select_target()) return;
    if (!read_edit_utf8(pairing_code_edit, code, sizeof(code), &code_len) || code_len != 6) {
        SetWindowTextW(status_label, L"请输入电视显示的 6 位配对码。");
        return;
    }
    for (index = 0; index < 6; ++index) {
        if (code[index] < '0' || code[index] > '9') {
            SecureZeroMemory(code, sizeof(code));
            SetWindowTextW(status_label, L"配对码必须正好是 6 位数字。");
            return;
        }
    }
    request_id = (uint64_t)InterlockedIncrement64(&next_request_id);
    if (tvrc_pair_submit(core_handle, request_id, code, code_len) == TVRC_OK) {
        core_busy = TRUE;
        update_control_enablement();
        SetWindowTextW(pairing_code_edit, L"");
        SetWindowTextW(sas_label, L"SAS：等待计算");
        SetWindowTextW(status_label, L"正在建立首次配对 TLS 连接…");
    } else {
        SetWindowTextW(status_label, L"配对请求未入队；请等待当前操作结束。");
    }
    SecureZeroMemory(code, sizeof(code));
}

static void submit_connect(void) {
    uint8_t address[256];
    uint32_t address_len = 0;
    uint64_t request_id;
    if (!read_edit_utf8(target_edit, address, sizeof(address), &address_len)) {
        SetWindowTextW(status_label, L"请先发现电视或手动输入电视 IP。");
        return;
    }
    request_id = (uint64_t)InterlockedIncrement64(&next_request_id);
    if (tvrc_connect(core_handle, request_id, address, address_len) == TVRC_OK) {
        core_busy = TRUE;
        update_control_enablement();
        SetWindowTextW(status_label, L"正在校验证书 pin 并认证控制会话…");
    } else {
        SetWindowTextW(status_label, L"连接请求未入队；请等待当前操作结束。");
    }
}

static void submit_disconnect(void) {
    uint64_t request_id = (uint64_t)InterlockedIncrement64(&next_request_id);
    if (tvrc_disconnect(core_handle, request_id) == TVRC_OK) {
        core_busy = TRUE;
        update_control_enablement();
        SetWindowTextW(status_label, L"正在安全断开控制会话…");
    } else {
        SetWindowTextW(status_label, L"断开请求未入队。");
    }
}

static void submit_media_toggle(void) {
    tvrc_result result;
    if (!core_connected || media_request_pending) return;
    media_request_id = (uint64_t)InterlockedIncrement64(&next_request_id);
    media_requested_start = !media_active;
    if (media_requested_start) media_backend_adb = adb_enabled && adb_ready && adb_selected_serial[0] != '\0';
    if (media_backend_adb) {
        result = media_requested_start
            ? tvrc_adb_media_start(core_handle, media_request_id, (const uint8_t *)adb_selected_serial,
                                   (uint32_t)strlen(adb_selected_serial), TVRC_ADB_MEDIA_AUDIO)
            : tvrc_adb_media_stop(core_handle, media_request_id);
    } else {
        result = media_requested_start
            ? tvrc_media_start(core_handle, media_request_id)
            : tvrc_media_stop(core_handle, media_request_id);
    }
    if (result == TVRC_OK) {
        media_request_pending = TRUE;
        update_control_enablement();
        SetWindowTextW(
            status_label,
            media_requested_start ? L"正在请求电视端本地画面授权…" : L"正在停止画面与音频…");
    } else {
        SetWindowTextW(status_label, L"媒体请求未入队；基础遥控保持可用。");
    }
}

static void cancel_remote_key_local(int index) {
    if (index < 0 || index >= 5 || !remote_pressed[index]) return;
    remote_pressed[index] = FALSE;
    remote_repeating[index] = FALSE;
    remote_down_request_ids[index] = 0;
    KillTimer(remote_buttons[index], ID_KEY_REPEAT_TIMER);
    SendMessageW(remote_buttons[index], BM_SETSTATE, FALSE, 0);
    if (GetCapture() == remote_buttons[index]) ReleaseCapture();
}

static void release_remote_key(int index) {
    if (index < 0 || index >= 5 || !remote_pressed[index]) return;
    cancel_remote_key_local(index);
    (void)submit_key_state((uint32_t)index, TVRC_KEY_UP, NULL);
}

static void release_all_remote_keys(void) {
    int index;
    for (index = 0; index < 5; ++index) release_remote_key(index);
}

static LRESULT CALLBACK remote_button_proc(HWND button, UINT message, WPARAM w_param, LPARAM l_param) {
    int index = (int)GetWindowLongPtrW(button, GWLP_USERDATA);
    uint64_t submitted_request_id = 0;
    WNDPROC original = (index >= 0 && index < 5) ? remote_button_procs[index] : DefWindowProcW;
    switch (message) {
    case WM_LBUTTONDOWN:
        if (!remote_pressed[index] && IsWindowEnabled(button) &&
            submit_key_state((uint32_t)index, TVRC_KEY_DOWN, &submitted_request_id)) {
            remote_pressed[index] = TRUE;
            remote_repeating[index] = FALSE;
            remote_down_request_ids[index] = submitted_request_id;
            SetCapture(button);
            SendMessageW(button, BM_SETSTATE, TRUE, 0);
            SetTimer(button, ID_KEY_REPEAT_TIMER, 400, NULL);
        }
        SetFocus(button);
        return 0;
    case WM_LBUTTONUP:
        release_remote_key(index);
        return 0;
    case WM_TIMER:
        if (w_param == ID_KEY_REPEAT_TIMER && remote_pressed[index]) {
            if (!remote_repeating[index]) {
                remote_repeating[index] = TRUE;
                SetTimer(button, ID_KEY_REPEAT_TIMER, 120, NULL);
            }
            (void)submit_key_state((uint32_t)index, TVRC_KEY_REPEAT, NULL);
            return 0;
        }
        break;
    case WM_KEYDOWN:
        if (w_param == VK_SPACE && !remote_pressed[index] && IsWindowEnabled(button) &&
            submit_key_state((uint32_t)index, TVRC_KEY_DOWN, &submitted_request_id)) {
            remote_pressed[index] = TRUE;
            remote_repeating[index] = FALSE;
            remote_down_request_ids[index] = submitted_request_id;
            SendMessageW(button, BM_SETSTATE, TRUE, 0);
            SetTimer(button, ID_KEY_REPEAT_TIMER, 400, NULL);
            return 0;
        }
        break;
    case WM_KEYUP:
        if (w_param == VK_SPACE) {
            release_remote_key(index);
            return 0;
        }
        break;
    case WM_KILLFOCUS:
    case WM_CANCELMODE:
    case WM_CAPTURECHANGED:
        release_remote_key(index);
        break;
    case WM_ENABLE:
        if (!w_param) release_remote_key(index);
        break;
    default:
        break;
    }
    return CallWindowProcW(original, button, message, w_param, l_param);
}

static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM w_param, LPARAM l_param) {
    switch (message) {
    case WM_CREATE: {
        status_label = make_control(window, L"STATIC", L"可刷新发现电视，或手动输入电视 IP 后配对/连接。", SS_LEFT, 0);
        preview_label = make_control(window, L"STATIC", L"实时画面：等待媒体会话（DRM/HDCP 内容可能黑屏）", SS_CENTER | WS_BORDER, 0);
        refresh_button = make_control(window, L"BUTTON", L"刷新", BS_PUSHBUTTON, ID_REFRESH);
        adb_button = make_control(window, L"BUTTON", L"ADB 设置", BS_PUSHBUTTON, ID_ADB_SETUP);
        target_edit = make_control(window, L"COMBOBOX", L"", CBS_DROPDOWN | CBS_AUTOHSCROLL | WS_VSCROLL, ID_TARGET);
        pairing_code_edit = make_control(window, L"EDIT", L"", WS_BORDER | ES_NUMBER | ES_CENTER, ID_PAIR_CODE);
        pair_button = make_control(window, L"BUTTON", L"配对", BS_PUSHBUTTON, ID_PAIR);
        connect_button = make_control(window, L"BUTTON", L"连接", BS_PUSHBUTTON, ID_CONNECT);
        disconnect_button = make_control(window, L"BUTTON", L"断开", BS_PUSHBUTTON | WS_DISABLED, ID_DISCONNECT);
        media_button = make_control(window, L"BUTTON", L"画面", BS_PUSHBUTTON | WS_DISABLED, ID_MEDIA);
        sas_label = make_control(window, L"STATIC", L"SAS：尚未配对", SS_LEFT | SS_CENTERIMAGE | WS_BORDER, 0);
        SendMessageW(target_edit, CB_LIMITTEXT, 255, 0);
        SendMessageW(pairing_code_edit, EM_SETLIMITTEXT, 6, 0);
        const wchar_t *labels[] = {
            L"上", L"下", L"左", L"右", L"确定", L"返回", L"主页",
            L"音量+", L"音量-", L"静音", L"播放/暂停", L"停止", L"下一首", L"上一首"
        };
        const int ids[] = {
            ID_UP, ID_DOWN, ID_LEFT, ID_RIGHT, ID_OK, ID_BACK, ID_HOME,
            ID_VOLUME_UP, ID_VOLUME_DOWN, ID_VOLUME_MUTE, ID_MEDIA_PLAY_PAUSE, ID_MEDIA_STOP,
            ID_MEDIA_NEXT, ID_MEDIA_PREVIOUS
        };
        for (int index = 0; index < 14; ++index) {
            remote_buttons[index] = make_control(window, L"BUTTON", labels[index], BS_PUSHBUTTON | WS_DISABLED, ids[index]);
            if (index < 5) {
                SetWindowLongPtrW(remote_buttons[index], GWLP_USERDATA, (LONG_PTR)index);
                remote_button_procs[index] = (WNDPROC)SetWindowLongPtrW(
                    remote_buttons[index], GWLP_WNDPROC, (LONG_PTR)remote_button_proc);
            }
        }
        update_control_enablement();
        return 0;
    }
    case WM_COMMAND:
        switch (LOWORD(w_param)) {
        case ID_REFRESH: {
            uint64_t request_id = (uint64_t)InterlockedIncrement64(&next_request_id);
            tvrc_result result = tvrc_discover(core_handle, request_id);
            if (result == TVRC_OK) {
                core_busy = TRUE;
                update_control_enablement();
                SetWindowTextW(status_label, L"正在通过局域网发现电视端 APK…");
            } else {
                SetWindowTextW(status_label, L"发现请求未入队；请稍后重试。");
            }
            return 0;
        }
        case ID_ADB_SETUP: show_adb_settings(window); return 0;
        case ID_PAIR: submit_pairing(); return 0;
        case ID_CONNECT: submit_connect(); return 0;
        case ID_DISCONNECT:
            release_all_remote_keys();
            submit_disconnect();
            return 0;
        case ID_MEDIA: submit_media_toggle(); return 0;
        case ID_UP:
        case ID_DOWN:
        case ID_LEFT:
        case ID_RIGHT:
        case ID_OK:
            return 0;
        case ID_BACK: submit_key(TVRC_KEY_BACK); return 0;
        case ID_HOME: submit_key(TVRC_KEY_HOME); return 0;
        case ID_VOLUME_UP: submit_key(TVRC_KEY_VOLUME_UP); return 0;
        case ID_VOLUME_DOWN: submit_key(TVRC_KEY_VOLUME_DOWN); return 0;
        case ID_VOLUME_MUTE: submit_key(TVRC_KEY_VOLUME_MUTE); return 0;
        case ID_MEDIA_PLAY_PAUSE: submit_key(TVRC_KEY_MEDIA_PLAY_PAUSE); return 0;
        case ID_MEDIA_STOP: submit_key(TVRC_KEY_MEDIA_STOP); return 0;
        case ID_MEDIA_NEXT: submit_key(TVRC_KEY_MEDIA_NEXT); return 0;
        case ID_MEDIA_PREVIOUS: submit_key(TVRC_KEY_MEDIA_PREVIOUS); return 0;
        default: return 0;
        }
    case WM_TVRC_EVENT:
        handle_core_event((posted_core_event *)l_param);
        return 0;
    case WM_TVRC_MEDIA_ERROR:
        if (w_param == 2) {
            SetWindowTextW(status_label, L"系统音频不可用；继续显示静音画面。");
        } else if (w_param == 1) {
            SetWindowTextW(status_label, L"原生视频解码失败；基础遥控保持可用。");
        } else {
            SetWindowTextW(status_label, L"本机原生媒体层不可用；基础遥控保持可用。");
            EnableWindow(media_button, FALSE);
        }
        return 0;
    case WM_ACTIVATE:
        if (LOWORD(w_param) == WA_INACTIVE) release_all_remote_keys();
        return 0;
    case WM_CLOSE:
        if (core_connected && !pending_close) {
            pending_close = TRUE;
            release_all_remote_keys();
            submit_disconnect();
            SetTimer(window, ID_CLOSE_TIMEOUT, 7000, NULL);
            return 0;
        }
        DestroyWindow(window);
        return 0;
    case WM_TIMER:
        if (w_param == ID_CLOSE_TIMEOUT) {
            KillTimer(window, ID_CLOSE_TIMEOUT);
            DestroyWindow(window);
            return 0;
        }
        break;
    case WM_SIZE:
        layout(window);
#if defined(_WIN64)
        {
            tvrc_windows_media *media = (tvrc_windows_media *)InterlockedCompareExchangePointer(
                &native_media_pointer, NULL, NULL);
            if (media != NULL) (void)tvrc_windows_media_resize(media);
        }
#endif
        return 0;
    case WM_DPICHANGED: {
        const RECT *suggested = (const RECT *)l_param;
        SetWindowPos(window, NULL, suggested->left, suggested->top,
                     suggested->right - suggested->left, suggested->bottom - suggested->top,
                     SWP_NOACTIVATE | SWP_NOZORDER);
        return 0;
    }
    case WM_DESTROY:
        release_all_remote_keys();
        InterlockedExchange(&event_thread_stop, 1);
        if (core_handle != NULL) {
            tvrc_stop(core_handle);
        }
        if (event_thread != NULL) {
            WaitForSingleObject(event_thread, INFINITE);
            CloseHandle(event_thread);
            event_thread = NULL;
        }
        if (core_handle != NULL) {
            tvrc_destroy(core_handle);
            core_handle = NULL;
        }
        {
            MSG pending;
            while (PeekMessageW(&pending, window, WM_TVRC_EVENT, WM_TVRC_EVENT, PM_REMOVE)) {
                free((posted_core_event *)pending.lParam);
            }
        }
        PostQuitMessage(0);
        return 0;
    default:
        return DefWindowProcW(window, message, w_param, l_param);
    }
}

int tvrc_windows_run(void) {
    tvrc_config config;
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    tvrc_config_init(&config);
    if (tvrc_create(&config, &core_handle) != TVRC_OK || core_handle == NULL) return 1;
    if (tvrc_start(core_handle) != TVRC_OK) {
        tvrc_destroy(core_handle);
        core_handle = NULL;
        return 1;
    }
    load_adb_settings();

    const wchar_t class_name[] = L"TvRemoteControlWindow";
    WNDCLASSW definition = {0};
    definition.lpfnWndProc = window_proc;
    definition.hInstance = GetModuleHandleW(NULL);
    definition.lpszClassName = class_name;
    definition.hCursor = LoadCursorW(NULL, IDC_ARROW);
    definition.hbrBackground = GetSysColorBrush(COLOR_WINDOW);
    if (RegisterClassW(&definition) == 0) {
        tvrc_stop(core_handle);
        tvrc_destroy(core_handle);
        core_handle = NULL;
        return 1;
    }

    HWND window = CreateWindowExW(0, class_name, L"TV Remote Control", WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                                  CW_USEDEFAULT, CW_USEDEFAULT, 1100, 720, NULL, NULL, definition.hInstance, NULL);
    if (window == NULL) {
        tvrc_stop(core_handle);
        tvrc_destroy(core_handle);
        core_handle = NULL;
        return 1;
    }
    main_window = window;
    event_thread_stop = 0;
    event_thread = CreateThread(NULL, 0, event_pump, NULL, 0, NULL);
    if (event_thread == NULL) {
        DestroyWindow(window);
        return 1;
    }
    if (adb_enabled) submit_adb_probe();
    MSG message;
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    return (int)message.wParam;
}
