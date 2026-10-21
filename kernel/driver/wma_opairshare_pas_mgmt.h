/* SPDX-License-Identifier: GPL-2.0-only */
/* Included only by wma_data.c, after the native wma_tx_packet definition. */
#ifdef DRIVER_PASSTHRU_MODE
#include <linux/workqueue.h>
#include <linux/hrtimer.h>
#include <linux/wait.h>
#include <linux/slab.h>
#include <linux/module.h>
#include <linux/moduleparam.h>
#include <linux/ieee80211.h>

static bool passthru_mgmt_bridge;
module_param(passthru_mgmt_bridge, bool, 0444);
MODULE_PARM_DESC(passthru_mgmt_bridge, "Opt-in PAS/NDI netdev management TX via native WMI");

static bool passthru_mgmt_home_channel;
module_param(passthru_mgmt_home_channel, bool, 0644);
MODULE_PARM_DESC(passthru_mgmt_home_channel, "PAS-only diagnostic current-channel management TX");
static bool passthru_mgmt_raw_tx;
module_param(passthru_mgmt_raw_tx, bool, 0644);
MODULE_PARM_DESC(passthru_mgmt_raw_tx, "PAS-only diagnostic WMI RAW non-pause TID at native 6Mbps");

static bool passthru_awdl_wmi_raw;
module_param(passthru_awdl_wmi_raw, bool, 0644);
MODULE_PARM_DESC(passthru_awdl_wmi_raw,
                "Diagnostic: PAS AWDL QoS data over native WMI RAW TX");

static unsigned int passthru_awdl_rate_mbps = 6;
static int opairshare_awdl_rate_set(const char *value, const struct kernel_param *kp)
{
	unsigned int rate;
	int rc = kstrtouint(value, 0, &rate);
	if (rc)
		return rc;
	switch (rate) {
	case 6: case 9: case 12: case 18: case 24: case 36: case 48: case 54:
		WRITE_ONCE(*(unsigned int *)kp->arg, rate);
		return 0;
	default:
		return -EINVAL;
	}
}
static const struct kernel_param_ops opairshare_awdl_rate_ops = {
	.set = opairshare_awdl_rate_set, .get = param_get_uint,
};
module_param_cb(passthru_awdl_rate_mbps, &opairshare_awdl_rate_ops,
		&passthru_awdl_rate_mbps, 0644);
MODULE_PARM_DESC(passthru_awdl_rate_mbps,
	"PAS AWDL WMI DATA-only OFDM rate; default is proven 6Mbps");
static enum rateid opairshare_awdl_rate_id(void)
{
	switch (READ_ONCE(passthru_awdl_rate_mbps)) {
	case 9: return RATEID_9MBPS;
	case 12: return RATEID_12MBPS;
	case 18: return RATEID_18MBPS;
	case 24: return RATEID_24MBPS;
	case 36: return RATEID_36MBPS;
	case 48: return RATEID_48MBPS;
	case 54: return RATEID_54MBPS;
	default: return RATEID_6MBPS;
	}
}

static bool passthru_awdl_host_seq;
module_param(passthru_awdl_host_seq, bool, 0644);
MODULE_PARM_DESC(passthru_awdl_host_seq,
	"Opt-in explicit host sequence ownership for verified native AWDL RAW ABI");
#include "wma_opairshare_awdl_sequence.h"

static unsigned int passthru_awdl_attempts = 3;
static int opairshare_awdl_attempts_set(const char *value, const struct kernel_param *kp)
{
	unsigned int attempts;
	int rc = kstrtouint(value, 0, &attempts);
	if (rc)
		return rc;
	if (attempts < 1 || attempts > 7)
		return -EINVAL;
	WRITE_ONCE(*(unsigned int *)kp->arg, attempts);
	return 0;
}
static const struct kernel_param_ops opairshare_awdl_attempts_ops = {
	.set = opairshare_awdl_attempts_set, .get = param_get_uint,
};
module_param_cb(passthru_awdl_attempts, &opairshare_awdl_attempts_ops,
		&passthru_awdl_attempts, 0644);
MODULE_PARM_DESC(passthru_awdl_attempts,
	"AWDL unicast maximum WMI transmissions including first; 1..3, default3");
static u8 opairshare_awdl_attempts_snapshot(bool unicast)
{
	return unicast ? READ_ONCE(passthru_awdl_attempts) : 3;
}

/* ---- stage54-agg-core.h (inlined verbatim by stage54-agg.py) ---- */
/* SPDX-License-Identifier: GPL-2.0-only */
/* stage54: pure helpers for the OPAirShare AWDL bridge.
 *
 *   * classification of a queued AWDL QoS-data frame (A-MSDU eligibility and
 *     "plain TCP ACK" recognition),
 *   * the join rule that decides whether a queued frame may ride in the same
 *     A-MSDU as the frame at the head of the queue,
 *   * the A-MSDU subframe layout (IEEE 802.11-2016 9.3.2.2.2: DA | SA | Length
 *     big-endian | MSDU | pad to 4 between subframes, none after the last).
 *
 * This file is compiled twice from the SAME bytes: inside the kernel module
 * (stage54-agg.py inlines it into wma_opairshare_pas_mgmt.h) and by the host selftest
 * (stage54-agg-selftest.c).  It therefore uses nothing but uint*_t, size_t,
 * memcmp/memcpy/memset; the includer provides those.  No locking, no
 * allocation, no skb: callers hand it plain byte pointers.
 *
 * MSDU of an AWDL data frame, as seen on the air (STAGE54 capture dump):
 *     LLC/SNAP  aa aa 03 00 17 f2 08 00                       (8)
 *     AWDL hdr  03 04 <seq16 LE> 00 00 86 dd                   (8)
 *     IPv6 packet                                              (40 + ...)
 */
#ifndef STAGE54_AGG_CORE_H
#define STAGE54_AGG_CORE_H

#define OPAIRSHARE_AGG_QOS_HDR	26u	/* 802.11 QoS data header, no HTC */
#define OPAIRSHARE_AGG_SUB_HDR	14u	/* DA(6) SA(6) Length(2) */
#define OPAIRSHARE_AGG_LLC_LEN	8u
#define OPAIRSHARE_AGG_AWDL_LEN	8u
#define OPAIRSHARE_AGG_IPV6_LEN	40u
#define OPAIRSHARE_AGG_MAX_MSDU	2304u
#define OPAIRSHARE_AGG_HARD_MAX	5u	/* subframes per A-MSDU, hard ceiling */
/* LLC + AWDL header + IPv6 + TCP with the 12-byte timestamp option */
#define OPAIRSHARE_AGG_PEEK		(OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN + OPAIRSHARE_AGG_IPV6_LEN + 32u)

struct opairshare_agg_meta {
	uint8_t  eligible;	/* unicast AWDL QoS data that may be A-MSDU'd */
	uint8_t  ack_pure;	/* plain TCP ACK: no payload, no SACK, flags == ACK */
	uint8_t  ra[6];
	uint8_t  ta[6];
	uint8_t  qos[2];
	uint16_t msdu_len;	/* bytes following the 26-byte QoS header */
	uint8_t  flow[36];	/* IPv6 src + dst + TCP src/dst port */
	uint32_t ack_seq;
};

/* Subframe padding: aligns (14 + msdu_len) to 4; written BETWEEN subframes only. */
static inline unsigned int opairshare_agg_pad(unsigned int msdu_len)
{
	return (4u - ((OPAIRSHARE_AGG_SUB_HDR + msdu_len) & 3u)) & 3u;
}

static inline uint32_t opairshare_agg_be32(const uint8_t *p)
{
	return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
	       ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

/* h:      the 26-byte 802.11 header exactly as queued
 * peek:   the first peek_len (<= OPAIRSHARE_AGG_PEEK) bytes of the MSDU
 * msdu_len: total MSDU length (bytes after the 26-byte header)
 */
static inline void opairshare_agg_parse(const uint8_t *h, const uint8_t *peek,
				  size_t peek_len, size_t msdu_len,
				  struct opairshare_agg_meta *m)
{
	static const uint8_t llc[OPAIRSHARE_AGG_LLC_LEN] =
		{0xaa, 0xaa, 0x03, 0x00, 0x17, 0xf2, 0x08, 0x00};
	const uint8_t *awdl, *ip, *tcp;
	unsigned int doff;

	memset(m, 0, sizeof(*m));
	/* QoS data, to-DS/from-DS clear, unprotected, no +HTC/order bit. */
	if (h[0] != 0x88 || h[1] != 0x00)
		return;
	if (h[4] & 1u)			/* group-addressed: never aggregated */
		return;
	if (msdu_len < OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN ||
	    msdu_len > OPAIRSHARE_AGG_MAX_MSDU)
		return;
	if (peek_len < OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN ||
	    memcmp(peek, llc, sizeof(llc)))
		return;
	memcpy(m->ra, h + 4, 6);
	memcpy(m->ta, h + 10, 6);
	m->qos[0] = h[24];
	m->qos[1] = h[25];
	m->msdu_len = (uint16_t)msdu_len;
	m->eligible = 1;

	/* Plain-TCP-ACK recognition (used only by the ACK filter). */
	if (peek_len < OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN + OPAIRSHARE_AGG_IPV6_LEN + 20u)
		return;
	awdl = peek + OPAIRSHARE_AGG_LLC_LEN;
	if (awdl[0] != 0x03 || awdl[1] != 0x04 || awdl[6] != 0x86 || awdl[7] != 0xdd)
		return;
	ip = awdl + OPAIRSHARE_AGG_AWDL_LEN;
	if ((ip[0] >> 4) != 6 || ip[6] != 6)	/* IPv6, next header TCP */
		return;
	tcp = ip + OPAIRSHARE_AGG_IPV6_LEN;
	doff = (unsigned int)(tcp[12] >> 4) * 4u;
	if (doff != 20u && doff != 32u)
		return;
	if (peek_len < OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN + OPAIRSHARE_AGG_IPV6_LEN + doff)
		return;
	/* Only NOP NOP TIMESTAMP: any SACK/other option keeps the ACK untouched. */
	if (doff == 32u && !(tcp[20] == 1 && tcp[21] == 1 && tcp[22] == 8 && tcp[23] == 10))
		return;
	if (tcp[13] != 0x10)			/* flags must be exactly ACK */
		return;
	if ((((unsigned int)ip[4] << 8) | ip[5]) != doff)	/* no TCP payload */
		return;
	if (msdu_len != OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN + OPAIRSHARE_AGG_IPV6_LEN + doff)
		return;
	memcpy(m->flow, ip + 8, 32);		/* src + dst address */
	memcpy(m->flow + 32, tcp, 4);		/* src + dst port */
	m->ack_seq = opairshare_agg_be32(tcp + 8);
	m->ack_pure = 1;
}

/* May `cand` follow the frames already gathered?  Returns the new body length
 * (everything after the 26-byte header) or 0 if not.
 *   n_have      subframes already in the aggregate (>= 1, the leader counts)
 *   body_have   body length so far (last subframe unpadded)
 *   last_len    MSDU length of the last gathered subframe
 * Order is preserved by the caller stopping at the first refusal.
 */
static inline uint32_t opairshare_agg_join(const struct opairshare_agg_meta *lead,
				     const struct opairshare_agg_meta *cand,
				     unsigned int n_have, uint32_t body_have,
				     unsigned int last_len, unsigned int max_n,
				     uint32_t max_mpdu)
{
	uint32_t body;

	if (!lead->eligible || !cand->eligible || n_have >= max_n ||
	    n_have >= OPAIRSHARE_AGG_HARD_MAX)
		return 0;
	if (memcmp(cand->ra, lead->ra, 6) || memcmp(cand->ta, lead->ta, 6) ||
	    cand->qos[0] != lead->qos[0] || cand->qos[1] != lead->qos[1])
		return 0;
	body = body_have + opairshare_agg_pad(last_len) + OPAIRSHARE_AGG_SUB_HDR + cand->msdu_len;
	if (OPAIRSHARE_AGG_QOS_HDR + body > max_mpdu)
		return 0;
	return body;
}

/* Write DA | SA | Length(big-endian) of one subframe.  14 bytes. */
static inline void opairshare_agg_put_subhdr(uint8_t *p, const uint8_t *da,
				       const uint8_t *sa, unsigned int msdu_len)
{
	memcpy(p, da, 6);
	memcpy(p + 6, sa, 6);
	p[12] = (uint8_t)(msdu_len >> 8);
	p[13] = (uint8_t)(msdu_len & 0xff);
}

/* Offset of subframe i's header inside the body, and the body length.
 * Returns 0 for an invalid request.
 */
static inline uint32_t opairshare_agg_layout(const uint16_t *msdu_len, unsigned int n,
				       uint32_t *off)
{
	uint32_t pos = 0;
	unsigned int i;

	if (!n || n > OPAIRSHARE_AGG_HARD_MAX)
		return 0;
	for (i = 0; i < n; i++) {
		if (!msdu_len[i] || msdu_len[i] > OPAIRSHARE_AGG_MAX_MSDU)
			return 0;
		off[i] = pos;
		pos += OPAIRSHARE_AGG_SUB_HDR + msdu_len[i];
		if (i + 1 < n)
			pos += opairshare_agg_pad(msdu_len[i]);
	}
	return pos;
}

/* A queued pure ACK `older` is redundant once `newer` of the same flow, same
 * link addresses and a strictly larger cumulative ACK number is queued behind
 * it.  Equal ACK numbers (duplicate ACKs, window updates) are never dropped.
 */
static inline int opairshare_ackf_redundant(const struct opairshare_agg_meta *older,
				      const struct opairshare_agg_meta *newer)
{
	return older->ack_pure && newer->ack_pure &&
	       !memcmp(older->flow, newer->flow, sizeof(older->flow)) &&
	       !memcmp(older->ra, newer->ra, 6) && !memcmp(older->ta, newer->ta, 6) &&
	       (int32_t)(newer->ack_seq - older->ack_seq) > 0;
}

#endif /* STAGE54_AGG_CORE_H */

/* ---- end stage54-agg-core.h ---- */

struct opairshare_bridge_item {
	struct list_head list;
	/* stage54: queued-frame classification (A-MSDU eligibility, plain ACK) */
	struct opairshare_agg_meta agg;
	struct sk_buff *skb;
	struct wlan_objmgr_vdev *vdev;
	uint16_t radiotap_len;
	uint16_t freq;
	bool sequence_valid;
	u16 sequence_control;
	u8 awdl_attempts;
	u64 trace_id, trace_epoch, trace_enqueued, trace_dequeued;
	u64 metric_enqueued, metric_dequeued, metric_entered;
	bool metric_data, metric_native;
	/* Resubmit state; unused unless passthru_awdl_retry. */
	qdf_nbuf_t fw_buf;
	u64 first_submit_ns;
	u8 retries;
};
static DEFINE_SPINLOCK(opairshare_bridge_lock);
static DEFINE_MUTEX(opairshare_bridge_lifecycle);
static LIST_HEAD(opairshare_bridge_queue);
static DECLARE_WAIT_QUEUE_HEAD(opairshare_bridge_wait);
static struct workqueue_struct *opairshare_bridge_wq;
static uint8_t opairshare_bridge_id = WLAN_INVALID_VDEV_ID;
static uint16_t opairshare_bridge_freq;
static bool opairshare_bridge_accepting;
static atomic64_t opairshare_bridge_pending = ATOMIC64_INIT(0);
static atomic64_t opairshare_bridge_accepted = ATOMIC64_INIT(0);
static atomic64_t opairshare_bridge_submitted = ATOMIC64_INIT(0);
static atomic64_t opairshare_bridge_completed = ATOMIC64_INIT(0);
static atomic64_t opairshare_bridge_dropped = ATOMIC64_INIT(0);
static atomic64_t opairshare_bridge_last_status = ATOMIC64_INIT(-1);
static void opairshare_bridge_worker(struct work_struct *work);
static DECLARE_WORK(opairshare_bridge_work, opairshare_bridge_worker);
static struct hrtimer opairshare_collect_timer;
static enum hrtimer_restart opairshare_collect_expire(struct hrtimer *timer)
{
    unsigned long flags;
    (void)timer;
    spin_lock_irqsave(&opairshare_bridge_lock, flags);
    if (opairshare_bridge_wq && opairshare_bridge_accepting)
        queue_work(opairshare_bridge_wq, &opairshare_bridge_work);
    spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
    return HRTIMER_NORESTART;
}


static int opairshare_bridge_count_get(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, 32, "%lld\n",
		(long long)atomic64_read((atomic64_t *)kp->arg));
}
static const struct kernel_param_ops opairshare_bridge_count_ops = { .get = opairshare_bridge_count_get };
module_param_cb(opairshare_bridge_pending, &opairshare_bridge_count_ops, &opairshare_bridge_pending, 0444);
module_param_cb(opairshare_bridge_accepted, &opairshare_bridge_count_ops, &opairshare_bridge_accepted, 0444);
module_param_cb(opairshare_bridge_submitted, &opairshare_bridge_count_ops, &opairshare_bridge_submitted, 0444);
module_param_cb(opairshare_bridge_completed, &opairshare_bridge_count_ops, &opairshare_bridge_completed, 0444);
module_param_cb(opairshare_bridge_dropped, &opairshare_bridge_count_ops, &opairshare_bridge_dropped, 0444);
module_param_cb(opairshare_bridge_last_status, &opairshare_bridge_count_ops, &opairshare_bridge_last_status, 0444);

/* Bounded driver-level resubmit of a failed AWDL TX. Off by default. */
static bool passthru_awdl_retry;
module_param(passthru_awdl_retry, bool, 0644);
MODULE_PARM_DESC(passthru_awdl_retry,
	"Resubmit a failed AWDL TX with the same 802.11 sequence instead of dropping it");
static unsigned int passthru_awdl_retry_max = 8;
module_param(passthru_awdl_retry_max, uint, 0644);
MODULE_PARM_DESC(passthru_awdl_retry_max, "Max driver-level resubmits per frame");
static unsigned int passthru_awdl_retry_budget_ms = 500;
module_param(passthru_awdl_retry_budget_ms, uint, 0644);
MODULE_PARM_DESC(passthru_awdl_retry_budget_ms,
	"Wall-clock budget per frame measured from its first submit");
/* Firmware WMI management-TX completion status (WMI_MGMT_TX_COMP_STATUS_TYPE).
 * 0 = COMPLETE_OK, 1 = DISCARD (permanent failure), 2 = INSPECT,
 * 3 = COMPLETE_NO_ACK (retries exhausted, no 802.11 ACK). Only NO_ACK is a
 * transient air failure; everything else must be dropped, never resubmitted.
 */
#define OPAIRSHARE_MGMT_TX_COMP_OK 0
#define OPAIRSHARE_MGMT_TX_COMP_NO_ACK 3
/* Queue occupancy thresholds. Previously hardcoded 32/16; exposed so the
 * host-side feeding limit can be measured instead of assumed.
 */
static unsigned int passthru_awdl_capacity = 32;
module_param(passthru_awdl_capacity, uint, 0644);
MODULE_PARM_DESC(passthru_awdl_capacity, "Accepted-but-unfinished frame cap before backpressure stops the queue");
static unsigned int passthru_awdl_lowmark = 16;
module_param(passthru_awdl_lowmark, uint, 0644);
MODULE_PARM_DESC(passthru_awdl_lowmark, "Pending level at or below which a paused queue is woken");
static atomic64_t opairshare_bridge_retried = ATOMIC64_INIT(0);
static atomic64_t opairshare_bridge_retry_gaveup = ATOMIC64_INIT(0);
module_param_cb(opairshare_bridge_retried, &opairshare_bridge_count_ops, &opairshare_bridge_retried, 0444);
module_param_cb(opairshare_bridge_retry_gaveup, &opairshare_bridge_count_ops, &opairshare_bridge_retry_gaveup, 0444);

/* Diagnostic: rewrite the AWDL data frame QoS control ACK policy.
 * 0 = leave the frame untouched (default), 1 = No Ack, 3 = Block Ack.
 * Rationale: a failed frame costs ~38 ms (4 attempts) while a successful one
 * costs ~7 ms, and the air time at 54 Mbps is only ~0.3 ms, so most of the
 * per-frame firmware cost is not air. If that cost is ACK waiting, removing
 * the ACK requirement should raise the firmware's service rate.
 */
/* Diagnostic: present each AWDL data payload as a single-subframe A-MSDU.
 * The measured ceiling on this path is ~210 FRAMES/second regardless of frame
 * size (a 114-byte ACK phase and a 1492-byte data phase both run at the same
 * frame rate), so bytes-per-frame is the only remaining throughput lever. This
 * probe first tests whether the peer accepts A-MSDU framing at all.
 */
/* Diagnostic: rewrite the AWDL data frame's QoS TID.
 * The phone's WMI management path is measured at ~210 frames/second. If the
 * firmware's management queue is per-TID, interleaving TIDs would multiply the
 * rate; if it is global, it would not. -1 = leave the frame untouched,
 * 0..7 = force that TID, 100 = round-robin across all eight.
 */
static int passthru_awdl_tid = -1;
module_param(passthru_awdl_tid, int, 0644);
MODULE_PARM_DESC(passthru_awdl_tid,
	"Diagnostic: force/round-robin the AWDL data QoS TID (-1=leave, 0-7, 100=rr)");
static atomic_t opairshare_awdl_tid_rr = ATOMIC_INIT(0);
/* stage54 A-MSDU control.
 *   0  off: byte-identical to the stage48 candidate.
 *   1  wrap each frame whose result still fits passthru_awdl_amsdu_bytes as a
 *      one-subframe A-MSDU.  Pure acceptance probe, never faster by itself.
 *   2  merge frames that are ALREADY queued for the same peer/TID into one
 *      multi-subframe A-MSDU (never waits for more, so it adds no latency).
 */
static unsigned int passthru_awdl_amsdu;
static unsigned int passthru_awdl_amsdu_max = 2;
static unsigned int passthru_awdl_amsdu_bytes = 3200;
static bool passthru_awdl_ackfilter;
static int opairshare_amsdu_mode_set(const char *value, const struct kernel_param *kp)
{
	unsigned int v;
	int rc = kstrtouint(value, 0, &v);

	if (rc)
		return rc;
	if (v > 2)
		return -EINVAL;
	WRITE_ONCE(*(unsigned int *)kp->arg, v);
	return 0;
}
static int opairshare_amsdu_max_set(const char *value, const struct kernel_param *kp)
{
	unsigned int v;
	int rc = kstrtouint(value, 0, &v);

	if (rc)
		return rc;
	if (v < 2 || v > 5)
		return -EINVAL;
	WRITE_ONCE(*(unsigned int *)kp->arg, v);
	return 0;
}
static int opairshare_amsdu_bytes_set(const char *value, const struct kernel_param *kp)
{
	unsigned int v;
	int rc = kstrtouint(value, 0, &v);

	if (rc)
		return rc;
	if (v < 128 || v > 7935)
		return -EINVAL;
	WRITE_ONCE(*(unsigned int *)kp->arg, v);
	return 0;
}
static const struct kernel_param_ops opairshare_amsdu_mode_ops = {
	.set = opairshare_amsdu_mode_set, .get = param_get_uint,
};
static const struct kernel_param_ops opairshare_amsdu_max_ops = {
	.set = opairshare_amsdu_max_set, .get = param_get_uint,
};
static const struct kernel_param_ops opairshare_amsdu_bytes_ops = {
	.set = opairshare_amsdu_bytes_set, .get = param_get_uint,
};
module_param_cb(passthru_awdl_amsdu, &opairshare_amsdu_mode_ops, &passthru_awdl_amsdu, 0644);
MODULE_PARM_DESC(passthru_awdl_amsdu,
	"A-MSDU: 0=off, 1=wrap small frames as 1-subframe A-MSDU (probe), 2=merge already-queued frames");
module_param_cb(passthru_awdl_amsdu_max, &opairshare_amsdu_max_ops, &passthru_awdl_amsdu_max, 0644);
MODULE_PARM_DESC(passthru_awdl_amsdu_max, "Max MSDUs per A-MSDU in mode 2 (2..5)");
module_param_cb(passthru_awdl_amsdu_bytes, &opairshare_amsdu_bytes_ops, &passthru_awdl_amsdu_bytes, 0644);
MODULE_PARM_DESC(passthru_awdl_amsdu_bytes, "Max MPDU bytes (802.11 header + subframes) of an A-MSDU (128..7935)");
module_param(passthru_awdl_ackfilter, bool, 0644);
MODULE_PARM_DESC(passthru_awdl_ackfilter,
	"Drop a queued plain TCP ACK once a newer ACK of the same flow is queued behind it");
/* PHY for AWDL data frames.  0 = legacy OFDM at passthru_awdl_rate_mbps (unchanged).
 * 2 = HT, 3 = VHT: the WMI TX params carry MASKS (preamble, MCS, NSS) the firmware
 * may pick from, so this only requests a rate class; it does not prove it was used.
 * Needed because a non-HT PPDU may not carry an MPDU above 2346 octets.
 */
static unsigned int passthru_awdl_phy;
static unsigned int passthru_awdl_mcs = 7;
static int opairshare_phy_set(const char *value, const struct kernel_param *kp)
{
	unsigned int v;
	int rc = kstrtouint(value, 0, &v);

	if (rc)
		return rc;
	if (v != 0 && v != 2 && v != 3)
		return -EINVAL;
	WRITE_ONCE(*(unsigned int *)kp->arg, v);
	return 0;
}
static int opairshare_mcs_set(const char *value, const struct kernel_param *kp)
{
	unsigned int v;
	int rc = kstrtouint(value, 0, &v);

	if (rc)
		return rc;
	if (v > 9)
		return -EINVAL;
	WRITE_ONCE(*(unsigned int *)kp->arg, v);
	return 0;
}
static const struct kernel_param_ops opairshare_phy_ops = {
	.set = opairshare_phy_set, .get = param_get_uint,
};
static const struct kernel_param_ops opairshare_mcs_ops = {
	.set = opairshare_mcs_set, .get = param_get_uint,
};
module_param_cb(passthru_awdl_phy, &opairshare_phy_ops, &passthru_awdl_phy, 0644);
MODULE_PARM_DESC(passthru_awdl_phy, "AWDL data PHY class: 0=legacy OFDM (default), 2=HT, 3=VHT");
module_param_cb(passthru_awdl_mcs, &opairshare_mcs_ops, &passthru_awdl_mcs, 0644);
MODULE_PARM_DESC(passthru_awdl_mcs, "MCS index requested when passthru_awdl_phy is 2 or 3 (0..9)");

/* A-MSDU geometry, 802.11-2016 9.3.2.2.2 (802.11-2012 8.3.2.2 for the byte
 * order). A subframe is DA(6) | SA(6) | Length(2) | MSDU | pad(0-3); the
 * Length carries the MSDU length and is ordered like an IEEE 802.3 length
 * field, i.e. most significant octet first; padding 4-aligns the whole
 * subframe and exists only between subframes, never after the last one.
 */
#define OPAIRSHARE_AMSDU_SUBFRAME_HDR	14
#define OPAIRSHARE_AMSDU_MAX_PAD	3
#define OPAIRSHARE_AMSDU_MAX_MSDU	2304
/* Space the wrapper can add for n subframes: one header each, plus the pad
 * that separates consecutive subframes. n = 1 costs 14; the previous "+16"
 * was one octet short of the 14 + 3 the old code could actually write.
 */
#define OPAIRSHARE_AMSDU_OVERHEAD(n)	((n) * OPAIRSHARE_AMSDU_SUBFRAME_HDR + \
				 ((n) - 1) * OPAIRSHARE_AMSDU_MAX_PAD)
#define OPAIRSHARE_AMSDU_MAX_SUBFRAMES	1
static unsigned int passthru_awdl_ack_policy;
module_param(passthru_awdl_ack_policy, uint, 0644);
MODULE_PARM_DESC(passthru_awdl_ack_policy,
	"Diagnostic: rewrite AWDL data QoS ACK policy (0=leave, 1=no-ack, 3=block-ack)");

#include "wma_opairshare_metrics.h"
#include "wma_opairshare_awdl_trace.h"
#include "wma_opairshare_awdl_target.h"
static bool opairshare_rx_filter_armed;
static void opairshare_flow_maybe_wake_locked(void);
#include "wma_opairshare_channel_state.h"
#include "wma_opairshare_flow_control.h"
static bool passthru_awdl_rx_auto;
module_param(passthru_awdl_rx_auto, bool, 0444);
MODULE_PARM_DESC(passthru_awdl_rx_auto, "Automatic RX match; fixed149; rearm requires confirmed clean lifecycle");
static bool passthru_awdl_rx_rearm;
module_param(passthru_awdl_rx_rearm, bool, 0444);
MODULE_PARM_DESC(passthru_awdl_rx_rearm, "Reuse normal RX sessions after real delete and host teardown; uncertain replies quarantine");
#include "wma_opairshare_awdl_rx_mac_filter.h"

/* Fixed149 profile: this does not establish general hopping support. */
void opairshare_pas_rx_order_end(void);
int opairshare_pas_mgmt_stop(uint8_t id);
static unsigned int opairshare_rx_fixed_bandwidth;
static bool opairshare_rx_fixed_bandwidth_valid;

/* 1=legacy setup untouched; 0=bridge drained/cleared; negative=retain resources. */
int opairshare_pas_rx_setup_abort(u8 id)
{
	if (!passthru_awdl_rx_auto)
		return 1;
	return opairshare_pas_mgmt_stop(id);
}

int opairshare_pas_rx_fixed_channel(u8 id, u16 freq, unsigned int bandwidth)
{
	unsigned long flags;
	int rc = 0;
	if (!passthru_awdl_rx_auto)
		return 0;
	if (freq != 5745) {
		pr_info("OPAIRSHARERXAUTO: unsupported request freq=%u bandwidth_enum=%u\n", freq, bandwidth);
		return -EOPNOTSUPP;
	}
	mutex_lock(&opairshare_bridge_lifecycle);
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (opairshare_bridge_wq && opairshare_bridge_id != id)
		rc = -EINVAL;
	else if (opairshare_channel_fault || opairshare_channel_busy || READ_ONCE(opairshare_target_fault))
		rc = -EIO;
	else if (opairshare_rx_filter_armed) {
		/* No radio operation is needed for this exact already-active channel.
		 * Never use this shortcut for another frequency/width or bad gate.
		 */
		if (opairshare_rx_filter_active && !opairshare_rx_filter_fault &&
		    !opairshare_rx_filter_pending && opairshare_rx_filter_id == id &&
		    opairshare_rx_filter_freq == freq && opairshare_bridge_freq == freq &&
		    opairshare_bridge_accepting)
			rc = opairshare_rx_fixed_bandwidth_valid &&
				bandwidth == opairshare_rx_fixed_bandwidth ? 1 : -EOPNOTSUPP;
		else
			rc = -EIO;
	}
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	mutex_unlock(&opairshare_bridge_lifecycle);
	return rc;
}

int opairshare_pas_rx_auto_start(u8 id, u16 freq, unsigned int bandwidth)
{
	unsigned long flags;
	int rc;
	if (!passthru_awdl_rx_auto)
		return 0;
	rc = opairshare_pas_rx_fixed_channel(id, freq, bandwidth);
	if (rc == 1)
		return 0;
	if (!rc)
		rc = opairshare_rx_filter_set("1", NULL);
	if (!rc) {
		mutex_lock(&opairshare_bridge_lifecycle);
		spin_lock_irqsave(&opairshare_bridge_lock, flags);
		opairshare_rx_fixed_bandwidth = bandwidth;
		opairshare_rx_fixed_bandwidth_valid = true;
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		mutex_unlock(&opairshare_bridge_lifecycle);
	}
	if (rc) {
		mutex_lock(&opairshare_bridge_lifecycle);
		spin_lock_irqsave(&opairshare_bridge_lock, flags);
		opairshare_bridge_accepting = false;
		opairshare_channel_fault = true;
		opairshare_channel_error = rc;
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		mutex_unlock(&opairshare_bridge_lifecycle);
	}
	pr_info("OPAIRSHARERXAUTO: start vdev=%u freq=%u bandwidth_enum=%u rc=%d active=%u used=%u\n",
		id, freq, bandwidth, rc, opairshare_rx_filter_active, opairshare_rx_filter_used);
	return rc;
}


static void opairshare_bridge_done(void)
{
	unsigned long flags;
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	atomic64_dec(&opairshare_bridge_pending);
	opairshare_flow_maybe_wake_locked();
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	wake_up_all(&opairshare_bridge_wait);
	/* Every accepted packet pins this module until cancelled/completed. */
	module_put(THIS_MODULE);
}

/* Driver-level resubmit. The completion callback receives only buf, so
 * ownership is registered before the submit and resolved before the buffer is
 * freed. A resubmit reuses item->sequence_control, so the frame leaves with
 * the same 802.11 sequence and the peer's duplicate detection still applies.
 */
#define OPAIRSHARE_BRIDGE_INFLIGHT_SLOTS 64
static struct opairshare_bridge_item *opairshare_bridge_inflight[OPAIRSHARE_BRIDGE_INFLIGHT_SLOTS];

static bool opairshare_bridge_inflight_add_locked(struct opairshare_bridge_item *item, qdf_nbuf_t buf)
{
	unsigned int i;
	for (i = 0; i < OPAIRSHARE_BRIDGE_INFLIGHT_SLOTS; i++) {
		if (!opairshare_bridge_inflight[i]) {
			opairshare_bridge_inflight[i] = item;
			item->fw_buf = buf;
			return true;
		}
	}
	return false;
}

static bool opairshare_bridge_inflight_drop_locked(struct opairshare_bridge_item *item)
{
	unsigned int i;
	for (i = 0; i < OPAIRSHARE_BRIDGE_INFLIGHT_SLOTS; i++) {
		if (opairshare_bridge_inflight[i] == item) {
			opairshare_bridge_inflight[i] = NULL;
			item->fw_buf = NULL;
			return true;
		}
	}
	return false;
}

static struct opairshare_bridge_item *opairshare_bridge_inflight_take_locked(qdf_nbuf_t buf)
{
	unsigned int i;
	for (i = 0; i < OPAIRSHARE_BRIDGE_INFLIGHT_SLOTS; i++) {
		struct opairshare_bridge_item *item = opairshare_bridge_inflight[i];
		if (item && item->fw_buf == buf) {
			opairshare_bridge_inflight[i] = NULL;
			item->fw_buf = NULL;
			return item;
		}
	}
	return NULL;
}

/* Exactly one terminal per item: releases the skb, the vdev reference and the
 * module reference, then settles pending. Callers must not hold the lock.
 */
static void opairshare_bridge_finish(struct opairshare_bridge_item *item, bool consumed)
{
	if (consumed) {
		consume_skb(item->skb);
	} else {
		atomic64_inc(&opairshare_bridge_dropped);
		dev_kfree_skb_any(item->skb);
	}
	wlan_objmgr_vdev_release_ref(item->vdev, WLAN_MGMT_NB_ID);
	kfree(item);
	opairshare_bridge_done();
}

static bool opairshare_bridge_retry_admit(struct opairshare_bridge_item *item)
{
	if (!READ_ONCE(passthru_awdl_retry))
		return false;
	/* Reusing the sequence is what makes this a link-layer retransmission
	 * rather than a second frame. Without host sequence ownership a resubmit
	 * would be delivered as new data, so refuse instead of guessing.
	 */
	if (!READ_ONCE(passthru_awdl_host_seq))
		return false;
	if (item->retries >= READ_ONCE(passthru_awdl_retry_max))
		return false;
	if (ktime_get_ns() - item->first_submit_ns >
	    (u64)READ_ONCE(passthru_awdl_retry_budget_ms) * NSEC_PER_MSEC)
		return false;
	return true;
}

/* Put the frame back at the head of the queue. No timer: a failed frame's
 * firmware round trip already spaces attempts by tens of milliseconds, which
 * is the scale of the observed peer absence, and the item stays on the normal
 * queue so Stop still drains it.
 */
static void opairshare_bridge_requeue(struct opairshare_bridge_item *item)
{
	unsigned long flags;
	bool ok;
	/* Re-enter the queue accounting so the next dequeue balances and a later
	 * drop still settles processing. Telemetry only.
	 */
	opairshare_metric_accept(item, item->metric_data);
	item->metric_native = false;
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	ok = opairshare_bridge_accepting && opairshare_bridge_wq;
	if (ok)
		list_add(&item->list, &opairshare_bridge_queue);
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	if (ok)
		queue_work(opairshare_bridge_wq, &opairshare_bridge_work);
	else
		opairshare_bridge_finish(item, false);
}

static QDF_STATUS opairshare_bridge_complete(void *context, qdf_nbuf_t buf,
				       uint32_t status, void *params)
{
	struct opairshare_bridge_item *item;
	unsigned long flags;

	opairshare_metric_remove(buf, false, status);
	opairshare_trace_remove(buf, false, status);
	atomic64_set(&opairshare_bridge_last_status, status);
	atomic64_inc(&opairshare_bridge_completed);
	pr_info_ratelimited("OPAIRSHAREBRIDGECOMP: status=%u len=%lu\n", status, qdf_nbuf_len(buf));
	/* Resolve ownership before freeing buf: the address may be reused. */
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	item = opairshare_bridge_inflight_take_locked(buf);
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	cds_packet_free(buf);
	if (!item) {
		/* Retry off, or a frame that was never tracked. */
		opairshare_bridge_done();
		return QDF_STATUS_SUCCESS;
	}
	if (status == OPAIRSHARE_MGMT_TX_COMP_NO_ACK && opairshare_bridge_retry_admit(item)) {
		item->retries++;
		atomic64_inc(&opairshare_bridge_retried);
		pr_info_ratelimited("OPAIRSHAREBRIDGERETRY: retry=%u status=%u len=%u\n",
				    item->retries, status, item->skb->len);
		opairshare_bridge_requeue(item);
		return QDF_STATUS_SUCCESS;
	}
	if (status != OPAIRSHARE_MGMT_TX_COMP_OK)
		atomic64_inc(&opairshare_bridge_retry_gaveup);
	opairshare_bridge_finish(item, status == OPAIRSHARE_MGMT_TX_COMP_OK);
	return QDF_STATUS_SUCCESS;
}

/* ---- stage54: A-MSDU coalescing and redundant-ACK filter ---- */

/* Stage62 experiments: default preserves existing non-waiting, 1SS TX. */
static unsigned int passthru_awdl_collect_us;
module_param(passthru_awdl_collect_us, uint, 0644);
static unsigned int passthru_awdl_nss = 1;
module_param(passthru_awdl_nss, uint, 0644);
static atomic64_t opairshare_bulk_packs[6];
static atomic64_t opairshare_collect_deferrals = ATOMIC64_INIT(0);
static int opairshare_txpack_get(char *out, const struct kernel_param *kp)
{
    (void)kp;
    return scnprintf(out, 256,
        "p1=%lld p2=%lld p3=%lld p4=%lld p5=%lld deferrals=%lld collect_us=%u nss=%u\n",
        (long long)atomic64_read(&opairshare_bulk_packs[1]),
        (long long)atomic64_read(&opairshare_bulk_packs[2]),
        (long long)atomic64_read(&opairshare_bulk_packs[3]),
        (long long)atomic64_read(&opairshare_bulk_packs[4]),
        (long long)atomic64_read(&opairshare_bulk_packs[5]),
        (long long)atomic64_read(&opairshare_collect_deferrals),
        READ_ONCE(passthru_awdl_collect_us), READ_ONCE(passthru_awdl_nss));
}
static const struct kernel_param_ops opairshare_txpack_ops = { .get = opairshare_txpack_get };
module_param_cb(opairshare_tx_pack_stats, &opairshare_txpack_ops, NULL, 0444);

static atomic64_t opairshare_agg_frames = ATOMIC64_INIT(0);
static atomic64_t opairshare_agg_msdus = ATOMIC64_INIT(0);
static atomic64_t opairshare_agg_bytes = ATOMIC64_INIT(0);
static atomic64_t opairshare_agg_wrapped = ATOMIC64_INIT(0);
static atomic64_t opairshare_agg_followers_dropped = ATOMIC64_INIT(0);
static atomic64_t opairshare_ackf_dropped = ATOMIC64_INIT(0);

static int opairshare_agg_stats_get(char *out, const struct kernel_param *kp)
{
	(void)kp;
	return scnprintf(out, 256,
		"aggregates=%lld msdus=%lld bytes=%lld wrapped=%lld followers_dropped=%lld ackf_dropped=%lld mode=%u max=%u cap=%u ackfilter=%u phy=%u mcs=%u\n",
		(long long)atomic64_read(&opairshare_agg_frames), (long long)atomic64_read(&opairshare_agg_msdus),
		(long long)atomic64_read(&opairshare_agg_bytes), (long long)atomic64_read(&opairshare_agg_wrapped),
		(long long)atomic64_read(&opairshare_agg_followers_dropped),
		(long long)atomic64_read(&opairshare_ackf_dropped), READ_ONCE(passthru_awdl_amsdu),
		READ_ONCE(passthru_awdl_amsdu_max), READ_ONCE(passthru_awdl_amsdu_bytes),
		(unsigned int)READ_ONCE(passthru_awdl_ackfilter), READ_ONCE(passthru_awdl_phy),
		READ_ONCE(passthru_awdl_mcs));
}
static const struct kernel_param_ops opairshare_agg_stats_ops = { .get = opairshare_agg_stats_get };
module_param_cb(opairshare_agg_stats, &opairshare_agg_stats_ops, NULL, 0444);

/* A follower or a filtered ACK never reaches the metric "register" step, so its
 * "processing" token (taken by opairshare_metric_dequeue) is returned here.  Unlike
 * opairshare_metric_drop() this does not count a worker drop.
 */
static void opairshare_metric_coalesced(struct opairshare_bridge_item *item)
{
	unsigned long flags;

	(void)item;
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	opairshare_metric_dec(&opairshare_metric_processing);
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

/* Classify a frame at enqueue (skb still holds radiotap + 802.11 + MSDU). */
static void opairshare_agg_classify(struct opairshare_agg_meta *m, const struct sk_buff *skb,
							  uint16_t rtlen, const uint8_t *hdr26, unsigned int len)
{
	uint8_t peek[OPAIRSHARE_AGG_PEEK];
	unsigned int avail = len - OPAIRSHARE_AGG_QOS_HDR;
	unsigned int npk = min_t(unsigned int, avail, sizeof(peek));

	if (len < OPAIRSHARE_AGG_QOS_HDR ||
		skb_copy_bits(skb, rtlen + OPAIRSHARE_AGG_QOS_HDR, peek, npk)) {
		memset(m, 0, sizeof(*m));
		return;
	}
	opairshare_agg_parse(hdr26, peek, npk, avail, m);
}

/* Detach up to OPAIRSHARE_AGG_HARD_MAX-1 queued followers behind `lead`.  Stops at
 * the first frame that may not join so frame order to a peer is never changed.
 * Takes opairshare_bridge_lock; the caller holds no lock.
 */
static unsigned int opairshare_agg_gather(struct opairshare_bridge_item *lead,
									struct opairshare_bridge_item **fol)
{
	struct opairshare_bridge_item *it, *tmp;
	unsigned int max_n = min_t(unsigned int, READ_ONCE(passthru_awdl_amsdu_max),
							   OPAIRSHARE_AGG_HARD_MAX);
	uint32_t max_mpdu = READ_ONCE(passthru_awdl_amsdu_bytes);
	uint32_t body = OPAIRSHARE_AGG_SUB_HDR + lead->agg.msdu_len, nb;
	unsigned int n = 1, last = lead->agg.msdu_len;
	unsigned long flags;

	if (OPAIRSHARE_AGG_QOS_HDR + body > max_mpdu)
		return 0;
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	list_for_each_entry_safe(it, tmp, &opairshare_bridge_queue, list) {
		if (it->trace_id || it->vdev != lead->vdev || it->freq != lead->freq ||
			it->awdl_attempts != lead->awdl_attempts)
			break;
		nb = opairshare_agg_join(&lead->agg, &it->agg, n, body, last, max_n, max_mpdu);
		if (!nb)
			break;
		list_del(&it->list);
		opairshare_metric_dequeue(it);
		fol[n - 1] = it;
		body = nb;
		last = it->agg.msdu_len;
		n++;
	}
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	return n - 1;
}

/* MPDU bytes (802.11 header included) of lead + nfol followers, 0 if invalid. */
static unsigned int opairshare_agg_total(struct opairshare_bridge_item *lead,
								   struct opairshare_bridge_item **fol, unsigned int nfol)
{
	uint16_t lens[OPAIRSHARE_AGG_HARD_MAX];
	uint32_t off[OPAIRSHARE_AGG_HARD_MAX], body;
	unsigned int i;

	lens[0] = lead->agg.msdu_len;
	for (i = 0; i < nfol; i++)
		lens[i + 1] = fol[i]->agg.msdu_len;
	body = opairshare_agg_layout(lens, nfol + 1, off);
	return body ? OPAIRSHARE_AGG_QOS_HDR + body : 0;
}

/* Build the aggregate in `data` (already sized by opairshare_agg_total).  0 = ok. */
static int opairshare_agg_build(uint8_t *data, struct opairshare_bridge_item *lead,
						  struct opairshare_bridge_item **fol, unsigned int nfol)
{
	struct opairshare_bridge_item *src[OPAIRSHARE_AGG_HARD_MAX];
	uint16_t lens[OPAIRSHARE_AGG_HARD_MAX];
	uint32_t off[OPAIRSHARE_AGG_HARD_MAX], body;
	unsigned int n = nfol + 1, i, j;

	src[0] = lead;
	lens[0] = lead->agg.msdu_len;
	for (i = 0; i < nfol; i++) {
		src[i + 1] = fol[i];
		lens[i + 1] = fol[i]->agg.msdu_len;
	}
	body = opairshare_agg_layout(lens, n, off);
	if (!body)
		return -EINVAL;
	if (skb_copy_bits(lead->skb, lead->radiotap_len, data, OPAIRSHARE_AGG_QOS_HDR))
		return -EFAULT;
	for (i = 0; i < n; i++) {
		uint8_t *p = data + OPAIRSHARE_AGG_QOS_HDR + off[i];
		const struct opairshare_agg_meta *m = &src[i]->agg;

		opairshare_agg_put_subhdr(p, m->ra, m->ta, m->msdu_len);
		if (skb_copy_bits(src[i]->skb, src[i]->radiotap_len + OPAIRSHARE_AGG_QOS_HDR,
						  p + OPAIRSHARE_AGG_SUB_HDR, m->msdu_len))
			return -EFAULT;
		if (i + 1 < n)
			for (j = 0; j < opairshare_agg_pad(m->msdu_len); j++)
				p[OPAIRSHARE_AGG_SUB_HDR + m->msdu_len + j] = 0;
	}
	data[24] |= 0x80;           /* QoS Control: A-MSDU Present */
	return 0;
}

/* Terminal for the followers: their bytes travel (or died) with the leader. */
static void opairshare_agg_settle(struct opairshare_bridge_item **fol, unsigned int n, bool sent)
{
	unsigned int i;

	for (i = 0; i < n; i++) {
		struct opairshare_bridge_item *f = fol[i];

		opairshare_metric_coalesced(f);
		if (sent) {
			consume_skb(f->skb);
		} else {
			atomic64_inc(&opairshare_bridge_dropped);
			atomic64_inc(&opairshare_agg_followers_dropped);
			dev_kfree_skb_any(f->skb);
		}
		wlan_objmgr_vdev_release_ref(f->vdev, WLAN_MGMT_NB_ID);
		kfree(f);
		opairshare_bridge_done();
	}
}

/* Remove, from the queue, the newest queued plain ACK that `nm` supersedes. */
static struct opairshare_bridge_item *opairshare_ackf_detach(struct wlan_objmgr_vdev *vdev,
												 const struct opairshare_agg_meta *nm)
{
	struct opairshare_bridge_item *it, *found = NULL;
	unsigned int scanned = 0;
	unsigned long flags;

	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (opairshare_bridge_accepting && opairshare_bridge_wq && vdev &&
		opairshare_bridge_id == wlan_vdev_get_id(vdev)) {
		list_for_each_entry_reverse(it, &opairshare_bridge_queue, list) {
			if (++scanned > 24)
				break;
			if (it->vdev == vdev && !it->trace_id &&
				opairshare_ackf_redundant(&it->agg, nm)) {
				found = it;
				break;
			}
		}
		if (found) {
			list_del(&found->list);
			opairshare_metric_dequeue(found);
		}
	}
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	return found;
}

static void opairshare_ackf_settle(struct opairshare_bridge_item *v)
{
	opairshare_metric_coalesced(v);
	atomic64_inc(&opairshare_ackf_dropped);
	consume_skb(v->skb);
	wlan_objmgr_vdev_release_ref(v->vdev, WLAN_MGMT_NB_ID);
	kfree(v);
	opairshare_bridge_done();
}

/* Shared by the kernel experiment and host sanitizer tests. */
static inline unsigned long long
opairshare_collect_remaining(unsigned long long age_ns, unsigned int wait_us,
                       unsigned int have, unsigned int goal, int bulk,
                       int blocker, int accepting)
{
    unsigned long long limit;
    if (!accepting || !bulk || blocker || !wait_us || have >= goal)
        return 0;
    if (wait_us > 2000)
        wait_us = 2000;
    limit = (unsigned long long)wait_us * 1000;
    return age_ns >= limit ? 0 : limit - age_ns;
}

/* Called with bridge_lock held. The first packet's enqueue timestamp fixes
 * the deadline: incoming packets never extend it. No sleeping worker. */
static u64 opairshare_collect_delay_locked(struct opairshare_bridge_item *lead)
{
    struct opairshare_bridge_item *it;
    unsigned int have = 1, last = lead->agg.msdu_len;
    unsigned int goal = min_t(unsigned int, READ_ONCE(passthru_awdl_amsdu_max), OPAIRSHARE_AGG_HARD_MAX);
    u32 body = OPAIRSHARE_AGG_SUB_HDR + last, next;
    bool blocker = false;
    if (!READ_ONCE(passthru_awdl_collect_us) ||
        READ_ONCE(passthru_awdl_amsdu) != 2 || lead->trace_id ||
        READ_ONCE(passthru_awdl_retry) || !lead->agg.eligible || last < 1000)
        return 0;
    list_for_each_entry(it, &opairshare_bridge_queue, list) {
        if (it == lead)
            continue;
        if (it->trace_id || it->vdev != lead->vdev || it->freq != lead->freq ||
            it->awdl_attempts != lead->awdl_attempts) {
            blocker = true;
            break;
        }
        next = opairshare_agg_join(&lead->agg, &it->agg, have, body, last, goal,
                             READ_ONCE(passthru_awdl_amsdu_bytes));
        if (!next) {
            blocker = true;
            break;
        }
        body = next;
        last = it->agg.msdu_len;
        if (++have >= goal)
            break;
    }
    return opairshare_collect_remaining(ktime_get_ns() - lead->metric_enqueued,
        READ_ONCE(passthru_awdl_collect_us), have, goal, 1, blocker,
        opairshare_bridge_accepting);
}

static void opairshare_bridge_worker(struct work_struct *work)
{
	struct opairshare_bridge_item *item;
	struct wlan_objmgr_peer *peer;
	struct wmi_mgmt_params mgmt;
	tp_wma_handle wma;
	qdf_nbuf_t buf;
	uint8_t *data;
	unsigned long flags;
	unsigned int len;
	uint8_t id;
	QDF_STATUS status;
	bool accepting, pack_bulk, retry_tracked = false;
	u64 collect_delay;
	struct opairshare_bridge_item *fol[OPAIRSHARE_AGG_HARD_MAX];
	unsigned int nfol = 0;

	/* No RTNL or osif transaction lock: stop drains us while holding them. */
	for (;;) {
		spin_lock_irqsave(&opairshare_bridge_lock, flags);
		if (list_empty(&opairshare_bridge_queue)) {
			spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
			return;
		}
		item = list_first_entry(&opairshare_bridge_queue, struct opairshare_bridge_item, list);
        collect_delay = opairshare_collect_delay_locked(item);
        if (collect_delay) {
            atomic64_inc(&opairshare_collect_deferrals);
            hrtimer_start(&opairshare_collect_timer, ns_to_ktime(collect_delay), HRTIMER_MODE_REL);
            spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
            return;
        }
        pack_bulk = item->agg.eligible && item->agg.msdu_len >= 1000;
		list_del(&item->list);
		opairshare_metric_dequeue(item);
		retry_tracked = false;
		accepting = opairshare_bridge_accepting;
		if (item->trace_id) item->trace_dequeued = ktime_get_ns();
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		wma = cds_get_context(QDF_MODULE_ID_WMA);
		id = wlan_vdev_get_id(item->vdev);
		if (!accepting || !wma || id >= wma->max_bssid ||
		    wma->interfaces[id].vdev != item->vdev ||
		    wma->interfaces[id].type != WMI_VDEV_TYPE_NDI ||
		    cds_is_driver_recovering() ||
		    !wmi_service_enabled(wma->wmi_handle, wmi_service_mgmt_tx_wmi))
			goto drop;
		/* stage54: in mode 2 the head frame carries the frames already queued
		 * behind it (same peer/TID) as further A-MSDU subframes.
		 */
		nfol = 0;
		if (READ_ONCE(passthru_awdl_amsdu) == 2 && item->agg.eligible &&
			!item->trace_id && !READ_ONCE(passthru_awdl_retry))
			nfol = opairshare_agg_gather(item, fol);
		len = item->skb->len - item->radiotap_len;
		if (nfol) {
			len = opairshare_agg_total(item, fol, nfol);
			if (!len)
				goto drop;
		}
		status = cds_packet_alloc(len + OPAIRSHARE_AMSDU_OVERHEAD(OPAIRSHARE_AMSDU_MAX_SUBFRAMES),
			(void **)&data, (void **)&buf);
		if (QDF_IS_STATUS_ERROR(status))
			goto drop;
		if (nfol ? opairshare_agg_build(data, item, fol, nfol) :
		    skb_copy_bits(item->skb, item->radiotap_len, data, len)) {
			cds_packet_free(buf);
			goto drop;
		}
		/* Assignment is snapshotted at enqueue. A later parameter write
		 * cannot alter ownership of an already accepted item. Only the
		 * copied MAC header changes; native AWDL/IPv6/TCP bytes stay intact.
		 */
		if (item->sequence_valid)
			opairshare_awdl_sequence_apply(data, item->sequence_control);
		/* Diagnostic ACK-policy rewrite; 0 leaves the frame byte-identical. */
		if (data[0] == 0x88 && data[1] == 0 &&
		    READ_ONCE(passthru_awdl_tid) >= 0) {
			int t = READ_ONCE(passthru_awdl_tid);

			if (t == 100)
				t = atomic_inc_return(&opairshare_awdl_tid_rr) & 7;
			data[24] = (data[24] & 0xf0) | (t & 0xf);
		}
		/* Wrap the payload as a single-subframe A-MSDU.
		 *
		 * On the wire, relative to the 26-octet QoS data MAC header:
		 *   +26 DA(6)   = addr1 (RA)
		 *   +32 SA(6)   = addr2 (TA)
		 *   +38 Length(2), MSDU length, MOST SIGNIFICANT OCTET FIRST
		 *   +40 MSDU    = the original payload, AWDL LLC included
		 * A single-subframe A-MSDU has no following subframe, so no
		 * alignment padding is appended: the last subframe is never
		 * padded. The only growth is the 14-octet subframe header, which
		 * cds_packet_alloc() reserved above.
		 *
		 * Evidence: stage53-amsdu/AM-SDU-FINDINGS.md. The layout comes from
		 * the standard; three decodable A-MSDU frames from OTHER Apple AWDL
		 * devices on channel 149 (not this link) agree with it and with no
		 * other reading. The previous code wrote the Length little-endian
		 * and padded the MSDU instead of the subframe, and the peer ACKed
		 * and dropped every frame it produced.
		 */
		if (data[0] == 0x88 && data[1] == 0 &&
		    !nfol && READ_ONCE(passthru_awdl_amsdu) == 1 && len > 26) {
			uint16_t plen = len - 26;

			if (plen <= OPAIRSHARE_AMSDU_MAX_MSDU &&
			    len + OPAIRSHARE_AMSDU_SUBFRAME_HDR <= READ_ONCE(passthru_awdl_amsdu_bytes)) {
				atomic64_inc(&opairshare_agg_wrapped);
				memmove(data + 26 + OPAIRSHARE_AMSDU_SUBFRAME_HDR,
					data + 26, plen);
				memmove(data + 26, data + 4, 6);   /* DA = addr1 */
				memmove(data + 32, data + 10, 6);  /* SA = addr2 */
				data[38] = (uint8_t)(plen >> 8);   /* Length, MSB first */
				data[39] = (uint8_t)(plen & 0xff);
				data[24] |= 0x80;   /* QoS Control: A-MSDU Present */
				len += OPAIRSHARE_AMSDU_SUBFRAME_HDR;
			}
		}
		if (data[0] == 0x88 && data[1] == 0 &&
		    READ_ONCE(passthru_awdl_ack_policy))
			data[24] = (data[24] & 0x9f) |
				((READ_ONCE(passthru_awdl_ack_policy) & 0x3) << 5);
		/* Worker owns item/vdev references; Stop flushes this worker
		 * before deleting any diagnostic target or the native vdev.
		 */
		if (data[0] == 0x88 && data[1] == 0)
			opairshare_target_learn(wma, item, id, data + 4);
		if (data[0] == 0x88 && data[1] == 0 &&
		    opairshare_target_ensure(wma, item->vdev, id, data + 4)) {
			cds_packet_free(buf);
			goto drop;
		}
		/* Pin the self peer of this PAS instance, rather than letting a
		 * global destination lookup select a peer on another STA vdev.
		 */
		peer = wlan_objmgr_get_peer(wma->psoc,
			wlan_objmgr_pdev_get_pdev_id(wma->pdev),
			wlan_vdev_mlme_get_macaddr(item->vdev), WLAN_MGMT_NB_ID);
		if (!peer || wlan_peer_get_vdev(peer) != item->vdev) {
			if (peer)
				wlan_objmgr_peer_release_ref(peer, WLAN_MGMT_NB_ID);
			cds_packet_free(buf);
			goto drop;
		}
		qdf_mem_zero(&mgmt, sizeof(mgmt));
		mgmt.tx_frame = buf;
		mgmt.frm_len = len;
		mgmt.vdev_id = id;
		mgmt.pdata = data;
		mgmt.chanfreq = READ_ONCE(passthru_mgmt_home_channel) ? 0 :
			(data[0] == 0xd0 ? item->freq : 0);
		if (READ_ONCE(passthru_mgmt_raw_tx) ||
		    (data[0] == 0x88 && data[1] == 0 &&
		     READ_ONCE(passthru_awdl_wmi_raw))) {
			/* FW ABI: explicit RAW params select the non-pause TID.
			 * Frames here are already complete 802.11 management frames.
			 * Use the original WMA rate encoder, not custom bit values.
			 */
			mgmt.tx_params_valid = true;
			wma_update_tx_send_params(&mgmt.tx_param, RATEID_6MBPS);
			if (data[0] == 0x88 && data[1] == 0 &&
			    READ_ONCE(passthru_awdl_wmi_raw)) {
				/* The rate helper initializes MGMT. The firmware ABI
				 * requires DATA=1 for this complete AWDL data frame.
				 */
				wma_update_tx_send_params(&mgmt.tx_param, opairshare_awdl_rate_id());
				mgmt.tx_param.frame_type = WMA_TX_SEND_DATA_TYPE;
				mgmt.tx_param.retry_limit = item->awdl_attempts;
				mgmt.tx_param.bw_mask = BIT(2); /* 20 MHz */
				if (READ_ONCE(passthru_awdl_phy)) {
					mgmt.tx_param.preamble_type = BIT(READ_ONCE(passthru_awdl_phy));
					mgmt.tx_param.mcs_mask = BIT(READ_ONCE(passthru_awdl_mcs));
					mgmt.tx_param.nss_mask = BIT(READ_ONCE(passthru_awdl_nss) == 2 ? 1 : 0);
				}
			}
		}
		mgmt.qdf_ctx = cds_get_context(QDF_MODULE_ID_QDF_DEVICE);
		mgmt.use_6mbps = item->freq > 3000;
		mgmt.tx_type = GENERIC_NODOWLOAD_ACK_COMP_INDEX;
		pr_info_ratelimited("OPAIRSHAREBRIDGESEND: vdev=%u freq=%u fc=%02x%02x len=%u\n",
			id, item->freq, data[1], data[0], len);
		/* Same native mgmt_txrx/WMI path as wma_tx_packet. The framework
		 * retains peer/descriptor on success. On failure we retain buf.
		 */
		opairshare_trace_register(item, buf, data, len, wma->pdev, id);
		opairshare_metric_register(item, buf, wma->pdev, id);
		/* Register ownership before the submit: the callback only receives
		 * buf, and the pointer must resolve from the moment the firmware can
		 * complete. A registration failure is settled here, before the
		 * submit, so there is exactly one terminal per item.
		 */
		if (READ_ONCE(passthru_awdl_retry)) {
			spin_lock_irqsave(&opairshare_bridge_lock, flags);
			retry_tracked = opairshare_bridge_inflight_add_locked(item, buf);
			if (retry_tracked && !item->first_submit_ns)
				item->first_submit_ns = ktime_get_ns();
			spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
			if (!retry_tracked) {
				opairshare_metric_remove(buf, true, 0);
				opairshare_trace_remove(buf, true, 0);
				item->trace_id = 0;
				pr_info_ratelimited("OPAIRSHAREBRIDGERETRY: inflight full\n");
				cds_packet_free(buf);
				goto drop;
			}
		}
		status = wlan_mgmt_txrx_mgmt_frame_tx(peer, wma->mac_context, buf,
			NULL, opairshare_bridge_complete, WLAN_UMAC_COMP_MLME, &mgmt);
		wlan_objmgr_peer_release_ref(peer, WLAN_MGMT_NB_ID);
		if (QDF_IS_STATUS_ERROR(status) && retry_tracked) {
			bool ours;
			spin_lock_irqsave(&opairshare_bridge_lock, flags);
			ours = opairshare_bridge_inflight_drop_locked(item);
			spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
			if (!ours) {
				/* The framework ran the completion on its own failure path and
				 * already settled this item: it owns buf and the terminal.
				 * Never free buf or settle pending a second time here.
				 */
				pr_err_ratelimited("OPAIRSHAREBRIDGERETRY: submit rc=%d settled by completion\n",
						   status);
				continue;
			}
		}
		opairshare_metric_return(item);
		if (QDF_IS_STATUS_ERROR(status)) {
			opairshare_metric_remove(buf, true, status);
			opairshare_trace_remove(buf, true, status);
			item->trace_id = 0;
			pr_info_ratelimited("OPAIRSHAREBRIDGEFAIL: native status=%d\n", status);
			cds_packet_free(buf);
			goto drop;
		}
		atomic64_inc(&opairshare_bridge_submitted);
        if (pack_bulk) atomic64_inc(&opairshare_bulk_packs[nfol + 1]);
		if (nfol) {
			atomic64_inc(&opairshare_agg_frames);
			atomic64_add(nfol + 1, &opairshare_agg_msdus);
			atomic64_add(len, &opairshare_agg_bytes);
			opairshare_agg_settle(fol, nfol, true);
			nfol = 0;
		}
		if (!retry_tracked) {
			consume_skb(item->skb);
			wlan_objmgr_vdev_release_ref(item->vdev, WLAN_MGMT_NB_ID);
			kfree(item);
		}
		continue;
drop:
		opairshare_agg_settle(fol, nfol, false);
		nfol = 0;
		opairshare_metric_drop(item);
		opairshare_trace_pre_drop(item);
		atomic64_inc(&opairshare_bridge_dropped);
		dev_kfree_skb_any(item->skb);
		wlan_objmgr_vdev_release_ref(item->vdev, WLAN_MGMT_NB_ID);
		kfree(item);
		opairshare_bridge_done();
	}
}

/* true means skb consumed; false preserves the original data/control path. */
/* 0=fallback unchanged, 1=consumed, 2=BUSY with the skb untouched. */
int opairshare_pas_mgmt_xmit(struct sk_buff *skb, struct wlan_objmgr_vdev *vdev, struct net_device *dev)
{
	struct opairshare_bridge_item *item;
	uint8_t rt[8], fcbytes[2], source[6], seqhdr[26];
	bool sequence_requested = false, trace_eligible = false;
	int accept_failure = 0;
	uint16_t rtlen, fc;
	unsigned int len;
	unsigned long flags;
	struct opairshare_agg_meta nm;
	struct opairshare_bridge_item *victim;
	bool have_hdr = false;
	if (!passthru_mgmt_bridge)
		return false;
	if (skb_copy_bits(skb, 0, rt, sizeof(rt)))
		goto invalid;
	rtlen = rt[2] | (rt[3] << 8);
	if (rt[0] || rtlen < 8 || rtlen > skb->len ||
	    skb_copy_bits(skb, rtlen, fcbytes, sizeof(fcbytes)))
		goto invalid;
	fc = fcbytes[0] | (fcbytes[1] << 8);
	if ((fc & IEEE80211_FCTL_FTYPE) != IEEE80211_FTYPE_MGMT) {
		uint8_t llc[8];
		static const uint8_t awdl_llc[8] =
			{0xaa, 0xaa, 0x03, 0x00, 0x17, 0xf2, 0x08, 0x00};
		/* Only ordinary no-DS, unprotected QoS AWDL data. Other data
		 * stays on the original TCL path; no packet header is changed.
		 */
		if (!READ_ONCE(passthru_awdl_wmi_raw) || fc != 0x0088 ||
		    skb->len - rtlen < 42 ||
		    skb_copy_bits(skb, rtlen + 26, llc, sizeof(llc)) ||
		    qdf_mem_cmp(llc, awdl_llc, sizeof(llc)))
			return false;
	}
	len = skb->len - rtlen;
	/* Initial supported set avoids native probe-response TSF rewriting.
	 * Arbitrary probe-request/action contents are copied without templates.
	 */
	if (len < 24 || len > 2304 ||
	    (fc != IEEE80211_STYPE_PROBE_REQ && fc != IEEE80211_STYPE_ACTION &&
	     fc != 0x0088) ||
	    (fc == IEEE80211_STYPE_ACTION && len < 25))
		goto invalid;
	if (fc == 0x0088 && !skb_copy_bits(skb, rtlen, seqhdr, sizeof(seqhdr))) {
		sequence_requested = READ_ONCE(passthru_awdl_host_seq) &&
			opairshare_awdl_sequence_scope(rt, fc, seqhdr);
		trace_eligible = !(seqhdr[4] & 1);
		have_hdr = true;
	}
	memset(&nm, 0, sizeof(nm));
	if (have_hdr && (READ_ONCE(passthru_awdl_amsdu) || READ_ONCE(passthru_awdl_ackfilter)))
		opairshare_agg_classify(&nm, skb, rtlen, seqhdr, len);
	/* A plain ACK that a queued older ACK of the same flow is superseded by
	 * frees its queue slot before the capacity checks below look at it.
	 */
	if (nm.ack_pure && READ_ONCE(passthru_awdl_ackfilter)) {
		victim = opairshare_ackf_detach(vdev, &nm);
		if (victim)
			opairshare_ackf_settle(victim);
	}
	/* A full queue needs no allocation or extra object reference to retry. */
	if (passthru_awdl_backpressure) {
		spin_lock_irqsave(&opairshare_bridge_lock, flags);
		if (vdev && dev && opairshare_bridge_accepting && opairshare_bridge_wq && opairshare_flow_dev == dev &&
		    opairshare_bridge_id == wlan_vdev_get_id(vdev) &&
		    atomic64_read(&opairshare_bridge_pending) >= READ_ONCE(passthru_awdl_capacity) && opairshare_flow_stop_locked()) {
			opairshare_flow_busy++;
			spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
			return 2;
		}
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	}
	item = kzalloc(sizeof(*item), GFP_ATOMIC);
	if (!item)
		goto invalid;
	if (!vdev || QDF_IS_STATUS_ERROR(wlan_objmgr_vdev_try_get_ref(vdev, WLAN_MGMT_NB_ID))) {
		kfree(item);
		goto invalid;
	}
	if (skb_copy_bits(skb, rtlen + 10, source, sizeof(source)) ||
	    qdf_mem_cmp(source, wlan_vdev_mlme_get_macaddr(vdev), sizeof(source))) {
		wlan_objmgr_vdev_release_ref(vdev, WLAN_MGMT_NB_ID);
		kfree(item);
		goto invalid;
	}
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (!opairshare_bridge_accepting || !opairshare_bridge_wq ||
	    opairshare_bridge_id != wlan_vdev_get_id(vdev))
		accept_failure = 1;
	else if (atomic64_read(&opairshare_bridge_pending) >= READ_ONCE(passthru_awdl_capacity)) {
		if (passthru_awdl_backpressure && opairshare_flow_dev == dev &&
		    opairshare_flow_stop_locked()) {
			opairshare_flow_busy++;
			spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
			wlan_objmgr_vdev_release_ref(vdev, WLAN_MGMT_NB_ID);
			kfree(item);
			return 2;
		}
		accept_failure = 2;
		if (trace_eligible) opairshare_trace_full_drop();
	} else if (!try_module_get(THIS_MODULE))
		accept_failure = 3;
	if (accept_failure) {
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		wlan_objmgr_vdev_release_ref(vdev, WLAN_MGMT_NB_ID);
		kfree(item);
		goto invalid;
	}
	if (opairshare_flow_bind_locked(dev)) {
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		module_put(THIS_MODULE);
		wlan_objmgr_vdev_release_ref(vdev, WLAN_MGMT_NB_ID);
		kfree(item);
		goto invalid;
	}
	item->skb = skb;
	item->agg = nm;
	item->vdev = vdev;
	item->radiotap_len = rtlen;
	item->freq = opairshare_bridge_freq;
	item->awdl_attempts = opairshare_awdl_attempts_snapshot(trace_eligible);
	opairshare_trace_accept(item, trace_eligible);
	item->sequence_valid = sequence_requested;
	if (sequence_requested)
		item->sequence_control = opairshare_awdl_sequence_allocate(seqhdr[24] & 15);
	atomic64_inc(&opairshare_bridge_pending);
	if (passthru_awdl_backpressure && atomic64_read(&opairshare_bridge_pending) >= READ_ONCE(passthru_awdl_capacity))
		opairshare_flow_stop_locked();
	atomic64_inc(&opairshare_bridge_accepted);
	opairshare_metric_accept(item, trace_eligible);
	list_add_tail(&item->list, &opairshare_bridge_queue);
	queue_work(opairshare_bridge_wq, &opairshare_bridge_work);
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	return true;
invalid:
	opairshare_metric_reject(accept_failure);
	atomic64_inc(&opairshare_bridge_dropped);
	dev_kfree_skb_any(skb);
	return true;
}

int opairshare_pas_mgmt_start(struct wlan_objmgr_vdev *vdev, uint16_t freq)
{
	struct workqueue_struct *wq;
	tp_wma_handle wma;
	uint8_t id;
	unsigned long flags;
	int target_rc;
	if (!passthru_mgmt_bridge)
		return 0;
	wma = cds_get_context(QDF_MODULE_ID_WMA);
	id = wlan_vdev_get_id(vdev);
	if (!wma || id >= wma->max_bssid || wma->interfaces[id].type != WMI_VDEV_TYPE_NDI ||
	    wlan_vdev_mlme_get_opmode(vdev) != QDF_PASSTHRU_MODE ||
	    !wmi_service_enabled(wma->wmi_handle, wmi_service_mgmt_tx_wmi))
		return -EOPNOTSUPP;
	mutex_lock(&opairshare_bridge_lifecycle);
	wq = alloc_ordered_workqueue("opairshare_pas_mgmt", WQ_MEM_RECLAIM);
	if (!wq) {
		mutex_unlock(&opairshare_bridge_lifecycle);
		return -ENOMEM;
	}
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (opairshare_bridge_wq || atomic64_read(&opairshare_bridge_pending) || opairshare_flow_dev) {
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		destroy_workqueue(wq);
		mutex_unlock(&opairshare_bridge_lifecycle);
		return -EBUSY;
	}
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	opairshare_pas_rx_order_end();
	target_rc = opairshare_target_prepare(wma, vdev, id);
	if (target_rc) {
		destroy_workqueue(wq);
		mutex_unlock(&opairshare_bridge_lifecycle);
		return target_rc;
	}
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	hrtimer_init(&opairshare_collect_timer, CLOCK_MONOTONIC, HRTIMER_MODE_REL);
	opairshare_collect_timer.function = opairshare_collect_expire;
	/* Lifecycle+spin locks held; the prior instance has no pending
	 * frames. Pause/resume and channel changes never reset the counter.
	 */
	opairshare_awdl_sequence_reset();
	opairshare_trace_new_instance();
	opairshare_channel_reset();
	opairshare_bridge_wq = wq;
	opairshare_bridge_id = id;
	opairshare_bridge_freq = freq;
	opairshare_bridge_accepting = true;
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	pr_info("OPAIRSHAREBRIDGESTART: vdev=%u freq=%u\n", id, freq);
	mutex_unlock(&opairshare_bridge_lifecycle);
	return 0;
}

static int opairshare_bridge_pause_locked(uint8_t id)
{
	struct workqueue_struct *wq;
	unsigned long flags;
	if (!passthru_mgmt_bridge)
		return 0;
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (id != opairshare_bridge_id || !opairshare_bridge_wq) {
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		return 0;
	}
	opairshare_bridge_accepting = false;
	wq = opairshare_bridge_wq;
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
    hrtimer_cancel(&opairshare_collect_timer);
    queue_work(wq, &opairshare_bridge_work);
	flush_workqueue(wq);
	if (!wait_event_timeout(opairshare_bridge_wait, !atomic64_read(&opairshare_bridge_pending),
				msecs_to_jiffies(3000))) {
		pr_err("OPAIRSHAREBRIDGEDRAIN: vdev=%u pending=%lld timeout; retain resources\n",
			id, (long long)atomic64_read(&opairshare_bridge_pending));
		return -ETIMEDOUT;
	}
	pr_info("OPAIRSHAREBRIDGEDRAIN: vdev=%u pending=0\n", id);
	return 0;
}

int opairshare_pas_mgmt_pause(uint8_t id)
{
	int ret;
	mutex_lock(&opairshare_bridge_lifecycle);
	ret = opairshare_bridge_pause_locked(id);
	mutex_unlock(&opairshare_bridge_lifecycle);
	return ret;
}

void opairshare_pas_mgmt_resume(uint8_t id, uint16_t freq)
{
	unsigned long flags;
	mutex_lock(&opairshare_bridge_lifecycle);
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (opairshare_bridge_wq && opairshare_bridge_id == id && !READ_ONCE(opairshare_target_fault) &&
	    !opairshare_channel_fault && !opairshare_channel_busy) {
		opairshare_bridge_freq = freq;
		opairshare_bridge_accepting = true;
		opairshare_flow_maybe_wake_locked();
	}
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	mutex_unlock(&opairshare_bridge_lifecycle);
}

int opairshare_pas_mgmt_stop(uint8_t id)
{
	struct workqueue_struct *wq;
	unsigned long flags;
	int ret;
	mutex_lock(&opairshare_bridge_lifecycle);
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (opairshare_bridge_wq && opairshare_bridge_id == id) {
		opairshare_channel_fault = true;
		opairshare_channel_busy = false;
		if (!++opairshare_channel_cookie)
			++opairshare_channel_cookie;
	}
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	ret = opairshare_bridge_pause_locked(id);
	if (ret) {
		mutex_unlock(&opairshare_bridge_lifecycle);
		return ret;
	}
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (opairshare_bridge_id != id) {
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		mutex_unlock(&opairshare_bridge_lifecycle);
		return 0;
	}
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	ret = opairshare_rx_filter_clear_locked(id);
	if (ret) {
		mutex_unlock(&opairshare_bridge_lifecycle);
		return ret;
	}
	opairshare_pas_rx_order_end();
	ret = opairshare_target_stop(cds_get_context(QDF_MODULE_ID_WMA), id);
	if (ret) {
		mutex_unlock(&opairshare_bridge_lifecycle);
		return ret;
	}
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	ret = opairshare_flow_forget_locked();
	if (ret) {
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		mutex_unlock(&opairshare_bridge_lifecycle);
		return ret;
	}
	wq = opairshare_bridge_wq;
	opairshare_channel_reset();
	opairshare_bridge_wq = NULL;
	opairshare_bridge_id = WLAN_INVALID_VDEV_ID;
	opairshare_bridge_freq = 0;
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	if (wq)
		destroy_workqueue(wq);
	mutex_unlock(&opairshare_bridge_lifecycle);
	return 0;
}
#include "wma_opairshare_awdl_ba.h"
#endif
