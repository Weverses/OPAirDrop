/* Process-local, reversible Wonder channel-width experiment.
 * Only nl80211 vendor requests on the live named Wonder interface are eligible.
 * Mode trace is byte-identical; mode20 normalizes the two cached widths.
 * Kernel ACKs remain authoritative. No replies or data frames are synthesized.
 */
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/genetlink.h>
#include <linux/netlink.h>
#include <linux/nl80211.h>
#include <net/if.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <unistd.h>

#define WONDER_VENDOR 0x001a11
#define MAX_MESSAGE 65536
static bool active, normalize_frequency, normalize_rate;
static uint16_t family;
static unsigned int interface_index;
static bool frequency_acked;
static uint64_t frequency_generation;
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
struct pending { bool used, valid; int fd; uint32_t port, seq, cmd, freq, index; uint64_t generation; };
static struct pending pending[64];

static void log_event(const char *format, ...) {
    const char *path=getenv("OPAIRSHARE_WIDTH_LOG");
    static const char prefix[]="/data/local/tmp/opairshare-width-";
    if (!path || strncmp(path,prefix,sizeof(prefix)-1)) return;
    int fd=open(path,O_WRONLY|O_CREAT|O_APPEND|O_CLOEXEC,0600);
    if (fd<0) return;
    va_list args; va_start(args,format); vdprintf(fd,format,args); va_end(args);
    dprintf(fd,"\n"); close(fd);
}
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
    const char *mode=getenv("OPAIRSHARE_WIDTH_MODE");
    if (!mode || (strcmp(mode,"trace") && strcmp(mode,"20") &&
                 strcmp(mode,"freq20") && strcmp(mode,"rate20"))) return;
    normalize_frequency=!strcmp(mode,"20") || !strcmp(mode,"freq20");
    normalize_rate=!strcmp(mode,"20") || !strcmp(mode,"rate20");
    family=resolve_family(); active=family>=GENL_MIN_ID;
    log_event("WIDTH_INIT mode=%s family=%u active=%u",mode,family,active);
    if (!active) log_event("WIDTH_INVALID family_resolution");
}
static int apply_width(unsigned char *bw, uint32_t cmd, uint32_t freq,
                       bool verified, bool enable) {
    unsigned int before=get16(bw);
    if (before!=0 && before!=2) return -1;
    if (cmd==15) return -3;
    if (!(cmd==1 ? freq==5745 : cmd==3 && verified)) return -2;
    if (enable && before==2) { uint16_t zero=0; memcpy(bw,&zero,2); return 1; }
    return 0;
}
static void acknowledge(struct pending *p, int error, unsigned int live_index) {
    if (p->cmd==1 && p->generation==frequency_generation)
        frequency_acked=!error && p->valid && p->freq==5745 &&
            p->index==interface_index && interface_index==live_index;
    if (error && p->generation==frequency_generation) frequency_acked=false;
    log_event("WIDTH_ACK seq=%u cmd=%u error=%d frequency_verified=%u generation=%llu",
              p->seq,p->cmd,error,frequency_acked,(unsigned long long)p->generation);
    p->used=false;
}
static void cancel_send(int fd) {
    pthread_mutex_lock(&lock);
    frequency_acked=false; frequency_generation++;
    for (unsigned int i=0;i<64;++i) if (pending[i].fd==fd) pending[i].used=false;
    pthread_mutex_unlock(&lock);
}
static void request(int fd, uint32_t port, struct nlmsghdr *h) {
    if (h->nlmsg_type!=family || h->nlmsg_len<NLMSG_LENGTH(GENL_HDRLEN)) return;
    struct genlmsghdr *g=NLMSG_DATA(h);
    if (g->cmd!=NL80211_CMD_VENDOR) return;
    unsigned char *attrs=(unsigned char *)g+GENL_HDRLEN;
    size_t length=h->nlmsg_len-NLMSG_LENGTH(GENL_HDRLEN);
    unsigned char *vendor=attribute(attrs,length,NL80211_ATTR_VENDOR_ID,4);
    unsigned char *command=attribute(attrs,length,NL80211_ATTR_VENDOR_SUBCMD,4);
    if (!vendor || get32(vendor)!=WONDER_VENDOR || !command) return;
    unsigned char *index=attribute(attrs,length,NL80211_ATTR_IFINDEX,4);
    const char *name=getenv("OPAIRSHARE_WIDTH_IFACE");
    if (!name || strcmp(name,"wonder0")) { log_event("WIDTH_INVALID interface_name"); return; }
    unsigned int current=if_nametoindex(name);
    if (!index || !current || get32(index)!=current) {
        log_event("WIDTH_INVALID interface command=%u",get32(command)); return;
    }
    if (interface_index!=current) {
        interface_index=current; frequency_acked=false; frequency_generation++;
        log_event("WIDTH_INTERFACE ifindex=%u",current);
    }
    uint32_t cmd=get32(command);
    if (cmd!=1 && cmd!=3 && cmd!=15) return;
    if (cmd==1 || cmd==15) { frequency_acked=false; frequency_generation++; }
    unsigned char *data=attribute(attrs,length,NL80211_ATTR_VENDOR_DATA,0);
    if (!data) { log_event("WIDTH_INVALID vendor_data cmd=%u",cmd); return; }
    struct nlattr container; memcpy(&container,data-NLA_HDRLEN,sizeof(container));
    size_t children=container.nla_len-NLA_HDRLEN;
    unsigned char *bw=attribute(data,children,2,2);
    uint32_t freq=0;
    if (cmd==1) {
        unsigned char *f=attribute(data,children,1,4);
        if (!f) { log_event("WIDTH_INVALID frequency"); return; }
        freq=get32(f);
    }
    if (!bw) { log_event("WIDTH_INVALID bandwidth cmd=%u",cmd); return; }
    unsigned int before=get16(bw), after=before;
    int changed=apply_width(bw,cmd,freq,frequency_acked,
                            cmd==1 ? normalize_frequency : normalize_rate);
    if (changed<0) log_event("WIDTH_INVALID request cmd=%u freq=%u bw=%u reason=%d",cmd,freq,before,changed);
    after=get16(bw);
    bool tracked=false;
    for (unsigned int i=0;i<64;++i) if (!pending[i].used) {
        pending[i]=(struct pending){.used=true,.valid=changed>=0,.fd=fd,.port=port,.seq=h->nlmsg_seq,.cmd=cmd,.freq=freq,.index=current,.generation=frequency_generation};
        tracked=true; break;
    }
    if (!tracked) { frequency_acked=false; log_event("WIDTH_INVALID pending_full"); }
    log_event("WIDTH_REQUEST fd=%d port=%u seq=%u cmd=%u ifindex=%u freq=%u bw=%u->%u tracked=%u",
              fd,port,h->nlmsg_seq,cmd,current,freq,before,after,tracked);
}
static void observe(int fd, unsigned char *data, size_t size, bool outgoing) {
    uint32_t port=socket_port(fd);
    if (!port) return;
    pthread_mutex_lock(&lock);
    size_t offset=0;
    while (offset+sizeof(struct nlmsghdr)<=size) {
        struct nlmsghdr *h=(struct nlmsghdr *)(data+offset);
        if (h->nlmsg_len<sizeof(*h) || h->nlmsg_len>size-offset) {
            log_event("WIDTH_INVALID nlmsg_length"); break;
        }
        if (outgoing) request(fd,port,h);
        else if (h->nlmsg_type==NLMSG_ERROR && h->nlmsg_len>=NLMSG_LENGTH(sizeof(struct nlmsgerr))) {
            struct nlmsgerr *e=NLMSG_DATA(h);
            for (unsigned int i=0;i<64;++i) if (pending[i].used && pending[i].fd==fd &&
                pending[i].port==port && pending[i].seq==h->nlmsg_seq) {
                if (e->msg.nlmsg_type!=family || e->msg.nlmsg_seq!=h->nlmsg_seq) {
                    frequency_acked=false;
                    log_event("WIDTH_INVALID ack_identity");
                    pending[i].used=false;
                    continue;
                }
                acknowledge(&pending[i],e->error,if_nametoindex("wonder0"));
            }
        }
        offset+=NLMSG_ALIGN(h->nlmsg_len);
    }
    pthread_mutex_unlock(&lock);
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
    if (!active || !socket_port(fd)) return real(fd,m,flags);
    size_t size; unsigned char *copy=flatten(m,&size);
    if (!copy) { log_event("WIDTH_INVALID sendmsg_layout"); return real(fd,m,flags); }
    observe(fd,copy,size,true);
    struct iovec iov={.iov_base=copy,.iov_len=size}; struct msghdr modified=*m;
    modified.msg_iov=&iov; modified.msg_iovlen=1;
    ssize_t result=real(fd,&modified,flags); int saved=errno;
    if (result<0) { cancel_send(fd); log_event("WIDTH_INVALID sendmsg_error errno=%d",saved); }
    free(copy); errno=saved; return result;
}
ssize_t sendto(int fd,const void *b,size_t n,int flags,const struct sockaddr *a,socklen_t l) {
    static _Atomic(void *) fn;
    void *p=atomic_load(&fn); if (!p) { p=dlvsym(RTLD_NEXT,"sendto","LIBC"); atomic_store(&fn,p); }
    ssize_t (*real)(int,const void *,size_t,int,const struct sockaddr *,socklen_t)=p;
    if (!real) { errno=ENOSYS; return -1; }
    if (!active || !socket_port(fd)) return real(fd,b,n,flags,a,l);
    if (n>MAX_MESSAGE) { log_event("WIDTH_INVALID sendto_length"); return real(fd,b,n,flags,a,l); }
    unsigned char *copy=malloc(n ? n : 1);
    if (!copy) { log_event("WIDTH_INVALID sendto_alloc"); return real(fd,b,n,flags,a,l); }
    memcpy(copy,b,n); observe(fd,copy,n,true);
    ssize_t result=real(fd,copy,n,flags,a,l); int saved=errno;
    if (result<0) { cancel_send(fd); log_event("WIDTH_INVALID sendto_error errno=%d",saved); }
    free(copy); errno=saved; return result;
}
ssize_t recvmsg(int fd,struct msghdr *m,int flags) {
    static _Atomic(void *) fn;
    void *p=atomic_load(&fn); if (!p) { p=dlvsym(RTLD_NEXT,"recvmsg","LIBC"); atomic_store(&fn,p); }
    ssize_t (*real)(int,struct msghdr *,int)=p;
    if (!real) { errno=ENOSYS; return -1; }
    ssize_t result=real(fd,m,flags); int saved=errno;
    if (active && result>0 && socket_port(fd)) {
        size_t size; unsigned char *copy=flatten(m,&size);
        if (copy) { observe(fd,copy,(size_t)result<size ? (size_t)result : size,false); free(copy); }
        else log_event("WIDTH_INVALID recvmsg_layout");
    }
    errno=saved; return result;
}
ssize_t recvfrom(int fd,void *b,size_t n,int flags,struct sockaddr *a,socklen_t *l) {
    static _Atomic(void *) fn;
    void *p=atomic_load(&fn); if (!p) { p=dlvsym(RTLD_NEXT,"recvfrom","LIBC"); atomic_store(&fn,p); }
    ssize_t (*real)(int,void *,size_t,int,struct sockaddr *,socklen_t *)=p;
    if (!real) { errno=ENOSYS; return -1; }
    ssize_t result=real(fd,b,n,flags,a,l); int saved=errno;
    if (active && result>0 && (size_t)result<=n) observe(fd,b,result,false);
    errno=saved; return result;
}
#ifdef COEX_WIDTH_SELF_TEST
int main(void) {
    unsigned char bytes[16]={8,0,1,0,0x71,0x16,0,0,6,0,2,0,2,0,0,0};
    assert(attribute(bytes,16,1,4)==bytes+4);
    assert(get32(attribute(bytes,16,1,4))==5745);
    assert(get16(attribute(bytes,16,2,2))==2);
    assert(get16(attribute(bytes,14,2,2))==2);
    assert(!attribute(bytes,15,2,2));
    assert(!attribute(bytes,16,1,2));
    bytes[10]=1; assert(!attribute(bytes,16,1,0));
    bytes[0]=3; assert(!attribute(bytes,16,2,0));
    char a[]="abcd", b[]="efgh";
    struct iovec iov[2]={{a,4},{b,4}}; struct msghdr m={.msg_iov=iov,.msg_iovlen=2};
    size_t size=0; unsigned char *copy=flatten(&m,&size);
    assert(size==8 && !memcmp(copy,"abcdefgh",8)); free(copy);
    uint16_t bw=2;
    assert(apply_width((unsigned char *)&bw,1,5745,false,false)==0 && bw==2);
    assert(apply_width((unsigned char *)&bw,1,5805,false,true)==-2 && bw==2);
    assert(apply_width((unsigned char *)&bw,3,0,false,true)==-2 && bw==2);
    assert(apply_width((unsigned char *)&bw,1,5745,false,true)==1 && bw==0);
    bw=2; assert(apply_width((unsigned char *)&bw,3,0,true,true)==1 && bw==0);
    bw=1; assert(apply_width((unsigned char *)&bw,1,5745,false,true)==-1 && bw==1);
    bw=2; assert(apply_width((unsigned char *)&bw,15,0,true,true)==-3 && bw==2);
    interface_index=7; frequency_generation=2; frequency_acked=false;
    struct pending old={.used=true,.valid=true,.cmd=1,.freq=5745,.index=7,.generation=1};
    acknowledge(&old,0,7); assert(!frequency_acked);
    old.used=true; old.generation=2; acknowledge(&old,0,7); assert(frequency_acked);
    old.used=true; acknowledge(&old,-22,7); assert(!frequency_acked);
    old.used=true; acknowledge(&old,0,8); assert(!frequency_acked);
    pending[0]=(struct pending){.used=true,.fd=99}; frequency_acked=true;
    cancel_send(99); assert(!frequency_acked && !pending[0].used);
    puts("WIDTH_SELF_TEST_OK"); return 0;
}
#endif
