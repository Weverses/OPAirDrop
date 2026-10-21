/* SPDX-License-Identifier: GPL-2.0-only */
/* Read-only counters for the ACTIVE legacy monitor 1.0 path. */
#ifndef OPAIRSHARE_RX_AUDIT_HOST_TEST
#include <linux/moduleparam.h>
#include <linux/atomic.h>
#endif
static bool passthru_awdl_rx_audit;
module_param(passthru_awdl_rx_audit, bool, 0644);
#define OPAIRSHARE_AUDIT_EVENTS(X) \
 X(pop) X(rxdma_drop) X(invalid_msdu) X(dup_link) X(dup_buffer) \
 X(refill_drop) X(bad_mpdu_tag) X(ppdu_ahead) X(ppdu_wrap) \
 X(parent_alloc_fail) X(add_list_fail) X(link_return_fail) \
 X(policy_drop) X(stuck) X(ppdu_break) X(deliver) X(empty) \
 X(consumed) X(replenish_buffers) X(reo_target) X(reo_raw) X(reo_nonraw) \
 X(reo_error_target)
#define OPAIRSHARE_AUDIT_ENUM(n) OPAIRSHARE_AUDIT_##n,
enum opairshare_audit_event { OPAIRSHARE_AUDIT_EVENTS(OPAIRSHARE_AUDIT_ENUM) OPAIRSHARE_AUDIT_MAX };
#undef OPAIRSHARE_AUDIT_ENUM
#define OPAIRSHARE_AUDIT_NAME(n) #n,
static const char *const opairshare_audit_names[] = { OPAIRSHARE_AUDIT_EVENTS(OPAIRSHARE_AUDIT_NAME) };
#undef OPAIRSHARE_AUDIT_NAME
static atomic64_t opairshare_audit_counts[OPAIRSHARE_AUDIT_MAX];
static atomic64_t opairshare_audit_rxdma[32];
static atomic64_t opairshare_audit_pop_hist[66], opairshare_audit_delivery_hist[66];
static atomic64_t opairshare_audit_dst_peak = ATOMIC64_INIT(0);
static atomic64_t opairshare_audit_posted_min = ATOMIC64_INIT(-1);
static atomic64_t opairshare_audit_posted_last = ATOMIC64_INIT(-1);
/* Updated under the existing per-MAC mon_lock, not a new data-path lock. */
struct opairshare_audit_ppdu { u32 id, popped, delivered; bool valid; };
static struct opairshare_audit_ppdu opairshare_audit_ppdus[8];
bool opairshare_awdl_target_vdev_match(u8 id);

bool opairshare_rx_audit_enabled(void) { return READ_ONCE(passthru_awdl_rx_audit); }
static void opairshare_audit_add(enum opairshare_audit_event ev, u32 n)
{
	if (opairshare_rx_audit_enabled()) atomic64_add(n, &opairshare_audit_counts[ev]);
}
#define OPAIRSHARE_AUDIT(n) opairshare_audit_add(OPAIRSHARE_AUDIT_##n, 1)
static void opairshare_audit_error(u32 code)
{
	if (opairshare_rx_audit_enabled() && code < 32) atomic64_inc(&opairshare_audit_rxdma[code]);
}
static void opairshare_audit_peak(atomic64_t *counter, u64 n)
{
	s64 old = atomic64_read(counter);
	while ((u64)old < n) {
		s64 seen = atomic64_cmpxchg(counter, old, n);
		if (seen == old) break;
		old = seen;
	}
}
static void opairshare_audit_ring(u32 occupied, u32 posted)
{
	s64 old;
	if (!opairshare_rx_audit_enabled()) return;
	opairshare_audit_peak(&opairshare_audit_dst_peak, occupied);
	atomic64_set(&opairshare_audit_posted_last, posted);
	old = atomic64_read(&opairshare_audit_posted_min);
	while ((u64)old > posted) {
		s64 seen = atomic64_cmpxchg(&opairshare_audit_posted_min, old, posted);
		if (seen == old) break;
		old = seen;
	}
}
static void opairshare_audit_ppdu(u32 mac, u32 id, bool delivered)
{
	struct opairshare_audit_ppdu *p;
	if (!opairshare_rx_audit_enabled() || mac >= 8) return;
	p = &opairshare_audit_ppdus[mac];
	if (p->valid && p->id != id) {
		atomic64_inc(&opairshare_audit_pop_hist[min_t(u32, p->popped, 65)]);
		atomic64_inc(&opairshare_audit_delivery_hist[min_t(u32, p->delivered, 65)]);
		p->popped = p->delivered = 0;
	}
	p->valid = true; p->id = id; p->popped++; p->delivered += delivered;
}
/* Called before the old RAW-format and shared trace-budget tests. */
void opairshare_rx_audit_native(u8 vdev, bool raw, unsigned int src)
{
	if (!opairshare_rx_audit_enabled() || !opairshare_awdl_target_vdev_match(vdev)) return;
	if (src == 8) {
		OPAIRSHARE_AUDIT(reo_target);
		opairshare_audit_add(raw ? OPAIRSHARE_AUDIT_reo_raw : OPAIRSHARE_AUDIT_reo_nonraw, 1);
	} else {
		OPAIRSHARE_AUDIT(reo_error_target);
	}
}
static int opairshare_rx_audit_get(char *out, const struct kernel_param *kp)
{
	int n = 0;
	unsigned int i;
	(void)kp;
	n += scnprintf(out+n, 4096-n, "enabled=%u dst_peak=%lld posted_cached_min=%lld posted_cached_last=%lld\n",
		READ_ONCE(passthru_awdl_rx_audit), (long long)atomic64_read(&opairshare_audit_dst_peak),
		(long long)atomic64_read(&opairshare_audit_posted_min), (long long)atomic64_read(&opairshare_audit_posted_last));
	for (i = 0; i < OPAIRSHARE_AUDIT_MAX; i++)
		n += scnprintf(out+n, 4096-n, "%s=%lld%s", opairshare_audit_names[i],
			(long long)atomic64_read(&opairshare_audit_counts[i]), i+1==OPAIRSHARE_AUDIT_MAX ? "\n" : " ");
	n += scnprintf(out+n, 4096-n, "rxdma_errors:");
	for (i = 0; i < 32; i++) if (atomic64_read(&opairshare_audit_rxdma[i]))
		n += scnprintf(out+n, 4096-n, " %u:%lld", i, (long long)atomic64_read(&opairshare_audit_rxdma[i]));
	n += scnprintf(out+n, 4096-n, "\nppdu_consumed_hist:");
	for (i = 0; i < 66; i++) if (atomic64_read(&opairshare_audit_pop_hist[i]))
		n += scnprintf(out+n, 4096-n, " %u:%lld", i, (long long)atomic64_read(&opairshare_audit_pop_hist[i]));
	n += scnprintf(out+n, 4096-n, "\nppdu_delivered_hist:");
	for (i = 0; i < 66; i++) if (atomic64_read(&opairshare_audit_delivery_hist[i]))
		n += scnprintf(out+n, 4096-n, " %u:%lld", i, (long long)atomic64_read(&opairshare_audit_delivery_hist[i]));
	n += scnprintf(out+n, 4096-n, "\n");
	return n;
}
static const struct kernel_param_ops opairshare_rx_audit_ops = { .get = opairshare_rx_audit_get };
module_param_cb(opairshare_rx_audit_stats, &opairshare_rx_audit_ops, NULL, 0444);
