/* SPDX-License-Identifier: GPL-2.0-only */
/* Scope: exact Mosey process, connected link-local IPv6 on current mosey0.
 * Duplicate an existing socket through pidfd; no ptrace/injected execution.
 * Default is read-only. --apply caps each matching SO_SNDBUF at 64 KiB
 * (Linux reports twice the requested size). No global sysctl is changed.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <net/if.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

static bool number(const char *s, long *value) {
    char *end;
    errno = 0;
    *value = strtol(s, &end, 10);
    return *s && !*end && !errno && *value > 0 && *value <= INT_MAX;
}

static int copyfd(int pidfd, int fd) {
    return (int)syscall(SYS_pidfd_getfd, pidfd, fd, 0);
}

static bool allowed(const struct sockaddr_in6 *local,
                    const struct sockaddr_in6 *peer, unsigned int index) {
    return index && local->sin6_family == AF_INET6 && peer->sin6_family == AF_INET6 &&
        IN6_IS_ADDR_LINKLOCAL(&local->sin6_addr) && IN6_IS_ADDR_LINKLOCAL(&peer->sin6_addr) &&
        local->sin6_scope_id == index;
}

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--self-test")) {
        struct sockaddr_in6 local = {.sin6_family=AF_INET6, .sin6_scope_id=7};
        struct sockaddr_in6 peer = {.sin6_family=AF_INET6};
        inet_pton(AF_INET6, "fe80::1", &local.sin6_addr);
        inet_pton(AF_INET6, "fe80::2", &peer.sin6_addr);
        assert(allowed(&local, &peer, 7));
        assert(!allowed(&local, &peer, 8));
        assert(!allowed(&local, &peer, 0));
        peer.sin6_family=AF_INET;
        assert(!allowed(&local, &peer, 7));
        peer.sin6_family=AF_INET6;
        inet_pton(AF_INET6, "::1", &peer.sin6_addr);
        assert(!allowed(&local, &peer, 7));
        inet_pton(AF_INET6, "2001:db8::1", &peer.sin6_addr);
        assert(!allowed(&local, &peer, 7));
        int sock = socket(AF_INET6, SOCK_STREAM | SOCK_CLOEXEC, 0);
        int pfd = (int)syscall(SYS_pidfd_open, getpid(), 0);
        int dup = pfd < 0 ? -1 : copyfd(pfd, sock);
        if (dup < 0) { perror("pidfd self-test"); return 1; }
        int value = 65536, actual = 0;
        socklen_t n = sizeof(actual);
        if (setsockopt(dup, SOL_SOCKET, SO_SNDBUF, &value, sizeof(value)) ||
            getsockopt(sock, SOL_SOCKET, SO_SNDBUF, &actual, &n)) return 1;
        printf("SELF_TEST_OK requested=%d actual=%d\n", value, actual);
        close(dup); close(pfd); close(sock);
        return actual == 131072 ? 0 : 1;
    }
    long pid;
    bool apply = argc == 3 && !strcmp(argv[2], "--apply");
    if ((argc != 2 && !apply) || !number(argv[1], &pid)) return 2;
    /* Pin the process before checking /proc identity. A dead pinned process
     * cannot redirect getfd to a later process reusing the numeric PID.
     */
    int pidfd = (int)syscall(SYS_pidfd_open, pid, 0);
    if (pidfd < 0) { perror("pidfd_open"); return 4; }
    char path[128], command[128] = {0};
    snprintf(path, sizeof(path), "/proc/%ld/cmdline", pid);
    FILE *f = fopen(path, "rb");
    if (!f) { close(pidfd); return 3; }
    size_t got = fread(command, 1, sizeof(command)-1, f);
    fclose(f);
    if (!got || strcmp(command, "com.google.android.mosey")) { close(pidfd); return 3; }
    unsigned int index = if_nametoindex("mosey0");
    if (!index) { close(pidfd); return 0; }
    snprintf(path, sizeof(path), "/proc/%ld/fd", pid);
    DIR *dir = opendir(path);
    if (!dir) { close(pidfd); return 4; }
    int errors = 0, matches = 0;
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        long original;
        if (!number(entry->d_name, &original)) continue;
        char target[128];
        snprintf(path, sizeof(path), "/proc/%ld/fd/%ld", pid, original);
        ssize_t n = readlink(path, target, sizeof(target)-1);
        if (n <= 0) continue;
        target[n] = 0;
        if (strncmp(target, "socket:[", 8)) continue;
        int fd = copyfd(pidfd, (int)original);
        if (fd < 0) {
            if (errno == EPERM || errno == ENOSYS || errno == EACCES) {
                perror("pidfd_getfd"); errors++; break;
            }
            continue; /* Concurrent close is normal. */
        }
        struct sockaddr_in6 local = {0}, peer = {0};
        socklen_t local_len = sizeof(local), peer_len = sizeof(peer);
        int type = 0;
        socklen_t type_len = sizeof(type);
        if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &type_len) || type != SOCK_STREAM ||
            getsockname(fd, (struct sockaddr *)&local, &local_len) ||
            getpeername(fd, (struct sockaddr *)&peer, &peer_len) ||
            !allowed(&local, &peer, index)) { close(fd); continue; }
        int before = 0, after = 0, limit = 65536;
        socklen_t size = sizeof(before);
        if (getsockopt(fd, SOL_SOCKET, SO_SNDBUF, &before, &size)) { close(fd); errors++; continue; }
        if (apply && before > 131072 && setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &limit, sizeof(limit))) {
            perror("setsockopt"); close(fd); errors++; continue;
        }
        size = sizeof(after);
        if (getsockopt(fd, SOL_SOCKET, SO_SNDBUF, &after, &size)) { close(fd); errors++; continue; }
        if (!apply || before != after)
            printf("MATCH pid=%ld fd=%ld local_port=%u peer_port=%u before=%d after=%d apply=%d\n",
                   pid, original, ntohs(local.sin6_port), ntohs(peer.sin6_port), before, after, apply);
        matches++;
        close(fd);
    }
    closedir(dir); close(pidfd);
    if ((!apply && matches) || errors) printf("SUMMARY matches=%d errors=%d\n", matches, errors);
    return errors ? 4 : 0;
}
