/* Runtime config adapter for the original Binder v2 server / FFI v5 library.
 * Preserve the original protocol and driver calls; append protobuf field5=true.
 * Activate only with OPAIRSHARE_ENABLE_HOPPING=1 in this one server process.
 */
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>

typedef void *(*dlsym_fn)(void *, const char *);
typedef void *(*dlopen_fn)(const char *, int);
typedef void *(*start5_fn)(const void *, size_t, uint32_t, const void *,
                         uint32_t, const uint8_t *, size_t);
static _Atomic(start5_fn) original_start;
static _Atomic(dlsym_fn) original_dlsym;
static _Atomic(dlopen_fn) original_dlopen;

static void receipt(const char *stage, size_t before, size_t after) {
    const char *path = getenv("OPAIRSHARE_CONFIG_TRACE");
    if (!path) path = "/data/local/tmp/opairshare-stage18/hop-config-trace.txt";
    int fd = open(path,
                  O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd >= 0) {
        dprintf(fd, "%s config_bytes=%zu->%zu\n", stage, before, after);
        close(fd);
    }
}

__attribute__((constructor)) static void loaded(void) { receipt("adapter_loaded", 0, 0); }

void *dlopen(const char *filename, int flags) {
    dlopen_fn real = atomic_load(&original_dlopen);
    if (!real) {
        real = (dlopen_fn)dlvsym(RTLD_NEXT, "dlopen", "LIBC");
        if (!real) return NULL;
        atomic_store(&original_dlopen, real);
    }
    const char *diagnostic = getenv("OPAIRSHARE_DIAGNOSTIC_FFI");
    if (filename && diagnostic &&
        !strcmp(filename, "/system_ext/lib64/libmosey_daemon_ffi.so") &&
        !strcmp(diagnostic, "/data/local/tmp/opairshare-stage19/libmosey-hopping-diagnostic.so")) {
        receipt("diagnostic_ffi_selected", 0, 0);
        filename = diagnostic;
    }
    return real(filename, flags);
}

static void *start_with_hopping(const void *channels, size_t count,
                              uint32_t max_mdns, const void *country,
                              uint32_t radio, const uint8_t *config, size_t len) {
    start5_fn real = atomic_load(&original_start);
    if (!real) return NULL;
    if ((len && !config) || len > 65536) {
        __android_log_print(ANDROID_LOG_ERROR, "OPAirShareHopConfig", "Reject invalid config length");
        return NULL;
    }
    if (channels && count <= 8) {
        const uint8_t *input = channels;
        for (size_t i = 0; i < count; ++i)
            receipt("app_requested_channel", input[i], i);
    }
    uint8_t *extended = malloc(len + 2);
    if (!extended) return NULL;
    if (len) memcpy(extended, config, len);
    /* protobuf bool field 5, varint wire type; last scalar occurrence wins. */
    extended[len] = 0x28;
    extended[len + 1] = 1;
    const uint8_t social_channels[] = {6, 44, 149};
    const uint8_t fixed_channel[] = {6};
    const uint8_t fixed_149[] = {149};
    const char *expand = getenv("OPAIRSHARE_SOCIAL_CHANNELS");
    if (expand && !strcmp(expand, "6,44,149")) {
        channels = social_channels;
        count = sizeof(social_channels);
        receipt("social_channels_6_44_149", 0, count);
    } else if (expand && !strcmp(expand, "6")) {
        channels = fixed_channel;
        count = sizeof(fixed_channel);
        receipt("fixed_channel_6_original_protocol", 0, count);
    } else if (expand && !strcmp(expand, "149")) {
        channels = fixed_149;
        count = sizeof(fixed_149);
        receipt("fixed_channel_149_original_protocol", 0, count);
    }
    receipt("field5_true", len, len + 2);
    __android_log_print(ANDROID_LOG_INFO, "OPAirShareHopConfig",
                       "FFI v5 config appended field5=true; bytes=%zu->%zu channels=%zu",
                       len, len + 2, count);
    void *handle = real(channels, count, max_mdns, country, radio, extended, len + 2);
    receipt(handle ? "original_start_success" : "original_start_failed", len, len + 2);
    free(extended);
    return handle;
}

void *dlsym(void *handle, const char *symbol) {
    dlsym_fn real = atomic_load(&original_dlsym);
    if (!real) {
        real = (dlsym_fn)dlvsym(RTLD_NEXT, "dlsym", "LIBC");
        if (!real) return NULL;
        atomic_store(&original_dlsym, real);
    }
    void *resolved = real(handle, symbol);
    const char *enabled = getenv("OPAIRSHARE_ENABLE_HOPPING");
    if (resolved && enabled && !strcmp(enabled, "1") && !strcmp(symbol, "mosey_start_5")) {
        receipt("resolved_mosey_start_5", 0, 0);
        atomic_store(&original_start, (start5_fn)resolved);
        return (void *)start_with_hopping;
    }
    return resolved;
}
