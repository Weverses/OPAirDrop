/* SPDX-License-Identifier: GPL-2.0-only */
/* Bounded observational side table; never owns packet/peer/module references. */
#include <linux/ktime.h>

#define OPAIRSHARE_TRACE_SLOTS 64
struct opairshare_trace_entry {
	bool used, has_desc;
	qdf_nbuf_t buf;
	void *pdev;
	u64 id, epoch, enqueued, dequeued, wmi;
	u32 desc;
	u16 len, freq, seq;
	u8 vdev, tid, ack_policy, ra[6], ta[6], bssid[6];
};
static struct opairshare_trace_entry opairshare_trace_entries[OPAIRSHARE_TRACE_SLOTS];
static DEFINE_SPINLOCK(opairshare_trace_lock);
static unsigned int opairshare_trace_budget;
static unsigned int opairshare_trace_open;
static u64 opairshare_trace_epoch, opairshare_trace_next_id;
static u64 opairshare_trace_selected, opairshare_trace_wmis, opairshare_trace_done_count;
static u64 opairshare_trace_failures, opairshare_trace_misses, opairshare_trace_full_drops;
static u64 opairshare_trace_queue_max_ns, opairshare_trace_fw_max_ns;
static u64 opairshare_trace_peak_pending, opairshare_trace_status[5];

static int opairshare_trace_arm_set(const char *value, const struct kernel_param *kp)
{
	unsigned int n, i;
	unsigned long flags;
	int rc = kstrtouint(value, 0, &n);
	(void)kp;
	if (rc || n > 256)
		return rc ? rc : -EINVAL;
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	if (n) {
		if (opairshare_trace_open) {
			spin_unlock_irqrestore(&opairshare_trace_lock, flags);
			return -EBUSY; /* Includes selected items still in the host queue. */
		}
		for (i = 0; i < OPAIRSHARE_TRACE_SLOTS; i++)
			if (opairshare_trace_entries[i].used) {
				spin_unlock_irqrestore(&opairshare_trace_lock, flags);
				return -EBUSY;
			}
		opairshare_trace_selected = opairshare_trace_wmis = opairshare_trace_done_count = 0;
		opairshare_trace_failures = opairshare_trace_misses = opairshare_trace_full_drops = 0;
		opairshare_trace_queue_max_ns = opairshare_trace_fw_max_ns = 0;
		opairshare_trace_peak_pending = 0;
		memset(opairshare_trace_status, 0, sizeof(opairshare_trace_status));
	}
	/* arm=0 stops selection only; never clears an in-flight mapping. */
	opairshare_trace_budget = n;
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
	return 0;
}

static int opairshare_trace_get(char *out, const struct kernel_param *kp)
{
	unsigned int i, active = 0;
	unsigned long flags;
	int n;
	(void)kp;
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	for (i = 0; i < OPAIRSHARE_TRACE_SLOTS; i++)
		active += opairshare_trace_entries[i].used;
	n = scnprintf(out, 1024,
		"budget=%u active=%u open=%u epoch=%llu selected=%llu wmi=%llu done=%llu fail=%llu miss=%llu full_drop=%llu peak_pending=%llu queue_max_us=%llu fw_max_us=%llu status0=%llu status1=%llu status2=%llu status3=%llu status_other=%llu\n",
		opairshare_trace_budget, active, opairshare_trace_open, opairshare_trace_epoch,
		opairshare_trace_selected, opairshare_trace_wmis, opairshare_trace_done_count,
		opairshare_trace_failures, opairshare_trace_misses, opairshare_trace_full_drops,
		opairshare_trace_peak_pending, opairshare_trace_queue_max_ns / 1000,
		opairshare_trace_fw_max_ns / 1000,
		opairshare_trace_status[0], opairshare_trace_status[1], opairshare_trace_status[2],
		opairshare_trace_status[3], opairshare_trace_status[4]);
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
	return n;
}
static const struct kernel_param_ops opairshare_trace_ops = {
	.set = opairshare_trace_arm_set, .get = opairshare_trace_get,
};
module_param_cb(passthru_awdl_trace, &opairshare_trace_ops, NULL, 0644);
MODULE_PARM_DESC(passthru_awdl_trace, "0 stops new samples; 1..256 arms bounded AWDL unicast trace");

static void opairshare_trace_new_instance(void)
{
	unsigned long flags;
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	opairshare_trace_epoch++;
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
}

static void opairshare_trace_accept(struct opairshare_bridge_item *item, bool eligible)
{
	unsigned long flags;
	u64 pending;
	if (!eligible)
		return;
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	if (opairshare_trace_budget) {
		opairshare_trace_budget--;
		item->trace_id = ++opairshare_trace_next_id;
		item->trace_epoch = opairshare_trace_epoch;
		item->trace_enqueued = ktime_get_ns();
		opairshare_trace_selected++;
		opairshare_trace_open++;
		pending = atomic64_read(&opairshare_bridge_pending) + 1;
		opairshare_trace_peak_pending = max(opairshare_trace_peak_pending, pending);
	}
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
}

static void opairshare_trace_full_drop(void)
{
	unsigned long flags;
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	if (opairshare_trace_budget || opairshare_trace_selected)
		opairshare_trace_full_drops++;
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
}

static void opairshare_trace_register(struct opairshare_bridge_item *item, qdf_nbuf_t buf,
				const u8 *data, unsigned int len, void *pdev, u8 vdev)
{
	struct opairshare_trace_entry *r = NULL, copy;
	unsigned long flags;
	unsigned int i;
	if (!item->trace_id)
		return;
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	for (i = 0; i < OPAIRSHARE_TRACE_SLOTS; i++)
		if (!opairshare_trace_entries[i].used) { r = &opairshare_trace_entries[i]; break; }
	if (!r) {
		opairshare_trace_misses++;
		if (opairshare_trace_open) opairshare_trace_open--;
		item->trace_id = 0;
		spin_unlock_irqrestore(&opairshare_trace_lock, flags);
		return; /* Missing telemetry never rejects the real packet. */
	}
	memset(r, 0, sizeof(*r));
	r->used = true; r->buf = buf; r->pdev = pdev;
	r->id = item->trace_id; r->epoch = item->trace_epoch;
	r->enqueued = item->trace_enqueued; r->dequeued = item->trace_dequeued;
	r->len = len; r->freq = item->freq; r->vdev = vdev;
	r->seq = data[22] | (data[23] << 8);
	r->tid = data[24] & 15; r->ack_policy = (data[24] >> 5) & 3;
	memcpy(r->ra, data + 4, 6); memcpy(r->ta, data + 10, 6);
	memcpy(r->bssid, data + 16, 6);
	opairshare_trace_queue_max_ns = max(opairshare_trace_queue_max_ns,
					 r->dequeued - r->enqueued);
	copy = *r;
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
	pr_info("OPAIRSHAREAWDLTRACE ENQ id=%llu epoch=%llu vdev=%u len=%u freq=%u seq=%u tid=%u ack_policy=%u ra=%pM ta=%pM bssid=%pM enqueue_ns=%llu dequeue_ns=%llu\n",
		copy.id, copy.epoch, copy.vdev, copy.len, copy.freq, copy.seq,
		copy.tid, copy.ack_policy, copy.ra, copy.ta, copy.bssid,
		copy.enqueued, copy.dequeued);
}

void opairshare_pas_trace_wmi(void *pdev, qdf_nbuf_t buf, u32 desc,
			     const struct wmi_mgmt_params *params)
{
	struct opairshare_trace_entry copy;
	unsigned long flags;
	unsigned int i;
	bool found = false;
	opairshare_metric_wmi(pdev, buf, desc, params->vdev_id);
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	for (i = 0; i < OPAIRSHARE_TRACE_SLOTS; i++) {
		struct opairshare_trace_entry *r = &opairshare_trace_entries[i];
		if (r->used && r->buf == buf && r->pdev == pdev &&
		    r->vdev == params->vdev_id && !r->has_desc) {
			r->desc = desc; r->has_desc = true; r->wmi = ktime_get_ns();
			copy = *r; found = true; opairshare_trace_wmis++; break;
		}
	}
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
	if (found)
		pr_info("OPAIRSHAREAWDLTRACE WMI id=%llu epoch=%llu desc=%u vdev=%u frame_type=%u flags=%u valid=%u chanfreq=%u retry_limit=%u mcs_mask=%u wmi_ns=%llu\n",
			copy.id, copy.epoch, desc, params->vdev_id,
			params->tx_param.frame_type, params->tx_flags,
			params->tx_params_valid, params->chanfreq,
			params->tx_param.retry_limit, params->tx_param.mcs_mask, copy.wmi);
}

void opairshare_pas_trace_fw(void *pdev, qdf_nbuf_t buf, u32 desc, u32 status,
			    u32 fields, u32 fw_pdev, u32 ppdu, u32 ack_rssi,
			    u32 rate, u32 retries, u64 tsf, u32 info)
{
	struct opairshare_trace_entry copy;
	unsigned long flags;
	unsigned int i;
	bool found = false;
	u64 now = ktime_get_ns();
	opairshare_metric_event(pdev, buf, desc);
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	for (i = 0; i < OPAIRSHARE_TRACE_SLOTS; i++) {
		struct opairshare_trace_entry *r = &opairshare_trace_entries[i];
		if (r->used && r->has_desc && r->buf == buf &&
		    r->pdev == pdev && r->desc == desc) {
			copy = *r; found = true; break;
		}
	}
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
	if (found)
		pr_info("OPAIRSHAREAWDLTRACE EVT id=%llu epoch=%llu desc=%u status=%u fields=%u fw_pdev=%u ppdu=%u ack_rssi_raw=%u rate_kbps=%u retries=%u tsf_us=%llu info=%u event_ns=%llu\n",
			copy.id, copy.epoch, desc, status, fields, fw_pdev, ppdu,
			ack_rssi, rate, retries, tsf, info, now);
}

static void opairshare_trace_remove(qdf_nbuf_t buf, bool failed, u32 status)
{
	struct opairshare_trace_entry copy;
	unsigned long flags;
	unsigned int i;
	bool found = false;
	u64 now = ktime_get_ns();
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	for (i = 0; i < OPAIRSHARE_TRACE_SLOTS; i++) {
		struct opairshare_trace_entry *r = &opairshare_trace_entries[i];
		if (r->used && r->buf == buf) {
			copy = *r; r->used = false; found = true;
			if (opairshare_trace_open) opairshare_trace_open--;
			if (failed) opairshare_trace_failures++;
			else {
				opairshare_trace_done_count++;
				opairshare_trace_status[status < 4 ? status : 4]++;
				if (r->wmi)
					opairshare_trace_fw_max_ns = max(opairshare_trace_fw_max_ns, now - r->wmi);
			}
			break;
		}
	}
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
	if (found)
		pr_info("OPAIRSHAREAWDLTRACE %s id=%llu epoch=%llu desc=%u has_desc=%u status=%u callback_ns=%llu\n",
			failed ? "FAIL" : "DONE", copy.id, copy.epoch,
			copy.desc, copy.has_desc, status, now);
}

static void opairshare_trace_pre_drop(struct opairshare_bridge_item *item)
{
	unsigned long flags;
	u64 id = item->trace_id;
	if (!id)
		return;
	spin_lock_irqsave(&opairshare_trace_lock, flags);
	if (opairshare_trace_open) opairshare_trace_open--;
	opairshare_trace_failures++;
	item->trace_id = 0;
	spin_unlock_irqrestore(&opairshare_trace_lock, flags);
	pr_info("OPAIRSHAREAWDLTRACE PRE_DROP id=%llu epoch=%llu\n", id, item->trace_epoch);
}
