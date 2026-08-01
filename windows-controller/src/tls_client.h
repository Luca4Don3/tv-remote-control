#ifndef TVRC_TLS_CLIENT_H
#define TVRC_TLS_CLIENT_H

#include <stddef.h>
#include <stdint.h>

typedef struct tvrc_tls_client tvrc_tls_client;

enum {
    TVRC_TLS_CLIENT_TIMEOUT = -2
};

tvrc_tls_client *tvrc_tls_client_create(void);
void tvrc_tls_client_destroy(tvrc_tls_client *client);
void tvrc_tls_client_prepare(tvrc_tls_client *client);
void tvrc_tls_client_close(tvrc_tls_client *client);
void tvrc_tls_client_cancel(tvrc_tls_client *client);
int32_t tvrc_tls_client_connect(tvrc_tls_client *client, const char *host, const char *port,
                                const uint8_t *expected_fingerprint, uint32_t fingerprint_len,
                                uint32_t read_timeout_ms);
int32_t tvrc_tls_client_read(tvrc_tls_client *client, uint8_t *buffer, uint32_t capacity);
int32_t tvrc_tls_client_write(tvrc_tls_client *client, const uint8_t *buffer, uint32_t length);
int32_t tvrc_tls_client_peer_fingerprint(tvrc_tls_client *client, uint8_t output[32]);
int32_t tvrc_tls_client_last_error(const tvrc_tls_client *client);

#endif
