#define UNICODE
#define _UNICODE
#include <windows.h>
#include <stdint.h>
#include <string.h>

#include "windows_runtime_paths.h"

int32_t tvrc_windows_companion_path(const char *name, uint8_t *output,
                                    uint32_t capacity, uint32_t *output_len) {
    wchar_t module_path[32768];
    wchar_t name_w[260];
    DWORD module_len;
    int name_len;
    int utf8_len;
    wchar_t *separator;
    if (name == NULL || output == NULL || output_len == NULL || capacity == 0) return 1;
    if (strchr(name, '\\') != NULL || strchr(name, '/') != NULL || strstr(name, "..") != NULL) return 1;
    name_len = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, name, -1, name_w, 260);
    if (name_len <= 1) return 1;
    module_len = GetModuleFileNameW(NULL, module_path, 32768);
    if (module_len == 0 || module_len >= 32768) return 2;
    separator = wcsrchr(module_path, L'\\');
    if (separator == NULL) return 2;
    if ((size_t)(separator - module_path) + 1u + (size_t)name_len >= 32768u) return 3;
    separator[1] = L'\0';
    wcscat_s(module_path, 32768, name_w);
    utf8_len = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, module_path, -1, NULL, 0, NULL, NULL);
    if (utf8_len <= 1 || (uint32_t)utf8_len > capacity) return 3;
    if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, module_path, -1,
                            (char *)output, (int)capacity, NULL, NULL) != utf8_len) return 2;
    *output_len = (uint32_t)(utf8_len - 1);
    return 0;
}
