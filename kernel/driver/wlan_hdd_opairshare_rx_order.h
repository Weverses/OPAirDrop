/* SPDX-License-Identifier: GPL-2.0-only */
/* The monitor copy bypasses REO. Reorder the negotiated peer's TID 0 before
 * handing it to Wonder/Mosey, using the 12-bit 802.11 sequence number. */
static unsigned int passthru_awdl_rx_order_ms = 0;
module_param(passthru_awdl_rx_order_ms, uint, 0644);
MODULE_PARM_DESC(passthru_awdl_rx_order_ms, "Monitor RX reorder hold time, 0=off, milliseconds (max 400)");
#define OPAIRSHARE_ORDER_WINDOW 64
static DEFINE_SPINLOCK(opairshare_order_lock);
static struct sk_buff *opairshare_order_slots[OPAIRSHARE_ORDER_WINDOW];
static u16 opairshare_order_seq[OPAIRSHARE_ORDER_WINDOW];
static u8 opairshare_order_peer[6], opairshare_order_last_peer[6];
static u16 opairshare_order_next, opairshare_order_last;
static bool opairshare_order_active, opairshare_order_observed;
static unsigned long opairshare_order_deadline;
static u64 opairshare_order_held, opairshare_order_released, opairshare_order_late;
static u64 opairshare_order_skipped, opairshare_order_timeouts;
static void opairshare_order_timeout(struct work_struct *work);
static DECLARE_DELAYED_WORK(opairshare_order_work, opairshare_order_timeout);

static int opairshare_order_stats_get(char *out, const struct kernel_param *kp)
{
	unsigned long flags;
	unsigned int i, pending = 0;
	int n;
	(void)kp;
	spin_lock_irqsave(&opairshare_order_lock, flags);
	for (i = 0; i < OPAIRSHARE_ORDER_WINDOW; i++)
		pending += !!opairshare_order_slots[i];
	n = scnprintf(out, 256,
		"active=%u wait_ms=%u next=%u pending=%u held=%llu released=%llu late=%llu skipped=%llu timeouts=%llu\n",
		opairshare_order_active, READ_ONCE(passthru_awdl_rx_order_ms), opairshare_order_next,
		pending, opairshare_order_held, opairshare_order_released, opairshare_order_late,
		opairshare_order_skipped, opairshare_order_timeouts);
	spin_unlock_irqrestore(&opairshare_order_lock, flags);
	return n;
}
static const struct kernel_param_ops opairshare_order_stats_ops = { .get = opairshare_order_stats_get };
module_param_cb(opairshare_rx_order_stats, &opairshare_order_stats_ops, NULL, 0444);

static void opairshare_order_emit(struct sk_buff_head *out)
{
	struct sk_buff *skb;
	while ((skb = __skb_dequeue(out))) {
		atomic64_inc(&opairshare_aux_forwarded);
		netif_rx(skb);
	}
}

/* Move through [next, end), releasing present packets in sequence order. */
static void opairshare_order_advance(u16 end, struct sk_buff_head *out)
{
	while (opairshare_order_next != end) {
		unsigned int slot = opairshare_order_next & (OPAIRSHARE_ORDER_WINDOW - 1);
		struct sk_buff *skb = opairshare_order_slots[slot];
		if (skb && opairshare_order_seq[slot] == opairshare_order_next) {
			opairshare_order_slots[slot] = NULL;
			__skb_queue_tail(out, skb);
			opairshare_order_released++;
		} else {
			opairshare_order_skipped++;
		}
		opairshare_order_next = (opairshare_order_next + 1) & 4095;
	}
}

static void opairshare_order_ready(struct sk_buff_head *out)
{
	while (opairshare_order_slots[opairshare_order_next & 63] &&
	       opairshare_order_seq[opairshare_order_next & 63] == opairshare_order_next)
		opairshare_order_advance((opairshare_order_next + 1) & 4095, out);
}

static void opairshare_order_flush(struct sk_buff_head *out)
{
	unsigned int i, span = 0;
	for (i = 0; i < OPAIRSHARE_ORDER_WINDOW; i++) {
		if (opairshare_order_slots[i]) {
			unsigned int d = (opairshare_order_seq[i] - opairshare_order_next) & 4095;
			if (d < OPAIRSHARE_ORDER_WINDOW)
				span = max(span, d + 1);
		}
	}
	opairshare_order_advance((opairshare_order_next + span) & 4095, out);
}

static void opairshare_order_timeout(struct work_struct *work)
{
	struct sk_buff_head out;
	unsigned long flags;
	(void)work;
	__skb_queue_head_init(&out);
	spin_lock_irqsave(&opairshare_order_lock, flags);
	if (opairshare_order_deadline && time_before(jiffies, opairshare_order_deadline)) {
		schedule_delayed_work(&opairshare_order_work, opairshare_order_deadline - jiffies);
		spin_unlock_irqrestore(&opairshare_order_lock, flags);
		return;
	}
	opairshare_order_timeouts++;
	opairshare_order_deadline = 0;
	opairshare_order_flush(&out);
	opairshare_order_emit(&out);
	spin_unlock_irqrestore(&opairshare_order_lock, flags);
}

void opairshare_pas_rx_order_start(const u8 *peer, u16 ssn)
{
	struct sk_buff_head out;
	unsigned long flags;
	__skb_queue_head_init(&out);
	spin_lock_irqsave(&opairshare_order_lock, flags);
	opairshare_order_flush(&out);
	opairshare_order_deadline = 0;
	ether_addr_copy(opairshare_order_peer, peer);
	opairshare_order_next = opairshare_order_observed &&
		ether_addr_equal(peer, opairshare_order_last_peer) ?
		(opairshare_order_last + 1) & 4095 : ssn;
	opairshare_order_active = true;
	opairshare_order_emit(&out);
	spin_unlock_irqrestore(&opairshare_order_lock, flags);
}

void opairshare_pas_rx_order_end(void)
{
	struct sk_buff_head out;
	unsigned long flags;
	__skb_queue_head_init(&out);
	spin_lock_irqsave(&opairshare_order_lock, flags);
	opairshare_order_active = false;
	opairshare_order_observed = false;
	opairshare_order_deadline = 0;
	opairshare_order_flush(&out);
	opairshare_order_emit(&out);
	spin_unlock_irqrestore(&opairshare_order_lock, flags);
}

static void opairshare_order_stop(void)
{
	struct sk_buff_head out;
	struct sk_buff *skb;
	unsigned long flags;
	__skb_queue_head_init(&out);
	cancel_delayed_work_sync(&opairshare_order_work);
	spin_lock_irqsave(&opairshare_order_lock, flags);
	opairshare_order_active = false;
	opairshare_order_observed = false;
	opairshare_order_deadline = 0;
	opairshare_order_flush(&out);
	spin_unlock_irqrestore(&opairshare_order_lock, flags);
	while ((skb = __skb_dequeue(&out)))
		consume_skb(skb);
}

/* true: held/delivered/duplicate; the caller still consumes the borrowed skb. */
static bool opairshare_order_receive(struct sk_buff *skb, struct net_device *target, u16 rtlen)
{
	u8 hdr[26];
	u16 fc, seq, delta;
	unsigned int wait, slot;
	struct sk_buff *copy;
	struct sk_buff_head out;
	unsigned long flags;
	bool consumed = false;
	if (skb_copy_bits(skb, rtlen, hdr, sizeof(hdr)))
		return false;
	fc = hdr[0] | (hdr[1] << 8);
	if ((fc & ~IEEE80211_FCTL_RETRY) != 0x0088 || (hdr[24] & 15) ||
	    (hdr[22] & 15) || !ether_addr_equal(hdr + 4, target->dev_addr))
		return false;
	seq = (hdr[22] | (hdr[23] << 8)) >> 4;
	__skb_queue_head_init(&out);
	spin_lock_irqsave(&opairshare_order_lock, flags);
	if (!opairshare_order_observed || !ether_addr_equal(opairshare_order_last_peer, hdr + 10)) {
		ether_addr_copy(opairshare_order_last_peer, hdr + 10);
		opairshare_order_last = seq;
		opairshare_order_observed = true;
	} else if (((seq - opairshare_order_last) & 4095) < 2048) {
		opairshare_order_last = seq;
	}
	if (!opairshare_order_active || !ether_addr_equal(opairshare_order_peer, hdr + 10))
		goto unlock;
	wait = min_t(unsigned int, READ_ONCE(passthru_awdl_rx_order_ms), 400);
	if (!wait) {
		opairshare_order_flush(&out);
		opairshare_order_deadline = 0;
		opairshare_order_last = seq;
		opairshare_order_next = (seq + 1) & 4095;
		goto unlock;
	}
	delta = (seq - opairshare_order_next) & 4095;
	if (delta >= 2048) {
		opairshare_order_late++;
		/* A timed-out gap can still be repaired by a late MPDU. The
		 * exact-byte retry cache already removed known duplicates; let
		 * TCP receive this one rather than force a transport retransmit. */
		goto unlock;
	}
	if (delta >= OPAIRSHARE_ORDER_WINDOW)
		opairshare_order_advance((seq - OPAIRSHARE_ORDER_WINDOW + 1) & 4095, &out);
	slot = seq & (OPAIRSHARE_ORDER_WINDOW - 1);
	if (opairshare_order_slots[slot]) {
		opairshare_order_late++;
		consumed = true;
		goto unlock;
	}
	copy = skb_clone(skb, GFP_ATOMIC);
	if (!copy)
		goto unlock;
	copy->dev = target;
	copy->skb_iif = target->ifindex;
	copy->pkt_type = PACKET_HOST;
	copy->protocol = htons(ETH_P_802_2);
	skb_reset_mac_header(copy);
	opairshare_order_slots[slot] = copy;
	opairshare_order_seq[slot] = seq;
	opairshare_order_held++;
	consumed = true;
	opairshare_order_ready(&out);
	/* First gap sets the deadline; later packets must not extend it. */
	{
		unsigned int i, pending = 0;
		for (i = 0; i < OPAIRSHARE_ORDER_WINDOW; i++)
			pending += !!opairshare_order_slots[i];
		if (pending) {
			if (!opairshare_order_deadline)
				opairshare_order_deadline = jiffies + msecs_to_jiffies(wait);
			if (!delayed_work_pending(&opairshare_order_work))
				schedule_delayed_work(&opairshare_order_work,
					time_before(jiffies, opairshare_order_deadline) ?
					opairshare_order_deadline - jiffies : 1);
		} else {
			opairshare_order_deadline = 0;
			cancel_delayed_work(&opairshare_order_work);
		}
	}
unlock:
	opairshare_order_emit(&out);
	spin_unlock_irqrestore(&opairshare_order_lock, flags);
	return consumed;
}
