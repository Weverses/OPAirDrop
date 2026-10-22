/* Experimental, bounded NFQUEUE TCP ordering probe, for one generated flow.
 * No packet contents are changed. The host must install a source/port-scoped
 * --queue-bypass rule and remove it after the probe exits.
 */
#include <arpa/inet.h>
#include <errno.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nfnetlink.h>
#include <linux/netfilter/nfnetlink_queue.h>
#include <linux/netlink.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define QUEUE 18773
#define SLOTS 256
struct held { uint32_t id, seq, end; bool used; };
static struct held held[SLOTS];
static int nl = -1;
static uint32_t expected, message_seq;
static bool initialized, selftest;
static unsigned int hold_ms;
static double deadline;
static volatile sig_atomic_t stopped;
static unsigned long queued, released, late, timeouts, overflow;
static uint32_t emitted[32];
static unsigned int emitted_count;

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec/1e9;
}
static void stop(int signal_number) { (void)signal_number; stopped = 1; }
static bool before(uint32_t a, uint32_t b) { return (int32_t)(a-b) < 0; }

static size_t attr(char *buffer, uint16_t type, const void *data, size_t size) {
    struct nlattr *a = (struct nlattr *)buffer;
    a->nla_type = type;
    a->nla_len = NLA_HDRLEN + size;
    memcpy(buffer+NLA_HDRLEN, data, size);
    size_t length = NLA_ALIGN(a->nla_len);
    memset(buffer+a->nla_len, 0, length-a->nla_len);
    return length;
}
static int send_message(uint8_t type, const char *attrs, size_t length) {
    char buffer[512] = {0};
    struct nlmsghdr *h = (struct nlmsghdr *)buffer;
    h->nlmsg_len = NLMSG_LENGTH(sizeof(struct nfgenmsg)) + length;
    h->nlmsg_type = (NFNL_SUBSYS_QUEUE << 8) | type;
    h->nlmsg_flags = NLM_F_REQUEST;
    h->nlmsg_seq = ++message_seq;
    struct nfgenmsg *g = NLMSG_DATA(h);
    g->nfgen_family = AF_INET6;
    g->version = NFNETLINK_V0;
    g->res_id = htons(QUEUE);
    memcpy((char *)g+sizeof(*g), attrs, length);
    return send(nl, buffer, h->nlmsg_len, 0) < 0 ? -1 : 0;
}
static void accept_packet(uint32_t id) {
    if (selftest) {
        if (emitted_count < 32) emitted[emitted_count++] = id;
        return;
    }
    struct nfqnl_msg_verdict_hdr v = {.verdict=htonl(NF_ACCEPT), .id=htonl(id)};
    char buffer[64];
    size_t n = attr(buffer, NFQA_VERDICT_HDR, &v, sizeof(v));
    if (send_message(NFQNL_MSG_VERDICT, buffer, n)) { perror("verdict"); stopped=1; }
}
static void drain_ready(void) {
    bool progress;
    do {
        progress = false;
        for (int i=0; i<SLOTS; ++i) {
            if (!held[i].used || before(expected, held[i].seq)) continue;
            if (before(expected, held[i].end)) expected = held[i].end;
            accept_packet(held[i].id);
            held[i].used=false;
            released++;
            progress=true;
        }
    } while (progress);
    bool pending=false;
    for (int i=0; i<SLOTS; ++i) pending |= held[i].used;
    if (!pending) deadline=0;
}
static void expire(void) {
    int first=-1;
    for (int i=0; i<SLOTS; ++i)
        if (held[i].used && (first<0 || before(held[i].seq, held[first].seq))) first=i;
    if (first>=0) {
        expected=held[first].seq;
        timeouts++;
        drain_ready();
        if (deadline) deadline=now()+hold_ms/1000.0;
    }
}
static void packet(uint32_t id, uint32_t seq, unsigned int size) {
    if (!size || !hold_ms) { accept_packet(id); return; }
    if (!initialized) { initialized=true; expected=seq; }
    uint32_t end=seq+size;
    if (!before(expected, seq)) {
        if (before(seq, expected)) late++;
        if (before(expected, end)) expected=end;
        accept_packet(id);
        drain_ready();
        return;
    }
    for (int i=0; i<SLOTS; ++i) if (!held[i].used) {
        held[i]=(struct held){.id=id, .seq=seq, .end=end, .used=true};
        queued++;
        if (!deadline) deadline=now()+hold_ms/1000.0;
        return;
    }
    overflow++;
    expire();
    accept_packet(id);
}
static uint32_t u32(const unsigned char *p) { uint32_t n; memcpy(&n,p,4); return ntohl(n); }
static void process(struct nlmsghdr *h) {
    if (h->nlmsg_type == NLMSG_ERROR) {
        if (h->nlmsg_len >= NLMSG_LENGTH(sizeof(struct nlmsgerr))) {
            struct nlmsgerr *e=NLMSG_DATA(h);
            if (e->error) { errno=-e->error; perror("nfqueue"); stopped=1; }
        }
        return;
    }
    if ((h->nlmsg_type & 0xff) != NFQNL_MSG_PACKET ||
        h->nlmsg_len < NLMSG_LENGTH(sizeof(struct nfgenmsg))) return;
    int remaining=h->nlmsg_len-NLMSG_LENGTH(sizeof(struct nfgenmsg));
    unsigned char *a=(unsigned char *)NLMSG_DATA(h)+sizeof(struct nfgenmsg);
    uint32_t id=0;
    bool have_id=false;
    unsigned char *data=NULL;
    int length=0;
    while (remaining>=NLA_HDRLEN) {
        struct nlattr *n=(struct nlattr *)a;
        if (n->nla_len<NLA_HDRLEN || n->nla_len>remaining) break;
        int size=n->nla_len-NLA_HDRLEN;
        if ((n->nla_type & NLA_TYPE_MASK)==NFQA_PACKET_HDR &&
            size>=(int)sizeof(struct nfqnl_msg_packet_hdr)) {
            id=u32(a+NLA_HDRLEN); have_id=true;
        }
        if ((n->nla_type & NLA_TYPE_MASK)==NFQA_PAYLOAD) { data=a+NLA_HDRLEN; length=size; }
        int advance=NLA_ALIGN(n->nla_len);
        remaining-=advance; a+=advance;
    }
    if (!have_id) return;
    if (!data || length<60 || data[0]>>4 != 6 || data[6]!=6) { accept_packet(id); return; }
    unsigned int tcp_header=(data[52]>>4)*4;
    unsigned int payload=(data[4]<<8)|data[5];
    if (tcp_header<20 || payload<tcp_header || length<40+(int)tcp_header) {
        accept_packet(id); return;
    }
    if (data[53]&2) { expected=u32(data+44)+1; initialized=true; }
    packet(id, u32(data+44), payload-tcp_header);
}
static int test(void) {
    selftest=true; hold_ms=8;
    packet(1,100,100); packet(3,300,100); packet(2,200,100);
    if (emitted_count!=3 || emitted[0]!=1 || emitted[1]!=2 || emitted[2]!=3) return 1;
    packet(4,200,100); packet(6,500,100); expire();
    if (emitted_count!=5 || emitted[3]!=4 || emitted[4]!=6 || expected!=600) return 2;
    puts("ORDER_SELF_TEST_OK"); return 0;
}
int main(int argc, char **argv) {
    if (argc==2 && !strcmp(argv[1],"--self-test")) return test();
    if (argc!=3) return 2;
    char *end;
    unsigned long hold=strtoul(argv[1],&end,10);
    if (*end || hold>20) return 2;
    unsigned long seconds=strtoul(argv[2],&end,10);
    if (*end || seconds<1 || seconds>300) return 2;
    hold_ms=hold;
    signal(SIGTERM,stop); signal(SIGINT,stop); signal(SIGHUP,stop);
    nl=socket(AF_NETLINK,SOCK_RAW,NETLINK_NETFILTER);
    struct sockaddr_nl local={.nl_family=AF_NETLINK};
    struct sockaddr_nl kernel={.nl_family=AF_NETLINK};
    if (nl<0 || bind(nl,(struct sockaddr *)&local,sizeof(local)) ||
        connect(nl,(struct sockaddr *)&kernel,sizeof(kernel))) { perror("netlink"); return 3; }
    int buffer_size=4*1024*1024;
    setsockopt(nl,SOL_SOCKET,SO_RCVBUF,&buffer_size,sizeof(buffer_size));
    struct nfqnl_msg_config_cmd cmd={.command=NFQNL_CFG_CMD_BIND,.pf=htons(AF_INET6)};
    char attrs[128];
    size_t n=attr(attrs,NFQA_CFG_CMD,&cmd,sizeof(cmd));
    if (send_message(NFQNL_MSG_CONFIG,attrs,n)) return 4;
    struct nfqnl_msg_config_params params={.copy_range=htonl(128),.copy_mode=NFQNL_COPY_PACKET};
    n=attr(attrs,NFQA_CFG_PARAMS,&params,sizeof(params));
    uint32_t max=htonl(4096);
    n+=attr(attrs+n,NFQA_CFG_QUEUE_MAXLEN,&max,sizeof(max));
    if (send_message(NFQNL_MSG_CONFIG,attrs,n)) return 4;
    puts("ORDER_READY"); fflush(stdout);
    double until=now()+seconds;
    unsigned char buffer[65536];
    while (!stopped && now()<until) {
        struct pollfd p={.fd=nl,.events=POLLIN};
        int timeout=deadline ? (int)((deadline-now())*1000) : 100;
        if (timeout<0) timeout=0;
        if (timeout>100) timeout=100;
        if (poll(&p,1,timeout)>0) {
            ssize_t size=recv(nl,buffer,sizeof(buffer),0);
            if (size<0) { if (errno==EINTR) continue; perror("recv netlink"); break; }
            size_t offset=0;
            while (offset+sizeof(struct nlmsghdr)<=(size_t)size) {
                struct nlmsghdr *h=(struct nlmsghdr *)(buffer+offset);
                if (h->nlmsg_len<sizeof(*h) || h->nlmsg_len>(size_t)size-offset) break;
                process(h);
                offset+=NLMSG_ALIGN(h->nlmsg_len);
            }
        }
        if (deadline && now()>=deadline) expire();
    }
    for (int i=0; i<SLOTS; ++i) if (held[i].used) accept_packet(held[i].id);
    close(nl);
    printf("ORDER_END held=%lu released=%lu late=%lu timeouts=%lu overflow=%lu\n",
           queued,released,late,timeouts,overflow);
    return stopped ? 5 : 0;
}
