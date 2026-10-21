/* SPDX-License-Identifier: GPL-2.0-only */
/* Included by the PAS native-monitor companion, after its counter ops. */
#include <linux/jiffies.h>
#include <linux/spinlock.h>

static bool passthru_awdl_rx_dedup;
module_param(passthru_awdl_rx_dedup, bool, 0644);
MODULE_PARM_DESC(passthru_awdl_rx_dedup,
	"PAS-only: suppress identical unicast AWDL Retry frames for 500ms");
static atomic64_t opairshare_awdl_rx_duplicates = ATOMIC64_INIT(0);
module_param_cb(opairshare_awdl_rx_duplicates, &opairshare_aux_count_ops,
		&opairshare_awdl_rx_duplicates, 0444);

/* Exact bytes, not a hash: a collision must never suppress a new packet.
 * Ignore FC/Duration and FCS, retaining addresses, sequence/fragment, TID,
 * AWDL header and the entire declared IPv6 packet. No allocation in RX.
 */
#define OPAIRSHARE_AWDL_RX_CACHE 64
#define OPAIRSHARE_AWDL_RX_MAX 2300
struct opairshare_awdl_rx_entry {
	unsigned long stamp;
	u16 len;
	u8 bytes[OPAIRSHARE_AWDL_RX_MAX];
};
static struct opairshare_awdl_rx_entry opairshare_awdl_rx_cache[OPAIRSHARE_AWDL_RX_CACHE];
static DEFINE_SPINLOCK(opairshare_awdl_rx_lock);
static unsigned int opairshare_awdl_rx_next;

static void opairshare_awdl_rx_reset(void)
{
	unsigned long flags;
	unsigned int i;
	spin_lock_irqsave(&opairshare_awdl_rx_lock, flags);
	for (i = 0; i < OPAIRSHARE_AWDL_RX_CACHE; i++)
		opairshare_awdl_rx_cache[i].len = 0;
	opairshare_awdl_rx_next = 0;
	spin_unlock_irqrestore(&opairshare_awdl_rx_lock, flags);
}

static bool opairshare_awdl_rx_duplicate(struct sk_buff *skb,
				  struct net_device *target, u16 rtlen)
{
	static const u8 llc[8] = {0xaa, 0xaa, 3, 0, 0x17, 0xf2, 8, 0};
	u8 header[48], chunk[64];
	u16 fc;
	unsigned int bytes, i, off, n;
	unsigned long flags, now = jiffies;
	struct opairshare_awdl_rx_entry *entry = NULL;
	bool duplicate = false, equal;

	if (!READ_ONCE(passthru_awdl_rx_dedup) ||
	    skb_copy_bits(skb, rtlen, header, sizeof(header)))
		return false;
	fc = header[0] | (header[1] << 8);
	/* Do not touch management, multicast, fragments, other TIDs/protocols,
	 * or packets addressed to another interface. Unsupported input passes.
	 */
	if ((fc & ~0x0800) != 0x0088 || (header[22] & 0x0f) ||
	    (header[4] & 1) || memcmp(header + 4, target->dev_addr, 6) ||
	    memcmp(header + 26, llc, sizeof(llc)) ||
	    header[34] != 3 || header[35] != 4 ||
	    header[40] != 0x86 || header[41] != 0xdd ||
	    (header[42] >> 4) != 6)
		return false;
	/* Read IPv6 payload length separately (offset 46, in the header). */
	bytes = 42 + 40 + ((header[46] << 8) | header[47]) - 4;
	if (bytes > OPAIRSHARE_AWDL_RX_MAX || rtlen + 4 + bytes > skb->len)
		return false;

	spin_lock_irqsave(&opairshare_awdl_rx_lock, flags);
	for (i = 0; i < OPAIRSHARE_AWDL_RX_CACHE; i++) {
		struct opairshare_awdl_rx_entry *candidate = &opairshare_awdl_rx_cache[i];
		if (candidate->len != bytes ||
		    time_after(now, candidate->stamp + msecs_to_jiffies(500)) ||
		    memcmp(candidate->bytes, header + 4, 22))
			continue;
		equal = true;
		for (off = 22; off < bytes; off += n) {
			n = min_t(unsigned int, sizeof(chunk), bytes - off);
			if (skb_copy_bits(skb, rtlen + 4 + off, chunk, n) ||
			    memcmp(chunk, candidate->bytes + off, n)) {
				equal = false;
				break;
			}
		}
		if (equal) { entry = candidate; break; }
	}
	if (entry && (fc & 0x0800)) {
		duplicate = true;
		/* Keep the first-arrival deadline: retries cannot extend it. */
		atomic64_inc(&opairshare_awdl_rx_duplicates);
	} else {
		if (!entry)
			entry = &opairshare_awdl_rx_cache[opairshare_awdl_rx_next++ % OPAIRSHARE_AWDL_RX_CACHE];
		entry->len = 0;
		if (!skb_copy_bits(skb, rtlen + 4, entry->bytes, bytes)) {
			entry->stamp = now;
			entry->len = bytes;
		}
	}
	spin_unlock_irqrestore(&opairshare_awdl_rx_lock, flags);
	return duplicate;
}
