/* Bounded root-only Android TCP receiver; validates generated test bytes. */
#include <arpa/inet.h>
#include <errno.h>
#include <net/if.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    char *end;
    unsigned long total = strtoul(argv[2], &end, 10);
    if (*end || total < 1024 || total > 32*1024*1024) return 2;
    struct sockaddr_in6 addr = {.sin6_family=AF_INET6,
        .sin6_port=htons(18773), .sin6_scope_id=if_nametoindex("mosey0")};
    if (!addr.sin6_scope_id || inet_pton(AF_INET6, argv[1], &addr.sin6_addr) != 1 ||
        !IN6_IS_ADDR_LINKLOCAL(&addr.sin6_addr)) return 2;
    int fd = socket(AF_INET6, SOCK_STREAM, 0);
    struct timeval timeout = {.tv_sec=10};
    if (fd < 0 || setsockopt(fd, SOL_SOCKET, SO_BINDTODEVICE, "mosey0", 7) ||
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) ||
        connect(fd, (struct sockaddr *)&addr, sizeof(addr))) {
        perror("connect/setup");
        return 3;
    }
    char request[80];
    int length = snprintf(request, sizeof(request), "OPAIRSHARE-BENCH-v1 %lu\n", total);
    if (send(fd, request, length, MSG_NOSIGNAL) != length) return 4;
    double start = now(), last = start;
    unsigned long received = 0;
    unsigned char data[65536];
    while (received < total && now()-start < 180) {
        size_t want = total-received < sizeof(data) ? total-received : sizeof(data);
        ssize_t n = recv(fd, data, want, 0);
        if (n <= 0) { perror("recv"); close(fd); return 5; }
        for (ssize_t i=0; i<n; ++i) if (data[i] != 0xa5) { close(fd); return 6; }
        received += n;
        if (now()-last >= 1) {
            printf("{\"event\":\"sample\",\"received\":%lu,\"elapsed\":%.6f}\n",
                   received, now()-start);
            fflush(stdout);
            last = now();
        }
    }
    double elapsed = now()-start;
    bool verified = received == total;
    if (verified) send(fd, "K", 1, MSG_NOSIGNAL);
    close(fd);
    printf("{\"event\":\"done\",\"verified\":%s,\"received\":%lu,\"elapsed\":%.6f,\"MBps\":%.6f}\n",
           verified ? "true" : "false", received, elapsed, received/elapsed/1e6);
    return verified ? 0 : 7;
}
