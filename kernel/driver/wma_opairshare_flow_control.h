/* SPDX-License-Identifier: GPL-2.0-only */
/* Included after channel state. All calls except the getter hold bridge_lock. */
int opairshare_pas_flow_queue_control(struct net_device *dev, unsigned int action,
				   unsigned int trace_id);
static bool passthru_awdl_backpressure;
module_param(passthru_awdl_backpressure, bool, 0444);
MODULE_PARM_DESC(passthru_awdl_backpressure, "Opt-in PAS completion-backed capacity32/low16; no queue growth");
static bool opairshare_flow_paused;
static struct net_device *opairshare_flow_dev;
static u64 opairshare_flow_busy, opairshare_flow_stops, opairshare_flow_wakes;
static u64 opairshare_flow_releases, opairshare_flow_errors, opairshare_flow_holds, opairshare_flow_puts;
static unsigned int passthru_flow_trace_budget, opairshare_flow_trace_used;
module_param(passthru_flow_trace_budget, uint, 0644);

static int opairshare_flow_control_locked(struct net_device *dev, unsigned int action)
{
	unsigned int id = 0;
	int rc;
	unsigned int cap = min_t(unsigned int,
		READ_ONCE(passthru_flow_trace_budget), 64);
	if (action != 3 && opairshare_flow_trace_used < cap)
		id = ++opairshare_flow_trace_used;
	if (id)
		pr_info("OPAIRSHAREFLOW: id=%u enter action=%u pending=%lld accepting=%u busy=%u fault=%u target_fault=%u cookie=%llu\n",
			id, action, (long long)atomic64_read(&opairshare_bridge_pending),
			opairshare_bridge_accepting, opairshare_channel_busy, opairshare_channel_fault,
			READ_ONCE(opairshare_target_fault), (unsigned long long)opairshare_channel_cookie);
	rc = opairshare_pas_flow_queue_control(dev, action, id);
	if (id)
		pr_info("OPAIRSHAREFLOW: id=%u exit action=%u result=%d\n", id, action, rc);
	return rc;
}

static int opairshare_flow_bind_locked(struct net_device *dev)
{
	if (!passthru_awdl_backpressure)
		return 0;
	if (opairshare_flow_dev)
		return opairshare_flow_dev == dev ? 0 : -EXDEV;
	if (opairshare_flow_control_locked(dev, 3) < 0) {
		opairshare_flow_errors++;
		return -EINVAL;
	}
	dev_hold(dev);
	opairshare_flow_dev = dev;
	opairshare_flow_holds++;
	return 0;
}

static bool opairshare_flow_stop_locked(void)
{
	if (!passthru_awdl_backpressure || !opairshare_flow_dev)
		return false;
	if (opairshare_flow_paused)
		return true;
	if (opairshare_flow_control_locked(opairshare_flow_dev, 0) < 0) {
		opairshare_flow_errors++;
		return false;
	}
	opairshare_flow_paused = true;
	opairshare_flow_stops++;
	return true;
}

static void opairshare_flow_maybe_wake_locked(void)
{
	int woke;
	if (!opairshare_flow_paused || !opairshare_flow_dev || !opairshare_bridge_wq ||
	    !opairshare_bridge_accepting || opairshare_channel_fault || opairshare_channel_busy ||
	    READ_ONCE(opairshare_target_fault) ||
	    atomic64_read(&opairshare_bridge_pending) > READ_ONCE(passthru_awdl_lowmark))
		return;
	woke = opairshare_flow_control_locked(opairshare_flow_dev, 1);
	if (woke < 0) {
		opairshare_flow_errors++;
		return;
	}
	/* Releasing our pause can legitimately leave another owner's queues off. */
	opairshare_flow_paused = false;
	opairshare_flow_releases++;
	if (woke)
		opairshare_flow_wakes++;
}

static int opairshare_flow_forget_locked(void)
{
	if (!opairshare_flow_dev)
		return 0;
	if (opairshare_bridge_accepting || atomic64_read(&opairshare_bridge_pending))
		return -EBUSY;
	/* Normal Stop has drained all native callbacks. Never wake during teardown. */
	if (opairshare_flow_control_locked(opairshare_flow_dev, 2) < 0) {
		opairshare_flow_errors++;
		return -EIO;
	}
	opairshare_flow_paused = false;
	dev_put(opairshare_flow_dev);
	opairshare_flow_dev = NULL;
	opairshare_flow_puts++;
	return 0;
}

static int opairshare_flow_get(char *out, const struct kernel_param *kp)
{
	unsigned long flags;
	int n;
	(void)kp;
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	n = scnprintf(out, 256,
		"enabled=%u paused=%u held=%u pending=%lld busy=%llu stops=%llu wakes=%llu releases=%llu errors=%llu holds=%llu puts=%llu high=%u low=%u\n",
		passthru_awdl_backpressure, opairshare_flow_paused, !!opairshare_flow_dev,
		(long long)atomic64_read(&opairshare_bridge_pending),
		(unsigned long long)opairshare_flow_busy, (unsigned long long)opairshare_flow_stops,
		(unsigned long long)opairshare_flow_wakes, (unsigned long long)opairshare_flow_releases,
		(unsigned long long)opairshare_flow_errors, (unsigned long long)opairshare_flow_holds,
		(unsigned long long)opairshare_flow_puts,
		READ_ONCE(passthru_awdl_capacity), READ_ONCE(passthru_awdl_lowmark));
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	return n;
}
static const struct kernel_param_ops opairshare_flow_ops = { .get = opairshare_flow_get };
module_param_cb(opairshare_awdl_flow_state, &opairshare_flow_ops, NULL, 0444);
