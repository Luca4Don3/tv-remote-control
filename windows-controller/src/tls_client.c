// glibc 在严格 ISO C 模式下默认不暴露 addrinfo/getaddrinfo；
// POSIX.1-2008 特性宏必须在任何系统头包含之前声明（macOS 头文件不受此约束）
#if !defined(_WIN32)
#define _POSIX_C_SOURCE 200809L
#endif

#include "tls_client.h"

#include <stdlib.h>
#include <stdatomic.h>
#include <string.h>

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <unistd.h>
#endif

#include <mbedtls/ctr_drbg.h>
#include <mbedtls/constant_time.h>
#include <mbedtls/entropy.h>
#include <mbedtls/net_sockets.h>
#include <mbedtls/sha256.h>
#include <mbedtls/ssl.h>
#include <mbedtls/x509_crt.h>

struct tvrc_tls_client {
    mbedtls_net_context network;
    mbedtls_ssl_context ssl;
    mbedtls_ssl_config config;
    mbedtls_ctr_drbg_context random;
    mbedtls_entropy_context entropy;
    uint8_t peer_fingerprint[32];
    int32_t last_error;
    int connected;
    atomic_int cancel_fd;
    atomic_int cancel_requested;
#if defined(_WIN32)
    int winsock_started;
#endif
};

#define TVRC_CONNECT_TIMEOUT_MS 10000
#define TVRC_CONNECT_POLL_MS 100
#define TVRC_HANDSHAKE_TIMEOUT_MS 10000

static int tvrc_socket_set_blocking(int fd, int blocking) {
#if defined(_WIN32)
    u_long mode = blocking ? 0UL : 1UL;
    return ioctlsocket((SOCKET)fd, FIONBIO, &mode) == 0 ? 0 : -1;
#else
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    if (blocking) flags &= ~O_NONBLOCK;
    else flags |= O_NONBLOCK;
    return fcntl(fd, F_SETFL, flags) == 0 ? 0 : -1;
#endif
}

static int tvrc_connect_in_progress(void) {
#if defined(_WIN32)
    int error = WSAGetLastError();
    return error == WSAEWOULDBLOCK || error == WSAEINPROGRESS || error == WSAEINVAL;
#else
    return errno == EINPROGRESS || errno == EWOULDBLOCK;
#endif
}

static int tvrc_wait_connected(tvrc_tls_client *client, int fd) {
    int elapsed_ms = 0;
    while (elapsed_ms < TVRC_CONNECT_TIMEOUT_MS) {
        fd_set write_set;
        fd_set error_set;
        struct timeval timeout;
        int selected;
        int socket_error = 0;
#if defined(_WIN32)
        int socket_error_len = (int)sizeof(socket_error);
#else
        socklen_t socket_error_len = (socklen_t)sizeof(socket_error);
#endif
        if (atomic_load_explicit(&client->cancel_requested, memory_order_acquire)) {
            return MBEDTLS_ERR_NET_CONNECT_FAILED;
        }
        FD_ZERO(&write_set);
        FD_ZERO(&error_set);
        FD_SET((unsigned)fd, &write_set);
        FD_SET((unsigned)fd, &error_set);
        timeout.tv_sec = 0;
        timeout.tv_usec = TVRC_CONNECT_POLL_MS * 1000;
        selected = select(fd + 1, NULL, &write_set, &error_set, &timeout);
        if (selected == 0) {
            elapsed_ms += TVRC_CONNECT_POLL_MS;
            continue;
        }
        if (selected < 0) {
#if defined(_WIN32)
            if (WSAGetLastError() == WSAEINTR) continue;
#else
            if (errno == EINTR) continue;
#endif
            return MBEDTLS_ERR_NET_CONNECT_FAILED;
        }
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, (char *)&socket_error, &socket_error_len) != 0 ||
            socket_error != 0 || FD_ISSET((unsigned)fd, &error_set)) {
            return MBEDTLS_ERR_NET_CONNECT_FAILED;
        }
        return 0;
    }
    return MBEDTLS_ERR_NET_CONNECT_FAILED;
}

static int tvrc_net_connect(tvrc_tls_client *client, const char *host, const char *port) {
    struct addrinfo hints;
    struct addrinfo *addresses = NULL;
    struct addrinfo *current;
    int result = MBEDTLS_ERR_NET_UNKNOWN_HOST;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    hints.ai_flags = AI_NUMERICHOST | AI_NUMERICSERV;
    if (getaddrinfo(host, port, &hints, &addresses) != 0) return MBEDTLS_ERR_NET_UNKNOWN_HOST;
    for (current = addresses; current != NULL; current = current->ai_next) {
        int fd;
        if (atomic_load_explicit(&client->cancel_requested, memory_order_acquire)) {
            result = MBEDTLS_ERR_NET_CONNECT_FAILED;
            break;
        }
        fd = (int)socket(current->ai_family, current->ai_socktype, current->ai_protocol);
        if (fd < 0) {
            result = MBEDTLS_ERR_NET_SOCKET_FAILED;
            continue;
        }
        client->network.fd = fd;
        atomic_store_explicit(&client->cancel_fd, fd, memory_order_release);
        if (tvrc_socket_set_blocking(fd, 0) != 0) {
            result = MBEDTLS_ERR_NET_SOCKET_FAILED;
        } else if (connect(fd, current->ai_addr, (int)current->ai_addrlen) == 0) {
            result = 0;
        } else if (tvrc_connect_in_progress()) {
            result = tvrc_wait_connected(client, fd);
        } else {
            result = MBEDTLS_ERR_NET_CONNECT_FAILED;
        }
        if (result == 0 && tvrc_socket_set_blocking(fd, 1) == 0) break;
        if (result == 0) result = MBEDTLS_ERR_NET_SOCKET_FAILED;
        atomic_store_explicit(&client->cancel_fd, -1, memory_order_release);
        mbedtls_net_free(&client->network);
        mbedtls_net_init(&client->network);
    }
    freeaddrinfo(addresses);
    return result;
}

static void tvrc_tls_client_reset(tvrc_tls_client *client) {
    atomic_store_explicit(&client->cancel_fd, -1, memory_order_release);
    if (client->connected) {
        (void)mbedtls_ssl_close_notify(&client->ssl);
    }
    mbedtls_net_free(&client->network);
    mbedtls_ssl_free(&client->ssl);
    mbedtls_ssl_config_free(&client->config);
    mbedtls_ctr_drbg_free(&client->random);
    mbedtls_entropy_free(&client->entropy);
    mbedtls_net_init(&client->network);
    mbedtls_ssl_init(&client->ssl);
    mbedtls_ssl_config_init(&client->config);
    mbedtls_ctr_drbg_init(&client->random);
    mbedtls_entropy_init(&client->entropy);
    memset(client->peer_fingerprint, 0, sizeof(client->peer_fingerprint));
    client->connected = 0;
}

tvrc_tls_client *tvrc_tls_client_create(void) {
    tvrc_tls_client *client = calloc(1, sizeof(*client));
    if (client == NULL) return NULL;
    mbedtls_net_init(&client->network);
    mbedtls_ssl_init(&client->ssl);
    mbedtls_ssl_config_init(&client->config);
    mbedtls_ctr_drbg_init(&client->random);
    mbedtls_entropy_init(&client->entropy);
    atomic_init(&client->cancel_fd, -1);
    atomic_init(&client->cancel_requested, 0);
#if defined(_WIN32)
    {
        WSADATA data;
        if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
            free(client);
            return NULL;
        }
        client->winsock_started = 1;
    }
#endif
    return client;
}

void tvrc_tls_client_destroy(tvrc_tls_client *client) {
    if (client == NULL) return;
    tvrc_tls_client_reset(client);
#if defined(_WIN32)
    if (client->winsock_started) WSACleanup();
#endif
    free(client);
}

void tvrc_tls_client_close(tvrc_tls_client *client) {
    if (client == NULL) return;
    tvrc_tls_client_reset(client);
    client->last_error = 0;
}

void tvrc_tls_client_prepare(tvrc_tls_client *client) {
    if (client == NULL) return;
    atomic_store_explicit(&client->cancel_requested, 0, memory_order_release);
}

void tvrc_tls_client_cancel(tvrc_tls_client *client) {
    int fd;
    if (client == NULL) return;
    atomic_store_explicit(&client->cancel_requested, 1, memory_order_release);
    fd = atomic_load_explicit(&client->cancel_fd, memory_order_acquire);
    if (fd < 0) return;
#if defined(_WIN32)
    (void)shutdown((SOCKET)fd, SD_BOTH);
#else
    (void)shutdown(fd, SHUT_RDWR);
#endif
}

int32_t tvrc_tls_client_connect(tvrc_tls_client *client, const char *host, const char *port,
                                const uint8_t *expected_fingerprint, uint32_t fingerprint_len,
                                uint32_t read_timeout_ms) {
    static const unsigned char personalization[] = "tv-remote-control-desktop-v1";
    const mbedtls_x509_crt *peer;
    int result;
    if (client == NULL || host == NULL || port == NULL) return -1;
    if (fingerprint_len != 0 && (expected_fingerprint == NULL || fingerprint_len != 32)) return -1;
    tvrc_tls_client_reset(client);

    result = mbedtls_ctr_drbg_seed(&client->random, mbedtls_entropy_func, &client->entropy,
                                   personalization, sizeof(personalization) - 1);
    if (result != 0) goto failed;
    result = mbedtls_ssl_config_defaults(&client->config, MBEDTLS_SSL_IS_CLIENT,
                                         MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT);
    if (result != 0) goto failed;
    mbedtls_ssl_conf_min_tls_version(&client->config, MBEDTLS_SSL_VERSION_TLS1_2);
    mbedtls_ssl_conf_max_tls_version(&client->config, MBEDTLS_SSL_VERSION_TLS1_3);
    /*
     * 设计边界（勿改为 CA 验证）：本产品使用自签证书 + TOFU + SAS 核对。
     * 首连（配对）时 VERIFY_NONE 仅用于读取对端指纹；SAS 六位码由双方
     * 基于证书指纹与随机数独立计算，用户核对通过后才 pin 指纹
     * （control_client.zig beginPair -> promoteAfterSas）。后续连接使用
     * expected_fingerprint 在握手中比对，指纹不符直接失败。因此 VERIFY_NONE
     * 不构成中间人窗口：攻击者在 SAS 核对前无法通过用户确认。
     */
    mbedtls_ssl_conf_authmode(&client->config, MBEDTLS_SSL_VERIFY_NONE);
    mbedtls_ssl_conf_rng(&client->config, mbedtls_ctr_drbg_random, &client->random);
    mbedtls_ssl_conf_read_timeout(&client->config, TVRC_HANDSHAKE_TIMEOUT_MS);
    result = mbedtls_ssl_setup(&client->ssl, &client->config);
    if (result != 0) goto failed;
    result = mbedtls_ssl_set_hostname(&client->ssl, host);
    if (result != 0) goto failed;
    result = tvrc_net_connect(client, host, port);
    if (result != 0) goto failed;
    mbedtls_ssl_set_bio(&client->ssl, &client->network, mbedtls_net_send,
                        mbedtls_net_recv, mbedtls_net_recv_timeout);
    do {
        result = mbedtls_ssl_handshake(&client->ssl);
    } while (result == MBEDTLS_ERR_SSL_WANT_READ || result == MBEDTLS_ERR_SSL_WANT_WRITE);
    if (result != 0) goto failed;
    mbedtls_ssl_conf_read_timeout(&client->config, read_timeout_ms);

    peer = mbedtls_ssl_get_peer_cert(&client->ssl);
    if (peer == NULL || peer->raw.p == NULL || peer->raw.len == 0) {
        result = MBEDTLS_ERR_X509_CERT_UNKNOWN_FORMAT;
        goto failed;
    }
    result = mbedtls_sha256(peer->raw.p, peer->raw.len, client->peer_fingerprint, 0);
    if (result != 0) goto failed;
    if (fingerprint_len == 32 &&
        mbedtls_ct_memcmp(client->peer_fingerprint, expected_fingerprint, 32) != 0) {
        result = MBEDTLS_ERR_X509_CERT_VERIFY_FAILED;
        goto failed;
    }
    client->connected = 1;
    client->last_error = 0;
    return 0;

failed:
    client->last_error = result;
    tvrc_tls_client_reset(client);
    client->last_error = result;
    return result;
}

int32_t tvrc_tls_client_read(tvrc_tls_client *client, uint8_t *buffer, uint32_t capacity) {
    int result;
    if (client == NULL || !client->connected || buffer == NULL || capacity == 0) return -1;
    do {
        result = mbedtls_ssl_read(&client->ssl, buffer, capacity);
    } while (result == MBEDTLS_ERR_SSL_WANT_READ || result == MBEDTLS_ERR_SSL_WANT_WRITE);
    if (result == MBEDTLS_ERR_SSL_TIMEOUT) return TVRC_TLS_CLIENT_TIMEOUT;
    if (result < 0) client->last_error = result;
    return result;
}

int32_t tvrc_tls_client_write(tvrc_tls_client *client, const uint8_t *buffer, uint32_t length) {
    uint32_t written = 0;
    if (client == NULL || !client->connected || (buffer == NULL && length != 0)) return -1;
    while (written < length) {
        int result = mbedtls_ssl_write(&client->ssl, buffer + written, length - written);
        if (result == MBEDTLS_ERR_SSL_WANT_READ || result == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
        if (result <= 0) {
            client->last_error = result;
            return result;
        }
        written += (uint32_t)result;
    }
    return (int32_t)written;
}

int32_t tvrc_tls_client_peer_fingerprint(tvrc_tls_client *client, uint8_t output[32]) {
    if (client == NULL || !client->connected || output == NULL) return -1;
    memcpy(output, client->peer_fingerprint, 32);
    return 0;
}

int32_t tvrc_tls_client_last_error(const tvrc_tls_client *client) {
    return client == NULL ? -1 : client->last_error;
}
