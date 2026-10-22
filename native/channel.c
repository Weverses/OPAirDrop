/* Wonder SET_FREQUENCY adapter, extracted from the measured freq20 profile.
 * Normalize only 5745 MHz channel setup from enum 2 (80 MHz) to 0 (20 MHz).
 * SET_FIXED_TX_RATE is deliberately left intact: it also writes firmware width.
 * This process-local preload does not alter STA power save, ACK filtering or PHY.
 */
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <linux/genetlink.h>
#include <linux/netlink.h>
#include <linux/nl80211.h>
#include <net/if.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <unistd.h>

#define WONDER_VENDOR 0x001a11
#define MAX_MESSAGE 65536
static bool active;
static uint16_t family;

static uint32_t get32(const void *p) { uint32_t n; memcpy(&n,p,4); return n; }
static uint16_t get16(const void *p) { uint16_t n; memcpy(&n,p,2); return n; }

/* Returns NULL for missing, malformed or duplicate requested attributes. */
static unsigned char *attribute(unsigned char *p, size_t length,
                                 unsigned int type, size_t expected) {
    unsigned char *found=NULL;
    while (length) {
        if (length<NLA_HDRLEN) return NULL;
        struct nlattr a; memcpy(&a,p,sizeof(a));
        if (a.nla_len<NLA_HDRLEN || a.nla_len>length) return NULL;
        size_t step=NLA_ALIGN(a.nla_len);
        /* A nested container may omit padding after its final child. */
        if (step>length) {
            if (a.nla_len!=length) return NULL;
            step=length;
        }
        if ((a.nla_type & NLA_TYPE_MASK)==type) {
            if (found || (expected && a.nla_len-NLA_HDRLEN!=expected)) return NULL;
            found=p+NLA_HDRLEN;
        }
        p+=step; length-=step;
    }
    return found;
}
static uint32_t socket_port(int fd) {
    struct sockaddr_nl local={0}; socklen_t length=sizeof(local);
    int protocol=0; socklen_t size=sizeof(protocol);
    if (getsockname(fd,(struct sockaddr *)&local,&length) ||
        local.nl_family!=AF_NETLINK ||
        getsockopt(fd,SOL_SOCKET,SO_PROTOCOL,&protocol,&size) || protocol!=NETLINK_GENERIC)
        return 0;
    return local.nl_pid;
}
static uint16_t resolve_family(void) {
    int fd=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC,NETLINK_GENERIC);
    if (fd<0) return 0;
    struct sockaddr_nl local={.nl_family=AF_NETLINK}, kernel={.nl_family=AF_NETLINK};
    struct timeval wait={.tv_sec=1};
    setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&wait,sizeof(wait));
    static char buffer[MAX_MESSAGE]; struct nlmsghdr *h=(struct nlmsghdr *)buffer;
    h->nlmsg_len=NLMSG_LENGTH(GENL_HDRLEN)+NLA_ALIGN(NLA_HDRLEN+8);
    h->nlmsg_type=GENL_ID_CTRL; h->nlmsg_flags=NLM_F_REQUEST; h->nlmsg_seq=1;
    struct genlmsghdr *g=NLMSG_DATA(h); g->cmd=CTRL_CMD_GETFAMILY; g->version=1;
    struct nlattr *a=(struct nlattr *)((char *)g+GENL_HDRLEN);
    a->nla_type=CTRL_ATTR_FAMILY_NAME; a->nla_len=NLA_HDRLEN+8;
    memcpy((char *)a+NLA_HDRLEN,"nl80211",8);
    uint16_t result=0;
    if (!bind(fd,(struct sockaddr *)&local,sizeof(local)) &&
        syscall(SYS_sendto,fd,buffer,h->nlmsg_len,0,&kernel,sizeof(kernel))>=0) {
        ssize_t n=syscall(SYS_recvfrom,fd,buffer,sizeof(buffer),MSG_TRUNC,NULL,NULL);
        if (n>=(ssize_t)NLMSG_LENGTH(GENL_HDRLEN) && (size_t)n<=sizeof(buffer) && h->nlmsg_len<=(size_t)n &&
            h->nlmsg_type==GENL_ID_CTRL) {
            unsigned char *attrs=(unsigned char *)NLMSG_DATA(h)+GENL_HDRLEN;
            size_t length=h->nlmsg_len-NLMSG_LENGTH(GENL_HDRLEN);
            unsigned char *id=attribute(attrs,length,CTRL_ATTR_FAMILY_ID,2);
            unsigned char *name=attribute(attrs,length,CTRL_ATTR_FAMILY_NAME,8);
            if (id && name && !memcmp(name,"nl80211",8)) result=get16(id);
        }
    }
    close(fd); return result;
}
__attribute__((constructor)) static void initialize(void) {
    const char *profile=getenv("OPAIRSHARE_CHANNEL_PROFILE");
    if (!profile || strcmp(profile,"freq20")) return;
    family=resolve_family(); active=family>=GENL_MIN_ID;
    __android_log_print(ANDROID_LOG_INFO,"OPAirShareChannel",
                        "profile=freq20 family=%u active=%u",family,active);
}
static void request(struct nlmsghdr *h) {
    if (h->nlmsg_type!=family || h->nlmsg_len<NLMSG_LENGTH(GENL_HDRLEN)) return;
    struct genlmsghdr *g=NLMSG_DATA(h);
    if (g->cmd!=NL80211_CMD_VENDOR) return;
    unsigned char *attrs=(unsigned char *)g+GENL_HDRLEN;
    size_t length=h->nlmsg_len-NLMSG_LENGTH(GENL_HDRLEN);
    unsigned char *vendor=attribute(attrs,length,NL80211_ATTR_VENDOR_ID,4);
    unsigned char *command=attribute(attrs,length,NL80211_ATTR_VENDOR_SUBCMD,4);
    if (!vendor || get32(vendor)!=WONDER_VENDOR || !command || get32(command)!=1) return;
    unsigned char *index=attribute(attrs,length,NL80211_ATTR_IFINDEX,4);
    unsigned int current=if_nametoindex("wonder0");
    if (!index || !current || get32(index)!=current) return;
    unsigned char *data=attribute(attrs,length,NL80211_ATTR_VENDOR_DATA,0);
    if (!data) return;
    struct nlattr container; memcpy(&container,data-NLA_HDRLEN,sizeof(container));
    size_t children=container.nla_len-NLA_HDRLEN;
    unsigned char *freq=attribute(data,children,1,4);
    unsigned char *bw=attribute(data,children,2,2);
    if (!freq || get32(freq)!=5745 || !bw || get16(bw)!=2) return;
    uint16_t narrow=0; memcpy(bw,&narrow,sizeof(narrow));
    __android_log_print(ANDROID_LOG_INFO,"OPAirShareChannel",
                        "SET_FREQUENCY ifindex=%u freq=5745 bw=2->0 seq=%u",current,h->nlmsg_seq);
}
static void normalize(unsigned char *data, size_t size) {
    size_t offset=0;
    while (offset+sizeof(struct nlmsghdr)<=size) {
        struct nlmsghdr *h=(struct nlmsghdr *)(data+offset);
        if (h->nlmsg_len<sizeof(*h) || h->nlmsg_len>size-offset) break;
        request(h);
        offset+=NLMSG_ALIGN(h->nlmsg_len);
    }
}
static unsigned char *flatten(const struct msghdr *m, size_t *size) {
    *size=0;
    if (!m || !m->msg_iov || !m->msg_iovlen || m->msg_iovlen>16) return NULL;
    for (size_t i=0;i<m->msg_iovlen;++i) {
        if (m->msg_iov[i].iov_len>MAX_MESSAGE-*size) return NULL;
        *size+=m->msg_iov[i].iov_len;
    }
    unsigned char *copy=malloc(*size ? *size : 1);
    if (!copy) return NULL;
    size_t offset=0;
    for (size_t i=0;i<m->msg_iovlen;++i) {
        memcpy(copy+offset,m->msg_iov[i].iov_base,m->msg_iov[i].iov_len);
        offset+=m->msg_iov[i].iov_len;
    }
    return copy;
}
ssize_t sendmsg(int fd,const struct msghdr *m,int flags) {
    static _Atomic(void *) fn;
    void *p=atomic_load(&fn); if (!p) { p=dlvsym(RTLD_NEXT,"sendmsg","LIBC"); atomic_store(&fn,p); }
    ssize_t (*real)(int,const struct msghdr *,int)=p;
    if (!real) { errno=ENOSYS; return -1; }
    int incoming=errno;
    bool eligible=active && socket_port(fd);
    errno=incoming;
    if (!eligible) return real(fd,m,flags);
    size_t size; unsigned char *copy=flatten(m,&size);
    if (!copy) { errno=incoming; return real(fd,m,flags); }
    normalize(copy,size);
    struct iovec iov={.iov_base=copy,.iov_len=size}; struct msghdr modified=*m;
    modified.msg_iov=&iov; modified.msg_iovlen=1;
    errno=incoming;
    ssize_t result=real(fd,&modified,flags); int saved=errno;
    free(copy); errno=saved; return result;
}
ssize_t sendto(int fd,const void *b,size_t n,int flags,const struct sockaddr *a,socklen_t l) {
    static _Atomic(void *) fn;
    void *p=atomic_load(&fn); if (!p) { p=dlvsym(RTLD_NEXT,"sendto","LIBC"); atomic_store(&fn,p); }
    ssize_t (*real)(int,const void *,size_t,int,const struct sockaddr *,socklen_t)=p;
    if (!real) { errno=ENOSYS; return -1; }
    int incoming=errno;
    bool eligible=active && socket_port(fd);
    errno=incoming;
    if (!eligible) return real(fd,b,n,flags,a,l);
    if (n>MAX_MESSAGE) { return real(fd,b,n,flags,a,l); }
    unsigned char *copy=malloc(n ? n : 1);
    if (!copy) { errno=incoming; return real(fd,b,n,flags,a,l); }
    memcpy(copy,b,n); normalize(copy,n);
    errno=incoming;
    ssize_t result=real(fd,copy,n,flags,a,l); int saved=errno;
    free(copy); errno=saved; return result;
}
