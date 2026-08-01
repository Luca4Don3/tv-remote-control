#ifndef TVRC_WINDOWS_MEDIA_H
#define TVRC_WINDOWS_MEDIA_H

#include <stdint.h>
#include <windows.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct tvrc_windows_media tvrc_windows_media;

HRESULT tvrc_windows_media_create(HWND target, tvrc_windows_media **out_media);
void tvrc_windows_media_destroy(tvrc_windows_media *media);
HRESULT tvrc_windows_media_resize(tvrc_windows_media *media);
HRESULT tvrc_windows_media_submit_video(tvrc_windows_media *media,
                                        uint32_t flags,
                                        uint32_t codec_config_id,
                                        uint64_t presentation_time_us,
                                        uint16_t width,
                                        uint16_t height,
                                        const uint8_t *payload,
                                        uint32_t payload_len);
HRESULT tvrc_windows_media_submit_audio(tvrc_windows_media *media,
                                        uint32_t flags,
                                        uint32_t codec_config_id,
                                        uint64_t presentation_time_us,
                                        const uint8_t *payload,
                                        uint32_t payload_len);
void tvrc_windows_media_reset(tvrc_windows_media *media);

#ifdef __cplusplus
}
#endif
#endif
