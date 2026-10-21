/* SPDX-License-Identifier: GPL-2.0-only */
/* BA events retain their vdev/module reference and bridge pending slot until
 * this ordered work completes. Stop drains the bridge BEFORE aux teardown.
 */
static bool passthru_awdl_native_rx;
module_param(passthru_awdl_native_rx, bool, 0644);
MODULE_PARM_DESC(passthru_awdl_native_rx,
	"Restore native HOST_BUF RX after successful AWDL BA activation");
int opairshare_pas_native_ring_activate(void);
static atomic64_t opairshare_native_rx_queued = ATOMIC64_INIT(0);
static atomic64_t opairshare_native_rx_applied = ATOMIC64_INIT(0);
static atomic64_t opairshare_native_rx_failed = ATOMIC64_INIT(0);
static atomic64_t opairshare_native_rx_aborted = ATOMIC64_INIT(0);

static void opairshare_native_rx_worker(struct work_struct *work)
{
	struct opairshare_ba_event *e = container_of(work, struct opairshare_ba_event,
					    native_work);
	int ret;
	if (!READ_ONCE(passthru_awdl_native_rx) ||
	    !READ_ONCE(opairshare_bridge_accepting) ||
	    !opairshare_awdl_target_match(wlan_vdev_get_id(e->vdev), e->frame + 10)) {
		atomic64_inc(&opairshare_native_rx_aborted);
		goto out;
	}
	/* No RTNL acquisition here: Stop can own RTNL while draining us. */
	ret = opairshare_pas_native_ring_activate();
	atomic64_inc(ret ? &opairshare_native_rx_failed : &opairshare_native_rx_applied);
	pr_info("OPAIRSHARENATIVERX: peer=%pM vdev=%u rc=%d\n", e->frame + 10,
		wlan_vdev_get_id(e->vdev), ret);
out:
	opairshare_ba_release(e);
}

static bool opairshare_native_rx_queue(struct opairshare_ba_event *e)
{
	unsigned long flags;
	bool queued = false;
	if (!READ_ONCE(passthru_awdl_native_rx) || e->tid != 0)
		return false;
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (opairshare_bridge_accepting && opairshare_bridge_wq &&
	    opairshare_bridge_id == wlan_vdev_get_id(e->vdev)) {
		INIT_WORK(&e->native_work, opairshare_native_rx_worker);
		queued = queue_work(opairshare_bridge_wq, &e->native_work);
		if (queued)
			atomic64_inc(&opairshare_native_rx_queued);
	}
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	return queued;
}

static int opairshare_native_rx_get(char *out, const struct kernel_param *kp)
{
	(void)kp;
	return scnprintf(out, 256,
		"enabled=%u queued=%lld applied=%lld failed=%lld aborted=%lld\n",
		READ_ONCE(passthru_awdl_native_rx),
		(long long)atomic64_read(&opairshare_native_rx_queued),
		(long long)atomic64_read(&opairshare_native_rx_applied),
		(long long)atomic64_read(&opairshare_native_rx_failed),
		(long long)atomic64_read(&opairshare_native_rx_aborted));
}
static const struct kernel_param_ops opairshare_native_rx_ops = { .get = opairshare_native_rx_get };
module_param_cb(opairshare_native_rx_stats, &opairshare_native_rx_ops, NULL, 0444);
