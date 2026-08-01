#ifndef TVRC_WINDOWS_RUNTIME_PATHS_H
#define TVRC_WINDOWS_RUNTIME_PATHS_H

#include <stdint.h>

int32_t tvrc_windows_companion_path(const char *name, uint8_t *output,
                                    uint32_t capacity, uint32_t *output_len);

#endif
