#ifndef TVRC_CREDENTIAL_STORE_H
#define TVRC_CREDENTIAL_STORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    TVRC_CREDENTIAL_OK = 0,
    TVRC_CREDENTIAL_INVALID_ARGUMENT = 1,
    TVRC_CREDENTIAL_UNSUPPORTED = 3,
    TVRC_CREDENTIAL_BUFFER_TOO_SMALL = 4,
    TVRC_CREDENTIAL_IO_ERROR = 5,
    TVRC_CREDENTIAL_NOT_FOUND = 6
};

typedef int32_t (*tvrc_credentials_put_fn)(void *context,
                                           const uint8_t *credential_id,
                                           uint32_t credential_id_len,
                                           const uint8_t *secret,
                                           uint32_t secret_len);
typedef int32_t (*tvrc_credentials_get_fn)(void *context,
                                           const uint8_t *credential_id,
                                           uint32_t credential_id_len,
                                           uint8_t *secret,
                                           uint32_t secret_capacity,
                                           uint32_t *secret_len);
typedef int32_t (*tvrc_credentials_remove_fn)(void *context,
                                              const uint8_t *credential_id,
                                              uint32_t credential_id_len);

int32_t tvrc_platform_credentials_put(void *context,
                                      const uint8_t *credential_id,
                                      uint32_t credential_id_len,
                                      const uint8_t *secret,
                                      uint32_t secret_len);
int32_t tvrc_platform_credentials_get(void *context,
                                      const uint8_t *credential_id,
                                      uint32_t credential_id_len,
                                      uint8_t *secret,
                                      uint32_t secret_capacity,
                                      uint32_t *secret_len);
int32_t tvrc_platform_credentials_remove(void *context,
                                         const uint8_t *credential_id,
                                         uint32_t credential_id_len);

#ifdef __cplusplus
}
#endif
#endif
