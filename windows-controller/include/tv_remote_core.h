#ifndef TV_REMOTE_CORE_H
#define TV_REMOTE_CORE_H

#include <stdint.h>
#include "tvrc_credential_store.h"

#ifdef __cplusplus
extern "C" {
#endif

#define TVRC_ABI_VERSION 1u

typedef void tvrc_handle;
typedef int32_t tvrc_result;

enum tvrc_result_code {
    TVRC_OK = 0,
    TVRC_INVALID_ARGUMENT = 1,
    TVRC_INVALID_STATE = 2,
    TVRC_UNSUPPORTED = 3,
    TVRC_BUFFER_TOO_SMALL = 4,
    TVRC_QUEUE_FULL = 5,
    TVRC_NOT_FOUND = 6,
    TVRC_IO_ERROR = 7,
    TVRC_CANCELLED = 8,
    TVRC_UNAUTHENTICATED = 9,
    TVRC_INTERNAL_ERROR = 10
};

enum tvrc_event_type {
    TVRC_EVENT_STATE_CHANGED = 1,
    TVRC_EVENT_DEVICE_FOUND = 2,
    TVRC_EVENT_PAIRING_SAS = 3,
    TVRC_EVENT_CAPABILITIES_CHANGED = 4,
    TVRC_EVENT_COMMAND_ACK = 5,
    TVRC_EVENT_REQUEST_COMPLETE = 6,
    TVRC_EVENT_ERROR = 7,
    TVRC_EVENT_MEDIA_STATE_CHANGED = 8,
    TVRC_EVENT_ADB_STATE_CHANGED = 9,
    TVRC_EVENT_ADB_DEVICE_FOUND = 10
};

enum tvrc_adb_install_flags {
    TVRC_ADB_INSTALL_CONFIRM = 1u,
    TVRC_ADB_UPGRADE_CONFIRM = 2u
};

enum tvrc_adb_media_flags {
    TVRC_ADB_MEDIA_AUDIO = 1u
};

enum tvrc_key_state {
    TVRC_KEY_DOWN = 0,
    TVRC_KEY_REPEAT = 1,
    TVRC_KEY_UP = 2,
    TVRC_KEY_PRESS = 3
};

enum tvrc_key {
    TVRC_KEY_DPAD_UP = 0,
    TVRC_KEY_DPAD_DOWN = 1,
    TVRC_KEY_DPAD_LEFT = 2,
    TVRC_KEY_DPAD_RIGHT = 3,
    TVRC_KEY_DPAD_CENTER = 4,
    TVRC_KEY_BACK = 5,
    TVRC_KEY_HOME = 6,
    TVRC_KEY_MENU = 7,
    TVRC_KEY_VOLUME_UP = 8,
    TVRC_KEY_VOLUME_DOWN = 9,
    TVRC_KEY_VOLUME_MUTE = 10,
    TVRC_KEY_CHANNEL_UP = 11,
    TVRC_KEY_CHANNEL_DOWN = 12,
    TVRC_KEY_MEDIA_PLAY_PAUSE = 13,
    TVRC_KEY_MEDIA_STOP = 14,
    TVRC_KEY_MEDIA_NEXT = 15,
    TVRC_KEY_MEDIA_PREVIOUS = 16,
    TVRC_KEY_POWER = 17
};

typedef struct tvrc_config {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t request_queue_capacity;
    uint32_t event_queue_capacity;
    uint32_t flags;
    uint32_t reserved;
    const uint8_t *controller_name;
    uint32_t controller_name_len;
    uint32_t controller_name_reserved;
    void *credential_context;
    tvrc_credentials_put_fn credentials_put;
    tvrc_credentials_get_fn credentials_get;
    tvrc_credentials_remove_fn credentials_remove;
} tvrc_config;

typedef struct tvrc_event {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t event_type;
    int32_t status;
    uint64_t request_id;
    uint32_t payload_len;
    uint32_t reserved;
} tvrc_event;

typedef struct tvrc_media_packet {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t track;
    uint32_t flags;
    uint32_t sequence;
    uint32_t codec_config_id;
    uint64_t presentation_time_us;
    uint32_t payload_len;
    uint16_t width;
    uint16_t height;
    uint32_t reserved;
} tvrc_media_packet;

void tvrc_config_init(tvrc_config *config);
void tvrc_event_init(tvrc_event *event);
void tvrc_media_packet_init(tvrc_media_packet *packet);

tvrc_result tvrc_create(const tvrc_config *config, tvrc_handle **handle);
void tvrc_destroy(tvrc_handle *handle);
tvrc_result tvrc_start(tvrc_handle *handle);
tvrc_result tvrc_stop(tvrc_handle *handle);
tvrc_result tvrc_discover(tvrc_handle *handle, uint64_t request_id);
tvrc_result tvrc_target_set(tvrc_handle *handle,
                            const uint8_t *address, uint32_t address_len);
tvrc_result tvrc_pair_submit(tvrc_handle *handle, uint64_t request_id,
                             const uint8_t *code, uint32_t code_len);
tvrc_result tvrc_connect(tvrc_handle *handle, uint64_t request_id,
                         const uint8_t *address, uint32_t address_len);
tvrc_result tvrc_disconnect(tvrc_handle *handle, uint64_t request_id);
tvrc_result tvrc_send_key(tvrc_handle *handle, uint64_t request_id,
                          uint32_t key, uint32_t key_state);
tvrc_result tvrc_poll_event(tvrc_handle *handle, tvrc_event *event,
                            uint8_t *payload, uint32_t payload_capacity);

tvrc_result tvrc_media_start(tvrc_handle *handle, uint64_t request_id);
tvrc_result tvrc_media_stop(tvrc_handle *handle, uint64_t request_id);
tvrc_result tvrc_media_read(tvrc_handle *handle, tvrc_media_packet *packet,
                            uint8_t *payload, uint32_t payload_capacity);

tvrc_result tvrc_adb_probe(tvrc_handle *handle, uint64_t request_id,
                           const uint8_t *saved_path, uint32_t saved_path_len);
tvrc_result tvrc_adb_install(tvrc_handle *handle, uint64_t request_id, uint32_t flags);
tvrc_result tvrc_adb_media_start(tvrc_handle *handle, uint64_t request_id,
                                 const uint8_t *serial, uint32_t serial_len, uint32_t flags);
tvrc_result tvrc_adb_media_stop(tvrc_handle *handle, uint64_t request_id);
tvrc_result tvrc_adb_disable(tvrc_handle *handle, uint64_t request_id);

tvrc_result tvrc_credentials_put(tvrc_handle *handle,
                                 const uint8_t *credential_id, uint32_t credential_id_len,
                                 const uint8_t *secret, uint32_t secret_len);
tvrc_result tvrc_credentials_get(tvrc_handle *handle,
                                 const uint8_t *credential_id, uint32_t credential_id_len,
                                 uint8_t *secret, uint32_t secret_capacity,
                                 uint32_t *secret_len);
tvrc_result tvrc_credentials_remove(tvrc_handle *handle,
                                    const uint8_t *credential_id, uint32_t credential_id_len);

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(tvrc_event) == 32, "tvrc_event ABI layout changed");
_Static_assert(sizeof(tvrc_media_packet) == 48, "tvrc_media_packet ABI layout changed");
#endif

#ifdef __cplusplus
}
#endif
#endif
