#include "tvrc_credential_store.h"

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincrypt.h>

static const wchar_t credential_registry_path[] =
    L"Software\\TvRemoteControl\\Credentials";
static const uint8_t credential_entropy_bytes[] = "TVRC-CREDENTIAL-v1";

static int make_value_name(const uint8_t *credential_id, uint32_t credential_id_len,
                           wchar_t output[129]) {
    static const wchar_t hex[] = L"0123456789abcdef";
    uint32_t index;
    if (credential_id == NULL || credential_id_len == 0 || credential_id_len > 64) return 0;
    for (index = 0; index < credential_id_len; ++index) {
        output[index * 2] = hex[credential_id[index] >> 4];
        output[index * 2 + 1] = hex[credential_id[index] & 15];
    }
    output[credential_id_len * 2] = L'\0';
    return 1;
}

static DATA_BLOB credential_entropy(void) {
    DATA_BLOB result;
    result.cbData = (DWORD)(sizeof(credential_entropy_bytes) - 1);
    result.pbData = (BYTE *)credential_entropy_bytes;
    return result;
}

int32_t tvrc_platform_credentials_put(void *context,
                                      const uint8_t *credential_id,
                                      uint32_t credential_id_len,
                                      const uint8_t *secret,
                                      uint32_t secret_len) {
    wchar_t value_name[129];
    DATA_BLOB plaintext;
    DATA_BLOB ciphertext = {0};
    DATA_BLOB entropy = credential_entropy();
    HKEY key = NULL;
    LONG status;
    (void)context;
    if (!make_value_name(credential_id, credential_id_len, value_name) ||
        secret == NULL || secret_len == 0 || secret_len > 4096) {
        return TVRC_CREDENTIAL_INVALID_ARGUMENT;
    }
    plaintext.cbData = secret_len;
    plaintext.pbData = (BYTE *)secret;
    if (!CryptProtectData(&plaintext, L"TV Remote Control credential", &entropy,
                          NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &ciphertext)) {
        return TVRC_CREDENTIAL_IO_ERROR;
    }
    status = RegCreateKeyExW(HKEY_CURRENT_USER, credential_registry_path, 0, NULL, 0,
                             KEY_SET_VALUE, NULL, &key, NULL);
    if (status == ERROR_SUCCESS) {
        status = RegSetValueExW(key, value_name, 0, REG_BINARY,
                                ciphertext.pbData, ciphertext.cbData);
        RegCloseKey(key);
    }
    SecureZeroMemory(ciphertext.pbData, ciphertext.cbData);
    LocalFree(ciphertext.pbData);
    return status == ERROR_SUCCESS ? TVRC_CREDENTIAL_OK : TVRC_CREDENTIAL_IO_ERROR;
}

int32_t tvrc_platform_credentials_get(void *context,
                                      const uint8_t *credential_id,
                                      uint32_t credential_id_len,
                                      uint8_t *secret,
                                      uint32_t secret_capacity,
                                      uint32_t *secret_len) {
    wchar_t value_name[129];
    DATA_BLOB ciphertext = {0};
    DATA_BLOB plaintext = {0};
    DATA_BLOB entropy = credential_entropy();
    HKEY key = NULL;
    DWORD value_type = 0;
    DWORD value_size = 0;
    LONG status;
    int32_t result = TVRC_CREDENTIAL_IO_ERROR;
    (void)context;
    if (!make_value_name(credential_id, credential_id_len, value_name) || secret_len == NULL) {
        return TVRC_CREDENTIAL_INVALID_ARGUMENT;
    }
    status = RegOpenKeyExW(HKEY_CURRENT_USER, credential_registry_path, 0,
                           KEY_QUERY_VALUE, &key);
    if (status == ERROR_FILE_NOT_FOUND) return TVRC_CREDENTIAL_NOT_FOUND;
    if (status != ERROR_SUCCESS) return TVRC_CREDENTIAL_IO_ERROR;
    status = RegQueryValueExW(key, value_name, NULL, &value_type, NULL, &value_size);
    if (status == ERROR_FILE_NOT_FOUND) {
        RegCloseKey(key);
        return TVRC_CREDENTIAL_NOT_FOUND;
    }
    if (status != ERROR_SUCCESS || value_type != REG_BINARY || value_size == 0 || value_size > 16384) {
        RegCloseKey(key);
        return TVRC_CREDENTIAL_IO_ERROR;
    }
    ciphertext.pbData = (BYTE *)malloc(value_size);
    if (ciphertext.pbData == NULL) {
        RegCloseKey(key);
        return TVRC_CREDENTIAL_IO_ERROR;
    }
    ciphertext.cbData = value_size;
    status = RegQueryValueExW(key, value_name, NULL, &value_type,
                              ciphertext.pbData, &value_size);
    RegCloseKey(key);
    if (status != ERROR_SUCCESS) goto cleanup;
    ciphertext.cbData = value_size;
    if (!CryptUnprotectData(&ciphertext, NULL, &entropy, NULL, NULL,
                            CRYPTPROTECT_UI_FORBIDDEN, &plaintext)) {
        goto cleanup;
    }
    if (plaintext.cbData == 0 || plaintext.cbData > 4096) goto cleanup;
    *secret_len = plaintext.cbData;
    if (secret_capacity < plaintext.cbData || secret == NULL) {
        result = TVRC_CREDENTIAL_BUFFER_TOO_SMALL;
        goto cleanup;
    }
    memcpy(secret, plaintext.pbData, plaintext.cbData);
    result = TVRC_CREDENTIAL_OK;

cleanup:
    if (plaintext.pbData != NULL) {
        SecureZeroMemory(plaintext.pbData, plaintext.cbData);
        LocalFree(plaintext.pbData);
    }
    if (ciphertext.pbData != NULL) {
        SecureZeroMemory(ciphertext.pbData, ciphertext.cbData);
        free(ciphertext.pbData);
    }
    return result;
}

int32_t tvrc_platform_credentials_remove(void *context,
                                         const uint8_t *credential_id,
                                         uint32_t credential_id_len) {
    wchar_t value_name[129];
    HKEY key = NULL;
    LONG status;
    (void)context;
    if (!make_value_name(credential_id, credential_id_len, value_name)) {
        return TVRC_CREDENTIAL_INVALID_ARGUMENT;
    }
    status = RegOpenKeyExW(HKEY_CURRENT_USER, credential_registry_path, 0,
                           KEY_SET_VALUE, &key);
    if (status == ERROR_FILE_NOT_FOUND) return TVRC_CREDENTIAL_OK;
    if (status != ERROR_SUCCESS) return TVRC_CREDENTIAL_IO_ERROR;
    status = RegDeleteValueW(key, value_name);
    RegCloseKey(key);
    if (status == ERROR_FILE_NOT_FOUND) return TVRC_CREDENTIAL_OK;
    return status == ERROR_SUCCESS ? TVRC_CREDENTIAL_OK : TVRC_CREDENTIAL_IO_ERROR;
}

#else

int32_t tvrc_platform_credentials_put(void *context,
                                      const uint8_t *credential_id,
                                      uint32_t credential_id_len,
                                      const uint8_t *secret,
                                      uint32_t secret_len) {
    (void)context; (void)credential_id; (void)credential_id_len; (void)secret; (void)secret_len;
    return TVRC_CREDENTIAL_UNSUPPORTED;
}

int32_t tvrc_platform_credentials_get(void *context,
                                      const uint8_t *credential_id,
                                      uint32_t credential_id_len,
                                      uint8_t *secret,
                                      uint32_t secret_capacity,
                                      uint32_t *secret_len) {
    (void)context; (void)credential_id; (void)credential_id_len; (void)secret; (void)secret_capacity;
    if (secret_len != NULL) *secret_len = 0;
    return TVRC_CREDENTIAL_UNSUPPORTED;
}

int32_t tvrc_platform_credentials_remove(void *context,
                                         const uint8_t *credential_id,
                                         uint32_t credential_id_len) {
    (void)context; (void)credential_id; (void)credential_id_len;
    return TVRC_CREDENTIAL_UNSUPPORTED;
}

#endif
