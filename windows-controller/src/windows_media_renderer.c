#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <mmsystem.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <wmcodecdsp.h>

#include "tvrc_windows_media.h"

#define TVRC_MEDIA_KEY_FRAME (1u << 0)
#define TVRC_MEDIA_CODEC_CONFIG (1u << 1)
#define TVRC_MEDIA_DISCONTINUITY (1u << 2)
#define TVRC_MEDIA_END_OF_STREAM (1u << 3)
#define TVRC_MAX_VIDEO_PACKET (4u * 1024u * 1024u)
#define TVRC_MAX_AUDIO_PACKET (1024u * 1024u)

struct tvrc_windows_media {
    CRITICAL_SECTION lock;
    HWND target;
    IDXGISwapChain *swap_chain;
    ID3D11Device *device;
    ID3D11DeviceContext *context;
    ID3D11VideoDevice *video_device;
    ID3D11VideoContext *video_context;
    ID3D11VideoProcessorEnumerator *processor_enumerator;
    ID3D11VideoProcessor *processor;
    ID3D11Texture2D *input_texture;
    ID3D11VideoProcessorInputView *input_view;
    ID3D11VideoProcessorOutputView *output_view;
    IMFTransform *decoder;
    IMFMediaType *output_type;
    DWORD output_buffer_size;
    DWORD output_stream_flags;
    LONG output_stride;
    uint32_t configuration_id;
    uint16_t video_width;
    uint16_t video_height;
    IMFTransform *audio_decoder;
    DWORD audio_output_buffer_size;
    DWORD audio_output_stream_flags;
    HWAVEOUT audio_output;
    HANDLE audio_event;
    uint32_t audio_configuration_id;
    int mf_started;
    int com_initialized;
};

static void release_decoder(tvrc_windows_media *media);
static void release_audio_decoder(tvrc_windows_media *media);
static void release_video_processor(tvrc_windows_media *media);
static HRESULT create_video_processor(tvrc_windows_media *media, uint16_t width, uint16_t height);
static HRESULT drain_decoder(tvrc_windows_media *media);

static void safe_release_unknown(IUnknown **value) {
    if (*value != NULL) {
        IUnknown_Release(*value);
        *value = NULL;
    }
}

static uint16_t read_be16(const uint8_t *value) {
    return (uint16_t)(((uint16_t)value[0] << 8) | value[1]);
}

static uint32_t read_be32(const uint8_t *value) {
    return ((uint32_t)value[0] << 24) | ((uint32_t)value[1] << 16) |
           ((uint32_t)value[2] << 8) | value[3];
}

static HRESULT d3d_create(tvrc_windows_media *media) {
    DXGI_SWAP_CHAIN_DESC swap = {0};
    D3D_FEATURE_LEVEL requested[] = {
        D3D_FEATURE_LEVEL_11_1,
        D3D_FEATURE_LEVEL_11_0,
        D3D_FEATURE_LEVEL_10_1,
    };
    D3D_FEATURE_LEVEL selected;
    UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
    HRESULT result;

    swap.BufferDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    swap.SampleDesc.Count = 1;
    swap.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swap.BufferCount = 2;
    swap.OutputWindow = media->target;
    swap.Windowed = TRUE;
    swap.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
    result = D3D11CreateDeviceAndSwapChain(
        NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, flags, requested, ARRAYSIZE(requested),
        D3D11_SDK_VERSION, &swap, &media->swap_chain, &media->device, &selected, &media->context);
    if (FAILED(result)) {
        result = D3D11CreateDeviceAndSwapChain(
            NULL, D3D_DRIVER_TYPE_WARP, NULL, flags, requested + 1, ARRAYSIZE(requested) - 1,
            D3D11_SDK_VERSION, &swap, &media->swap_chain, &media->device, &selected, &media->context);
    }
    if (FAILED(result)) return result;
    result = ID3D11Device_QueryInterface(media->device, &IID_ID3D11VideoDevice, (void **)&media->video_device);
    if (FAILED(result)) return result;
    return ID3D11DeviceContext_QueryInterface(
        media->context, &IID_ID3D11VideoContext, (void **)&media->video_context);
}

HRESULT tvrc_windows_media_create(HWND target, tvrc_windows_media **out_media) {
    tvrc_windows_media *media;
    HRESULT result;
    if (target == NULL || out_media == NULL) return E_INVALIDARG;
    *out_media = NULL;
    media = (tvrc_windows_media *)calloc(1, sizeof(*media));
    if (media == NULL) return E_OUTOFMEMORY;
    InitializeCriticalSection(&media->lock);
    media->target = target;
    result = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (SUCCEEDED(result)) media->com_initialized = 1;
    else if (result != RPC_E_CHANGED_MODE) goto failed;
    result = MFStartup(MF_VERSION, MFSTARTUP_LITE);
    if (FAILED(result)) goto failed;
    media->mf_started = 1;
    result = d3d_create(media);
    if (FAILED(result)) goto failed;
    *out_media = media;
    return S_OK;

failed:
    tvrc_windows_media_destroy(media);
    return result;
}

void tvrc_windows_media_destroy(tvrc_windows_media *media) {
    if (media == NULL) return;
    EnterCriticalSection(&media->lock);
    release_decoder(media);
    release_audio_decoder(media);
    release_video_processor(media);
    safe_release_unknown((IUnknown **)&media->video_context);
    safe_release_unknown((IUnknown **)&media->video_device);
    safe_release_unknown((IUnknown **)&media->context);
    safe_release_unknown((IUnknown **)&media->device);
    safe_release_unknown((IUnknown **)&media->swap_chain);
    LeaveCriticalSection(&media->lock);
    if (media->mf_started) MFShutdown();
    if (media->com_initialized) CoUninitialize();
    DeleteCriticalSection(&media->lock);
    free(media);
}

void tvrc_windows_media_reset(tvrc_windows_media *media) {
    if (media == NULL) return;
    EnterCriticalSection(&media->lock);
    release_decoder(media);
    release_audio_decoder(media);
    release_video_processor(media);
    LeaveCriticalSection(&media->lock);
}

static void release_decoder(tvrc_windows_media *media) {
    if (media->decoder != NULL) {
        IMFTransform_ProcessMessage(media->decoder, MFT_MESSAGE_COMMAND_FLUSH, 0);
        IMFTransform_ProcessMessage(media->decoder, MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
    }
    safe_release_unknown((IUnknown **)&media->output_type);
    safe_release_unknown((IUnknown **)&media->decoder);
    media->output_buffer_size = 0;
    media->output_stream_flags = 0;
    media->output_stride = 0;
    media->configuration_id = 0;
}

static void release_audio_decoder(tvrc_windows_media *media) {
    if (media->audio_decoder != NULL) {
        IMFTransform_ProcessMessage(media->audio_decoder, MFT_MESSAGE_COMMAND_FLUSH, 0);
        IMFTransform_ProcessMessage(media->audio_decoder, MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
    }
    safe_release_unknown((IUnknown **)&media->audio_decoder);
    if (media->audio_output != NULL) {
        waveOutReset(media->audio_output);
        waveOutClose(media->audio_output);
        media->audio_output = NULL;
    }
    if (media->audio_event != NULL) {
        CloseHandle(media->audio_event);
        media->audio_event = NULL;
    }
    media->audio_output_buffer_size = 0;
    media->audio_output_stream_flags = 0;
    media->audio_configuration_id = 0;
}

static void release_video_processor(tvrc_windows_media *media) {
    safe_release_unknown((IUnknown **)&media->output_view);
    safe_release_unknown((IUnknown **)&media->input_view);
    safe_release_unknown((IUnknown **)&media->input_texture);
    safe_release_unknown((IUnknown **)&media->processor);
    safe_release_unknown((IUnknown **)&media->processor_enumerator);
    media->video_width = 0;
    media->video_height = 0;
}

static HRESULT create_output_view(tvrc_windows_media *media) {
    ID3D11Texture2D *back_buffer = NULL;
    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC description = {0};
    HRESULT result = IDXGISwapChain_GetBuffer(
        media->swap_chain, 0, &IID_ID3D11Texture2D, (void **)&back_buffer);
    if (FAILED(result)) return result;
    description.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    description.Texture2D.MipSlice = 0;
    result = ID3D11VideoDevice_CreateVideoProcessorOutputView(
        media->video_device, (ID3D11Resource *)back_buffer, media->processor_enumerator,
        &description, &media->output_view);
    ID3D11Texture2D_Release(back_buffer);
    return result;
}

static HRESULT create_video_processor(tvrc_windows_media *media, uint16_t width, uint16_t height) {
    D3D11_VIDEO_PROCESSOR_CONTENT_DESC content = {0};
    D3D11_TEXTURE2D_DESC texture = {0};
    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC input = {0};
    RECT client;
    HRESULT result;

    release_video_processor(media);
    if (!GetClientRect(media->target, &client) || client.right <= 0 || client.bottom <= 0) return E_FAIL;
    content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    content.InputFrameRate.Numerator = 15;
    content.InputFrameRate.Denominator = 1;
    content.InputWidth = width;
    content.InputHeight = height;
    content.OutputFrameRate.Numerator = 15;
    content.OutputFrameRate.Denominator = 1;
    content.OutputWidth = (UINT)client.right;
    content.OutputHeight = (UINT)client.bottom;
    content.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
    result = ID3D11VideoDevice_CreateVideoProcessorEnumerator(
        media->video_device, &content, &media->processor_enumerator);
    if (FAILED(result)) return result;
    result = ID3D11VideoDevice_CreateVideoProcessor(
        media->video_device, media->processor_enumerator, 0, &media->processor);
    if (FAILED(result)) return result;

    texture.Width = width;
    texture.Height = height;
    texture.MipLevels = 1;
    texture.ArraySize = 1;
    texture.Format = DXGI_FORMAT_NV12;
    texture.SampleDesc.Count = 1;
    texture.Usage = D3D11_USAGE_DEFAULT;
    texture.BindFlags = D3D11_BIND_DECODER;
    result = ID3D11Device_CreateTexture2D(media->device, &texture, NULL, &media->input_texture);
    if (FAILED(result)) return result;
    input.FourCC = 0;
    input.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    input.Texture2D.MipSlice = 0;
    input.Texture2D.ArraySlice = 0;
    result = ID3D11VideoDevice_CreateVideoProcessorInputView(
        media->video_device, (ID3D11Resource *)media->input_texture,
        media->processor_enumerator, &input, &media->input_view);
    if (FAILED(result)) return result;
    result = create_output_view(media);
    if (FAILED(result)) return result;
    media->video_width = width;
    media->video_height = height;
    return S_OK;
}

HRESULT tvrc_windows_media_resize(tvrc_windows_media *media) {
    HRESULT result = S_OK;
    if (media == NULL) return E_INVALIDARG;
    EnterCriticalSection(&media->lock);
    if (media->swap_chain != NULL) {
        safe_release_unknown((IUnknown **)&media->output_view);
        result = IDXGISwapChain_ResizeBuffers(media->swap_chain, 0, 0, 0, DXGI_FORMAT_UNKNOWN, 0);
        if (SUCCEEDED(result) && media->video_width != 0) {
            uint16_t width = media->video_width;
            uint16_t height = media->video_height;
            result = create_video_processor(media, width, height);
        }
    }
    LeaveCriticalSection(&media->lock);
    return result;
}

static HRESULT parse_avcc_configuration(const uint8_t *data, uint32_t length,
                                         uint8_t **annex_b, uint32_t *annex_b_length) {
    uint32_t cursor = 5;
    uint32_t total = 0;
    uint32_t index;
    uint8_t sps_count;
    uint8_t pps_count;
    uint8_t *output;
    uint32_t output_cursor = 0;
    if (data == NULL || annex_b == NULL || annex_b_length == NULL || length < 11 ||
        data[0] != 1 || (data[4] & 3) != 3) return E_INVALIDARG;
    sps_count = (uint8_t)(data[cursor++] & 0x1f);
    if (sps_count == 0) return E_INVALIDARG;
    for (index = 0; index < sps_count; ++index) {
        uint16_t item_length;
        if (cursor + 2 > length) return E_INVALIDARG;
        item_length = read_be16(data + cursor);
        cursor += 2;
        if (item_length == 0 || cursor + item_length > length) return E_INVALIDARG;
        total += 4u + item_length;
        cursor += item_length;
    }
    if (cursor >= length) return E_INVALIDARG;
    pps_count = data[cursor++];
    if (pps_count == 0) return E_INVALIDARG;
    for (index = 0; index < pps_count; ++index) {
        uint16_t item_length;
        if (cursor + 2 > length) return E_INVALIDARG;
        item_length = read_be16(data + cursor);
        cursor += 2;
        if (item_length == 0 || cursor + item_length > length) return E_INVALIDARG;
        total += 4u + item_length;
        cursor += item_length;
    }
    if (cursor != length || total > TVRC_MAX_VIDEO_PACKET) return E_INVALIDARG;
    output = (uint8_t *)malloc(total);
    if (output == NULL) return E_OUTOFMEMORY;
    cursor = 5;
    sps_count = (uint8_t)(data[cursor++] & 0x1f);
    for (index = 0; index < sps_count; ++index) {
        uint16_t item_length = read_be16(data + cursor);
        cursor += 2;
        output[output_cursor++] = 0;
        output[output_cursor++] = 0;
        output[output_cursor++] = 0;
        output[output_cursor++] = 1;
        memcpy(output + output_cursor, data + cursor, item_length);
        output_cursor += item_length;
        cursor += item_length;
    }
    pps_count = data[cursor++];
    for (index = 0; index < pps_count; ++index) {
        uint16_t item_length = read_be16(data + cursor);
        cursor += 2;
        output[output_cursor++] = 0;
        output[output_cursor++] = 0;
        output[output_cursor++] = 0;
        output[output_cursor++] = 1;
        memcpy(output + output_cursor, data + cursor, item_length);
        output_cursor += item_length;
        cursor += item_length;
    }
    *annex_b = output;
    *annex_b_length = total;
    return S_OK;
}

static HRESULT avcc_access_unit_to_annex_b(const uint8_t *data, uint32_t length,
                                            uint8_t **annex_b, uint32_t *annex_b_length) {
    uint8_t *output;
    uint32_t cursor = 0;
    if (data == NULL || annex_b == NULL || annex_b_length == NULL || length == 0 ||
        length > TVRC_MAX_VIDEO_PACKET) return E_INVALIDARG;
    output = (uint8_t *)malloc(length);
    if (output == NULL) return E_OUTOFMEMORY;
    while (cursor < length) {
        uint32_t item_length;
        if (length - cursor < 4) {
            free(output);
            return E_INVALIDARG;
        }
        item_length = read_be32(data + cursor);
        if (item_length == 0 || item_length > length - cursor - 4) {
            free(output);
            return E_INVALIDARG;
        }
        output[cursor] = 0;
        output[cursor + 1] = 0;
        output[cursor + 2] = 0;
        output[cursor + 3] = 1;
        memcpy(output + cursor + 4, data + cursor + 4, item_length);
        cursor += 4 + item_length;
    }
    *annex_b = output;
    *annex_b_length = length;
    return S_OK;
}

static HRESULT configure_decoder(tvrc_windows_media *media, const uint8_t *configuration,
                                 uint32_t configuration_length, uint32_t configuration_id,
                                 uint16_t width, uint16_t height) {
    IMFMediaType *input = NULL;
    IMFMediaType *candidate = NULL;
    MFT_OUTPUT_STREAM_INFO stream_info;
    uint8_t *sequence = NULL;
    uint32_t sequence_length = 0;
    UINT32 stride_value;
    DWORD type_index;
    HRESULT result;

    if (width == 0 || height == 0) return E_INVALIDARG;
    result = parse_avcc_configuration(configuration, configuration_length, &sequence, &sequence_length);
    if (FAILED(result)) return result;
    release_decoder(media);
    result = CoCreateInstance(&CLSID_CMSH264DecoderMFT, NULL, CLSCTX_INPROC_SERVER,
                              &IID_IMFTransform, (void **)&media->decoder);
    if (FAILED(result)) goto done;
    result = MFCreateMediaType(&input);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetGUID(input, &MF_MT_MAJOR_TYPE, &MFMediaType_Video);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetGUID(input, &MF_MT_SUBTYPE, &MFVideoFormat_H264);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT64(
        input, &MF_MT_FRAME_SIZE, ((UINT64)width << 32) | (UINT64)height);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT64(input, &MF_MT_FRAME_RATE, ((UINT64)15 << 32) | 1u);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(input, &MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetBlob(input, &MF_MT_MPEG_SEQUENCE_HEADER, sequence, sequence_length);
    if (FAILED(result)) goto done;
    result = IMFTransform_SetInputType(media->decoder, 0, input, 0);
    if (FAILED(result)) goto done;

    result = MF_E_INVALIDMEDIATYPE;
    for (type_index = 0;; ++type_index) {
        GUID subtype;
        HRESULT next = IMFTransform_GetOutputAvailableType(media->decoder, 0, type_index, &candidate);
        if (next == MF_E_NO_MORE_TYPES) break;
        if (FAILED(next)) {
            result = next;
            break;
        }
        next = IMFMediaType_GetGUID(candidate, &MF_MT_SUBTYPE, &subtype);
        if (SUCCEEDED(next) && IsEqualGUID(&subtype, &MFVideoFormat_NV12)) {
            next = IMFTransform_SetOutputType(media->decoder, 0, candidate, 0);
            if (SUCCEEDED(next)) {
                media->output_type = candidate;
                candidate = NULL;
                result = S_OK;
                break;
            }
        }
        safe_release_unknown((IUnknown **)&candidate);
    }
    if (FAILED(result)) goto done;
    result = IMFTransform_GetOutputStreamInfo(media->decoder, 0, &stream_info);
    if (FAILED(result)) goto done;
    media->output_buffer_size = stream_info.cbSize;
    media->output_stream_flags = stream_info.dwFlags;
    if (media->output_buffer_size < (DWORD)width * height * 3u / 2u) {
        media->output_buffer_size = (DWORD)width * height * 3u / 2u;
    }
    if (SUCCEEDED(IMFMediaType_GetUINT32(media->output_type, &MF_MT_DEFAULT_STRIDE, &stride_value))) {
        media->output_stride = (LONG)stride_value;
    } else {
        media->output_stride = (LONG)width;
    }
    result = create_video_processor(media, width, height);
    if (FAILED(result)) goto done;
    result = IMFTransform_ProcessMessage(media->decoder, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    if (FAILED(result)) goto done;
    result = IMFTransform_ProcessMessage(media->decoder, MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    if (FAILED(result)) goto done;
    media->configuration_id = configuration_id;

done:
    free(sequence);
    safe_release_unknown((IUnknown **)&candidate);
    safe_release_unknown((IUnknown **)&input);
    if (FAILED(result)) release_decoder(media);
    return result;
}

static HRESULT create_input_sample(const uint8_t *data, uint32_t length, uint64_t pts_us,
                                   IMFSample **out_sample) {
    IMFSample *sample = NULL;
    IMFMediaBuffer *buffer = NULL;
    BYTE *target = NULL;
    HRESULT result;
    if (out_sample == NULL) return E_POINTER;
    *out_sample = NULL;
    result = MFCreateSample(&sample);
    if (FAILED(result)) goto done;
    result = MFCreateMemoryBuffer(length, &buffer);
    if (FAILED(result)) goto done;
    result = IMFMediaBuffer_Lock(buffer, &target, NULL, NULL);
    if (FAILED(result)) goto done;
    memcpy(target, data, length);
    IMFMediaBuffer_Unlock(buffer);
    target = NULL;
    result = IMFMediaBuffer_SetCurrentLength(buffer, length);
    if (FAILED(result)) goto done;
    result = IMFSample_AddBuffer(sample, buffer);
    if (FAILED(result)) goto done;
    result = IMFSample_SetSampleTime(sample, (LONGLONG)pts_us * 10);
    if (FAILED(result)) goto done;
    *out_sample = sample;
    sample = NULL;

done:
    if (target != NULL) IMFMediaBuffer_Unlock(buffer);
    safe_release_unknown((IUnknown **)&buffer);
    safe_release_unknown((IUnknown **)&sample);
    return result;
}

static HRESULT render_nv12(tvrc_windows_media *media, IMFMediaBuffer *buffer) {
    IMF2DBuffer *buffer_2d = NULL;
    BYTE *data = NULL;
    LONG stride = media->output_stride;
    DWORD current_length = 0;
    RECT source;
    RECT destination;
    RECT client;
    D3D11_VIDEO_PROCESSOR_STREAM stream = {0};
    HRESULT result;
    result = IMFMediaBuffer_QueryInterface(buffer, &IID_IMF2DBuffer, (void **)&buffer_2d);
    if (SUCCEEDED(result)) {
        result = IMF2DBuffer_Lock2D(buffer_2d, &data, &stride);
    } else {
        result = IMFMediaBuffer_Lock(buffer, &data, NULL, &current_length);
    }
    if (FAILED(result)) goto done;
    if (stride < 0) {
        result = E_FAIL;
        goto unlock;
    }
    ID3D11DeviceContext_UpdateSubresource(
        media->context, (ID3D11Resource *)media->input_texture, 0, NULL, data, (UINT)stride, 0);
    if (!GetClientRect(media->target, &client) || client.right <= 0 || client.bottom <= 0) {
        result = E_FAIL;
        goto unlock;
    }
    source.left = 0;
    source.top = 0;
    source.right = media->video_width;
    source.bottom = media->video_height;
    destination = client;
    {
        double source_ratio = (double)media->video_width / media->video_height;
        double target_ratio = (double)client.right / client.bottom;
        if (target_ratio > source_ratio) {
            LONG width = (LONG)(client.bottom * source_ratio);
            destination.left = (client.right - width) / 2;
            destination.right = destination.left + width;
        } else {
            LONG height = (LONG)(client.right / source_ratio);
            destination.top = (client.bottom - height) / 2;
            destination.bottom = destination.top + height;
        }
    }
    ID3D11VideoContext_VideoProcessorSetStreamSourceRect(
        media->video_context, media->processor, 0, TRUE, &source);
    ID3D11VideoContext_VideoProcessorSetStreamDestRect(
        media->video_context, media->processor, 0, TRUE, &destination);
    ID3D11VideoContext_VideoProcessorSetOutputTargetRect(
        media->video_context, media->processor, TRUE, &client);
    stream.Enable = TRUE;
    stream.pInputSurface = media->input_view;
    result = ID3D11VideoContext_VideoProcessorBlt(
        media->video_context, media->processor, media->output_view, 0, 1, &stream);
    if (SUCCEEDED(result)) result = IDXGISwapChain_Present(media->swap_chain, 1, 0);

unlock:
    if (buffer_2d != NULL) IMF2DBuffer_Unlock2D(buffer_2d);
    else IMFMediaBuffer_Unlock(buffer);
done:
    safe_release_unknown((IUnknown **)&buffer_2d);
    return result;
}

static HRESULT drain_decoder(tvrc_windows_media *media) {
    HRESULT result = S_OK;
    for (;;) {
        IMFSample *sample = NULL;
        IMFMediaBuffer *buffer = NULL;
        MFT_OUTPUT_DATA_BUFFER output = {0};
        DWORD status = 0;
        int produced = 0;
        if ((media->output_stream_flags &
             (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) == 0) {
            result = MFCreateSample(&sample);
            if (FAILED(result)) goto iteration_done;
            result = MFCreateMemoryBuffer(media->output_buffer_size, &buffer);
            if (FAILED(result)) goto iteration_done;
            result = IMFSample_AddBuffer(sample, buffer);
            if (FAILED(result)) goto iteration_done;
        }
        output.dwStreamID = 0;
        output.pSample = sample;
        result = IMFTransform_ProcessOutput(media->decoder, 0, 1, &output, &status);
        if (result == MF_E_TRANSFORM_NEED_MORE_INPUT) {
            result = S_OK;
            goto iteration_done;
        }
        if (result == MF_E_TRANSFORM_STREAM_CHANGE) {
            result = MF_E_TRANSFORM_STREAM_CHANGE;
            goto iteration_done;
        }
        if (FAILED(result)) goto iteration_done;
        produced = 1;
        if (output.pSample == NULL) {
            result = E_FAIL;
            goto iteration_done;
        }
        safe_release_unknown((IUnknown **)&buffer);
        result = IMFSample_ConvertToContiguousBuffer(output.pSample, &buffer);
        if (SUCCEEDED(result)) result = render_nv12(media, buffer);

iteration_done:
        if (output.pEvents != NULL) IMFCollection_Release(output.pEvents);
        if (output.pSample != NULL && output.pSample != sample) IMFSample_Release(output.pSample);
        safe_release_unknown((IUnknown **)&buffer);
        safe_release_unknown((IUnknown **)&sample);
        if (FAILED(result) || !produced) return result;
    }
}

HRESULT tvrc_windows_media_submit_video(tvrc_windows_media *media,
                                        uint32_t flags,
                                        uint32_t codec_config_id,
                                        uint64_t presentation_time_us,
                                        uint16_t width,
                                        uint16_t height,
                                        const uint8_t *payload,
                                        uint32_t payload_len) {
    uint8_t *annex_b = NULL;
    uint32_t annex_b_length = 0;
    IMFSample *sample = NULL;
    HRESULT result;
    if (media == NULL || payload_len > TVRC_MAX_VIDEO_PACKET ||
        (payload_len != 0 && payload == NULL)) return E_INVALIDARG;
    EnterCriticalSection(&media->lock);
    if ((flags & TVRC_MEDIA_CODEC_CONFIG) != 0) {
        result = configure_decoder(media, payload, payload_len, codec_config_id, width, height);
        LeaveCriticalSection(&media->lock);
        return result;
    }
    if (media->decoder == NULL || media->configuration_id != codec_config_id) {
        LeaveCriticalSection(&media->lock);
        return MF_E_NOT_INITIALIZED;
    }
    if ((flags & TVRC_MEDIA_DISCONTINUITY) != 0) {
        IMFTransform_ProcessMessage(media->decoder, MFT_MESSAGE_COMMAND_FLUSH, 0);
        IMFTransform_ProcessMessage(media->decoder, MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    }
    result = avcc_access_unit_to_annex_b(payload, payload_len, &annex_b, &annex_b_length);
    if (FAILED(result)) goto done;
    result = create_input_sample(annex_b, annex_b_length, presentation_time_us, &sample);
    if (FAILED(result)) goto done;
    result = IMFTransform_ProcessInput(media->decoder, 0, sample, 0);
    if (result == MF_E_NOTACCEPTING) {
        result = drain_decoder(media);
        if (SUCCEEDED(result)) result = IMFTransform_ProcessInput(media->decoder, 0, sample, 0);
    }
    if (SUCCEEDED(result)) result = drain_decoder(media);
    if (SUCCEEDED(result) && (flags & TVRC_MEDIA_END_OF_STREAM) != 0) {
        IMFTransform_ProcessMessage(media->decoder, MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
        IMFTransform_ProcessMessage(media->decoder, MFT_MESSAGE_COMMAND_DRAIN, 0);
        result = drain_decoder(media);
    }

done:
    free(annex_b);
    safe_release_unknown((IUnknown **)&sample);
    LeaveCriticalSection(&media->lock);
    return result;
}

static HRESULT configure_audio_decoder(tvrc_windows_media *media, const uint8_t *configuration,
                                       uint32_t configuration_length, uint32_t configuration_id) {
    IMFMediaType *input = NULL;
    IMFMediaType *output = NULL;
    MFT_OUTPUT_STREAM_INFO stream_info;
    WAVEFORMATEX wave = {0};
    uint8_t *user_data = NULL;
    HRESULT result;

    if (configuration == NULL || configuration_length < 2 || configuration_length > 64) {
        return E_INVALIDARG;
    }
    release_audio_decoder(media);
    user_data = (uint8_t *)calloc(1, 12u + configuration_length);
    if (user_data == NULL) return E_OUTOFMEMORY;
    user_data[2] = 0x29;
    memcpy(user_data + 12, configuration, configuration_length);

    result = CoCreateInstance(&CLSID_CMSAACDecMFT, NULL, CLSCTX_INPROC_SERVER,
                              &IID_IMFTransform, (void **)&media->audio_decoder);
    if (FAILED(result)) goto done;
    result = MFCreateMediaType(&input);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetGUID(input, &MF_MT_MAJOR_TYPE, &MFMediaType_Audio);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetGUID(input, &MF_MT_SUBTYPE, &MFAudioFormat_AAC);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(input, &MF_MT_AUDIO_NUM_CHANNELS, 2);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(input, &MF_MT_AUDIO_SAMPLES_PER_SECOND, 48000);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(input, &MF_MT_AUDIO_BLOCK_ALIGNMENT, 1);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(input, &MF_MT_AUDIO_AVG_BYTES_PER_SECOND, 16000);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(input, &MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(input, &MF_MT_AAC_PAYLOAD_TYPE, 0);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(input, &MF_MT_AAC_AUDIO_PROFILE_LEVEL_INDICATION, 0x29);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetBlob(input, &MF_MT_USER_DATA, user_data, 12u + configuration_length);
    if (FAILED(result)) goto done;
    result = IMFTransform_SetInputType(media->audio_decoder, 0, input, 0);
    if (FAILED(result)) goto done;

    result = MFCreateMediaType(&output);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetGUID(output, &MF_MT_MAJOR_TYPE, &MFMediaType_Audio);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetGUID(output, &MF_MT_SUBTYPE, &MFAudioFormat_PCM);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(output, &MF_MT_AUDIO_NUM_CHANNELS, 2);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(output, &MF_MT_AUDIO_SAMPLES_PER_SECOND, 48000);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(output, &MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(output, &MF_MT_AUDIO_BLOCK_ALIGNMENT, 4);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(output, &MF_MT_AUDIO_AVG_BYTES_PER_SECOND, 192000);
    if (FAILED(result)) goto done;
    result = IMFMediaType_SetUINT32(output, &MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
    if (FAILED(result)) goto done;
    result = IMFTransform_SetOutputType(media->audio_decoder, 0, output, 0);
    if (FAILED(result)) goto done;
    result = IMFTransform_GetOutputStreamInfo(media->audio_decoder, 0, &stream_info);
    if (FAILED(result)) goto done;
    media->audio_output_buffer_size = stream_info.cbSize;
    if (media->audio_output_buffer_size < 16384) media->audio_output_buffer_size = 16384;
    media->audio_output_stream_flags = stream_info.dwFlags;

    media->audio_event = CreateEventW(NULL, FALSE, FALSE, NULL);
    if (media->audio_event == NULL) {
        result = HRESULT_FROM_WIN32(GetLastError());
        goto done;
    }
    wave.wFormatTag = WAVE_FORMAT_PCM;
    wave.nChannels = 2;
    wave.nSamplesPerSec = 48000;
    wave.nAvgBytesPerSec = 192000;
    wave.nBlockAlign = 4;
    wave.wBitsPerSample = 16;
    wave.cbSize = 0;
    {
        MMRESULT wave_result = waveOutOpen(
            &media->audio_output, WAVE_MAPPER, &wave, (DWORD_PTR)media->audio_event, 0, CALLBACK_EVENT);
        if (wave_result != MMSYSERR_NOERROR) {
            result = HRESULT_FROM_WIN32(wave_result);
            goto done;
        }
    }
    result = IMFTransform_ProcessMessage(media->audio_decoder, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    if (FAILED(result)) goto done;
    result = IMFTransform_ProcessMessage(media->audio_decoder, MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    if (FAILED(result)) goto done;
    media->audio_configuration_id = configuration_id;

done:
    free(user_data);
    safe_release_unknown((IUnknown **)&output);
    safe_release_unknown((IUnknown **)&input);
    if (FAILED(result)) release_audio_decoder(media);
    return result;
}

static HRESULT play_pcm(tvrc_windows_media *media, IMFMediaBuffer *buffer) {
    BYTE *data = NULL;
    DWORD length = 0;
    WAVEHDR header = {0};
    MMRESULT wave_result;
    HRESULT result;
    result = IMFMediaBuffer_Lock(buffer, &data, NULL, &length);
    if (FAILED(result)) return result;
    if (length == 0) {
        IMFMediaBuffer_Unlock(buffer);
        return S_OK;
    }
    header.lpData = (LPSTR)data;
    header.dwBufferLength = length;
    ResetEvent(media->audio_event);
    wave_result = waveOutPrepareHeader(media->audio_output, &header, sizeof(header));
    if (wave_result == MMSYSERR_NOERROR) {
        wave_result = waveOutWrite(media->audio_output, &header, sizeof(header));
    }
    if (wave_result == MMSYSERR_NOERROR) {
        DWORD wait_result = WaitForSingleObject(media->audio_event, 1000);
        if (wait_result != WAIT_OBJECT_0) {
            waveOutReset(media->audio_output);
            result = wait_result == WAIT_TIMEOUT ? HRESULT_FROM_WIN32(ERROR_TIMEOUT) : E_FAIL;
        } else {
            result = S_OK;
        }
    } else {
        result = HRESULT_FROM_WIN32(wave_result);
    }
    if ((header.dwFlags & WHDR_PREPARED) != 0) {
        if ((header.dwFlags & WHDR_DONE) == 0) waveOutReset(media->audio_output);
        waveOutUnprepareHeader(media->audio_output, &header, sizeof(header));
    }
    IMFMediaBuffer_Unlock(buffer);
    return result;
}

static HRESULT drain_audio_decoder(tvrc_windows_media *media) {
    HRESULT result = S_OK;
    for (;;) {
        IMFSample *sample = NULL;
        IMFMediaBuffer *buffer = NULL;
        MFT_OUTPUT_DATA_BUFFER output = {0};
        DWORD status = 0;
        int produced = 0;
        if ((media->audio_output_stream_flags &
             (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) == 0) {
            result = MFCreateSample(&sample);
            if (FAILED(result)) goto iteration_done;
            result = MFCreateMemoryBuffer(media->audio_output_buffer_size, &buffer);
            if (FAILED(result)) goto iteration_done;
            result = IMFSample_AddBuffer(sample, buffer);
            if (FAILED(result)) goto iteration_done;
        }
        output.dwStreamID = 0;
        output.pSample = sample;
        result = IMFTransform_ProcessOutput(media->audio_decoder, 0, 1, &output, &status);
        if (result == MF_E_TRANSFORM_NEED_MORE_INPUT) {
            result = S_OK;
            goto iteration_done;
        }
        if (FAILED(result)) goto iteration_done;
        produced = 1;
        if (output.pSample == NULL) {
            result = E_FAIL;
            goto iteration_done;
        }
        safe_release_unknown((IUnknown **)&buffer);
        result = IMFSample_ConvertToContiguousBuffer(output.pSample, &buffer);
        if (SUCCEEDED(result)) result = play_pcm(media, buffer);

iteration_done:
        if (output.pEvents != NULL) IMFCollection_Release(output.pEvents);
        if (output.pSample != NULL && output.pSample != sample) IMFSample_Release(output.pSample);
        safe_release_unknown((IUnknown **)&buffer);
        safe_release_unknown((IUnknown **)&sample);
        if (FAILED(result) || !produced) return result;
    }
}

HRESULT tvrc_windows_media_submit_audio(tvrc_windows_media *media,
                                        uint32_t flags,
                                        uint32_t codec_config_id,
                                        uint64_t presentation_time_us,
                                        const uint8_t *payload,
                                        uint32_t payload_len) {
    IMFSample *sample = NULL;
    HRESULT result;
    if (media == NULL || payload_len > TVRC_MAX_AUDIO_PACKET ||
        (payload_len != 0 && payload == NULL)) return E_INVALIDARG;
    EnterCriticalSection(&media->lock);
    if ((flags & TVRC_MEDIA_CODEC_CONFIG) != 0) {
        result = configure_audio_decoder(media, payload, payload_len, codec_config_id);
        LeaveCriticalSection(&media->lock);
        return result;
    }
    if (media->audio_decoder == NULL || media->audio_configuration_id != codec_config_id) {
        LeaveCriticalSection(&media->lock);
        return MF_E_NOT_INITIALIZED;
    }
    if ((flags & TVRC_MEDIA_DISCONTINUITY) != 0) {
        IMFTransform_ProcessMessage(media->audio_decoder, MFT_MESSAGE_COMMAND_FLUSH, 0);
        IMFTransform_ProcessMessage(media->audio_decoder, MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
        waveOutReset(media->audio_output);
    }
    result = create_input_sample(payload, payload_len, presentation_time_us, &sample);
    if (FAILED(result)) goto done;
    result = IMFTransform_ProcessInput(media->audio_decoder, 0, sample, 0);
    if (result == MF_E_NOTACCEPTING) {
        result = drain_audio_decoder(media);
        if (SUCCEEDED(result)) result = IMFTransform_ProcessInput(media->audio_decoder, 0, sample, 0);
    }
    if (SUCCEEDED(result)) result = drain_audio_decoder(media);
    if (SUCCEEDED(result) && (flags & TVRC_MEDIA_END_OF_STREAM) != 0) {
        IMFTransform_ProcessMessage(media->audio_decoder, MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
        IMFTransform_ProcessMessage(media->audio_decoder, MFT_MESSAGE_COMMAND_DRAIN, 0);
        result = drain_audio_decoder(media);
    }

done:
    safe_release_unknown((IUnknown **)&sample);
    LeaveCriticalSection(&media->lock);
    return result;
}
