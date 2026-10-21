/* SPDX-License-Identifier: GPL-2.0-only */
/* Observational only: never retains a packet or changes TX ownership/gates. */
#include <linux/ktime.h>
#define OPAIRSHARE_METRIC_SLOTS 64
#define OPAIRSHARE_METRIC_BINS 11
struct opairshare_metric_hist {
	u64 n, sum, maximum, bins[OPAIRSHARE_METRIC_BINS];
};
struct opairshare_metric_entry {
	bool used, data, wmi_seen, event_seen;
	qdf_nbuf_t buf;
	void *pdev;
	u8 vdev;
	u32 desc;
	u64 enqueued, entered, wmi, event;
};
static DEFINE_SPINLOCK(opairshare_metric_lock);
static struct opairshare_metric_entry opairshare_metric_entries[OPAIRSHARE_METRIC_SLOTS];
static u64 opairshare_metric_queued, opairshare_metric_processing, opairshare_metric_live;
static u64 opairshare_metric_calls, opairshare_metric_peak_queued, opairshare_metric_peak_live;
static u64 opairshare_metric_accepted, opairshare_metric_completed, opairshare_metric_failed;
static u64 opairshare_metric_worker_drop, opairshare_metric_rejects[4], opairshare_metric_errors;
static u64 opairshare_metric_data_accepted, opairshare_metric_data_completed;
static u64 opairshare_metric_status[5];
static struct opairshare_metric_hist opairshare_metric_queue_hist, opairshare_metric_prepare_hist;
static struct opairshare_metric_hist opairshare_metric_call_hist, opairshare_metric_native_hist;
static struct opairshare_metric_hist opairshare_metric_wmi_hist, opairshare_metric_event_hist;
static struct opairshare_metric_hist opairshare_metric_total_hist;

static void opairshare_metric_hist_add(struct opairshare_metric_hist *h, u64 ns)
{
	static const u64 upper[] = {100000, 500000, 1000000, 5000000,
		10000000, 25000000, 50000000, 100000000, 250000000, 1000000000};
	unsigned int i;
	h->n++;
	h->sum += ns;
	if (ns > h->maximum) h->maximum = ns;
	for (i = 0; i < OPAIRSHARE_METRIC_BINS - 1; i++)
		if (ns <= upper[i]) break;
	h->bins[i]++;
}

static void opairshare_metric_dec(u64 *value)
{
	if (*value) (*value)--;
	else opairshare_metric_errors++;
}

static void opairshare_metric_accept(struct opairshare_bridge_item *item, bool data)
{
	unsigned long flags;
	item->metric_data = data;
	item->metric_enqueued = ktime_get_ns();
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	opairshare_metric_accepted++;
	if (data) opairshare_metric_data_accepted++;
	opairshare_metric_queued++;
	if (opairshare_metric_queued > opairshare_metric_peak_queued)
		opairshare_metric_peak_queued = opairshare_metric_queued;
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

static void opairshare_metric_dequeue(struct opairshare_bridge_item *item)
{
	unsigned long flags;
	item->metric_dequeued = ktime_get_ns();
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	opairshare_metric_dec(&opairshare_metric_queued);
	opairshare_metric_processing++;
	if (item->metric_data)
		opairshare_metric_hist_add(&opairshare_metric_queue_hist,
			item->metric_dequeued - item->metric_enqueued);
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

static void opairshare_metric_register(struct opairshare_bridge_item *item, qdf_nbuf_t buf,
				 void *pdev, u8 vdev)
{
	struct opairshare_metric_entry *r = NULL;
	unsigned int i;
	unsigned long flags;
	item->metric_entered = ktime_get_ns();
	item->metric_native = true;
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	opairshare_metric_dec(&opairshare_metric_processing);
	opairshare_metric_calls++;
	for (i = 0; i < OPAIRSHARE_METRIC_SLOTS; i++) {
		if (opairshare_metric_entries[i].used && opairshare_metric_entries[i].buf == buf) {
			opairshare_metric_errors++;
			r = NULL;
			break;
		}
		if (!opairshare_metric_entries[i].used && !r) r = &opairshare_metric_entries[i];
	}
	if (!r) opairshare_metric_errors++;
	else {
		memset(r, 0, sizeof(*r));
		r->used = true; r->data = item->metric_data;
		r->buf = buf; r->pdev = pdev; r->vdev = vdev;
		r->enqueued = item->metric_enqueued; r->entered = item->metric_entered;
		opairshare_metric_live++;
		if (opairshare_metric_live > opairshare_metric_peak_live)
			opairshare_metric_peak_live = opairshare_metric_live;
	}
	if (item->metric_data)
		opairshare_metric_hist_add(&opairshare_metric_prepare_hist,
			item->metric_entered - item->metric_dequeued);
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

static void opairshare_metric_return(struct opairshare_bridge_item *item)
{
	unsigned long flags;
	u64 now = ktime_get_ns();
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	opairshare_metric_dec(&opairshare_metric_calls);
	if (item->metric_data)
		opairshare_metric_hist_add(&opairshare_metric_call_hist, now - item->metric_entered);
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

static void opairshare_metric_wmi(void *pdev, qdf_nbuf_t buf, u32 desc, u8 vdev)
{
	unsigned int i;
	unsigned long flags;
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	for (i = 0; i < OPAIRSHARE_METRIC_SLOTS; i++) {
		struct opairshare_metric_entry *r = &opairshare_metric_entries[i];
		if (r->used && r->buf == buf && r->pdev == pdev &&
		    r->vdev == vdev && !r->wmi_seen) {
			r->wmi_seen = true; r->wmi = ktime_get_ns(); r->desc = desc;
			break;
		}
	}
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

static void opairshare_metric_event(void *pdev, qdf_nbuf_t buf, u32 desc)
{
	unsigned int i;
	unsigned long flags;
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	for (i = 0; i < OPAIRSHARE_METRIC_SLOTS; i++) {
		struct opairshare_metric_entry *r = &opairshare_metric_entries[i];
		if (r->used && r->buf == buf && r->pdev == pdev &&
		    r->wmi_seen && r->desc == desc && !r->event_seen) {
			r->event_seen = true; r->event = ktime_get_ns();
			break;
		}
	}
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

static void opairshare_metric_remove(qdf_nbuf_t buf, bool failed, u32 status)
{
	unsigned int i;
	unsigned long flags;
	u64 now = ktime_get_ns();
	bool found = false;
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	for (i = 0; i < OPAIRSHARE_METRIC_SLOTS; i++) {
		struct opairshare_metric_entry *r = &opairshare_metric_entries[i];
		if (!r->used || r->buf != buf) continue;
		found = true; r->used = false;
		opairshare_metric_dec(&opairshare_metric_live);
		if (failed) opairshare_metric_failed++;
		else {
			opairshare_metric_completed++;
			if (r->data) {
				opairshare_metric_data_completed++;
				opairshare_metric_status[status < 4 ? status : 4]++;
				opairshare_metric_hist_add(&opairshare_metric_native_hist, now - r->entered);
				opairshare_metric_hist_add(&opairshare_metric_total_hist, now - r->enqueued);
				if (r->wmi_seen)
					opairshare_metric_hist_add(&opairshare_metric_wmi_hist, now - r->wmi);
				if (r->event_seen)
					opairshare_metric_hist_add(&opairshare_metric_event_hist, now - r->event);
			}
		}
		break;
	}
	if (!found) opairshare_metric_errors++;
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

static void opairshare_metric_drop(struct opairshare_bridge_item *item)
{
	unsigned long flags;
	if (item->metric_native) return; /* Failed submit was settled by remove. */
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	opairshare_metric_dec(&opairshare_metric_processing);
	opairshare_metric_worker_drop++;
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

static void opairshare_metric_reject(unsigned int reason)
{
	unsigned long flags;
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	opairshare_metric_rejects[reason < 4 ? reason : 0]++;
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
}

static int opairshare_metric_hist_print(char *out, int n, const char *name,
				 const struct opairshare_metric_hist *h)
{
	unsigned int i;
	n += scnprintf(out + n, 4096 - n,
		"%s n=%llu sum_ns=%llu max_ns=%llu bins=", name,
		(unsigned long long)h->n, (unsigned long long)h->sum,
		(unsigned long long)h->maximum);
	for (i = 0; i < OPAIRSHARE_METRIC_BINS; i++)
		n += scnprintf(out + n, 4096 - n, "%s%llu", i ? "," : "",
			(unsigned long long)h->bins[i]);
	n += scnprintf(out + n, 4096 - n, "\n");
	return n;
}

static int opairshare_metric_get(char *out, const struct kernel_param *kp)
{
	unsigned long flags;
	int n;
	(void)kp;
	spin_lock_irqsave(&opairshare_metric_lock, flags);
	n = scnprintf(out, 4096,
		"queued=%llu processing=%llu native_live=%llu calls=%llu peak_queued=%llu peak_native_live=%llu accepted=%llu completed=%llu submit_fail=%llu worker_drop=%llu reject_invalid=%llu reject_gate=%llu reject_full=%llu reject_module=%llu errors=%llu data_accepted=%llu data_completed=%llu status0=%llu status1=%llu status2=%llu status3=%llu status_other=%llu\n",
		(unsigned long long)opairshare_metric_queued, (unsigned long long)opairshare_metric_processing,
		(unsigned long long)opairshare_metric_live, (unsigned long long)opairshare_metric_calls,
		(unsigned long long)opairshare_metric_peak_queued, (unsigned long long)opairshare_metric_peak_live,
		(unsigned long long)opairshare_metric_accepted, (unsigned long long)opairshare_metric_completed,
		(unsigned long long)opairshare_metric_failed, (unsigned long long)opairshare_metric_worker_drop,
		(unsigned long long)opairshare_metric_rejects[0], (unsigned long long)opairshare_metric_rejects[1],
		(unsigned long long)opairshare_metric_rejects[2], (unsigned long long)opairshare_metric_rejects[3],
		(unsigned long long)opairshare_metric_errors, (unsigned long long)opairshare_metric_data_accepted,
		(unsigned long long)opairshare_metric_data_completed, (unsigned long long)opairshare_metric_status[0],
		(unsigned long long)opairshare_metric_status[1], (unsigned long long)opairshare_metric_status[2],
		(unsigned long long)opairshare_metric_status[3], (unsigned long long)opairshare_metric_status[4]);
	n = opairshare_metric_hist_print(out, n, "queue", &opairshare_metric_queue_hist);
	n = opairshare_metric_hist_print(out, n, "prepare", &opairshare_metric_prepare_hist);
	n = opairshare_metric_hist_print(out, n, "call", &opairshare_metric_call_hist);
	n = opairshare_metric_hist_print(out, n, "native", &opairshare_metric_native_hist);
	n = opairshare_metric_hist_print(out, n, "wmi_prepare", &opairshare_metric_wmi_hist);
	n = opairshare_metric_hist_print(out, n, "event_callback", &opairshare_metric_event_hist);
	n = opairshare_metric_hist_print(out, n, "total", &opairshare_metric_total_hist);
	spin_unlock_irqrestore(&opairshare_metric_lock, flags);
	return n;
}
static const struct kernel_param_ops opairshare_metric_ops = { .get = opairshare_metric_get };
module_param_cb(opairshare_tx_metrics, &opairshare_metric_ops, NULL, 0400);
MODULE_PARM_DESC(opairshare_tx_metrics,
	"Lifetime observation; native_live includes submit calls; latency histograms are AWDL unicast only");
