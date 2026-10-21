/* Included after bridge/target state; all state uses the existing bridge lock. */
static u64 opairshare_channel_cookie;
static bool opairshare_channel_busy, opairshare_channel_fault;
static int opairshare_channel_error;
static int opairshare_bridge_pause_locked(uint8_t id);

/* Caller holds lifecycle and bridge locks. Invalidate late completions. */
static void opairshare_channel_reset(void)
{
	if (!++opairshare_channel_cookie)
		++opairshare_channel_cookie;
	opairshare_channel_busy = false;
	opairshare_channel_fault = false;
	opairshare_channel_error = 0;
}

static int opairshare_channel_get(char *out, const struct kernel_param *kp)
{
	unsigned long flags;
	int n;
	(void)kp;
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	n = scnprintf(out, 256,
		"cookie=%llu busy=%u fault=%u error=%d accepting=%u vdev=%u freq=%u\n",
		(unsigned long long)opairshare_channel_cookie, opairshare_channel_busy,
		opairshare_channel_fault, opairshare_channel_error, opairshare_bridge_accepting,
		opairshare_bridge_id, opairshare_bridge_freq);
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	return n;
}
static const struct kernel_param_ops opairshare_channel_ops = { .get = opairshare_channel_get };
module_param_cb(opairshare_channel_state, &opairshare_channel_ops, NULL, 0444);

int opairshare_pas_channel_begin(uint8_t id, u64 *cookie)
{
	unsigned long flags;
	int rc = 0;
	*cookie = 0;
	if (!passthru_mgmt_bridge)
		return 0;
	mutex_lock(&opairshare_bridge_lifecycle);
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	/* Initial channel setup precedes bridge start. */
	if (!opairshare_bridge_wq || opairshare_bridge_id != id)
		goto unlock;
	if (opairshare_channel_fault || READ_ONCE(opairshare_target_fault)) {
		rc = -EIO;
		goto unlock;
	}
	/* This one-shot diagnostic owns a fixed-frequency RX entry. */
	if (READ_ONCE(opairshare_rx_filter_armed)) {
		rc = -EBUSY;
		goto unlock;
	}
	if (opairshare_channel_busy) {
		rc = -EBUSY;
		goto unlock;
	}
	if (!++opairshare_channel_cookie)
		++opairshare_channel_cookie;
	*cookie = opairshare_channel_cookie;
	opairshare_channel_busy = true;
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	rc = opairshare_bridge_pause_locked(id);
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (rc) {
		opairshare_channel_busy = false;
		opairshare_channel_fault = true;
		opairshare_channel_error = rc;
	}
unlock:
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	mutex_unlock(&opairshare_bridge_lifecycle);
	return rc;
}

int opairshare_pas_channel_end(uint8_t id, u64 cookie, uint16_t freq, int error)
{
	unsigned long flags;
	int rc = error;
	if (!cookie)
		return error;
	mutex_lock(&opairshare_bridge_lifecycle);
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (!opairshare_bridge_wq || opairshare_bridge_id != id ||
	    cookie != opairshare_channel_cookie || !opairshare_channel_busy) {
		rc = -ESTALE;
		goto unlock;
	}
	if (!rc && (READ_ONCE(opairshare_target_fault) ||
		    atomic64_read(&opairshare_bridge_pending)))
		rc = -EIO;
	opairshare_channel_busy = false;
	opairshare_channel_error = rc;
	if (rc) {
		opairshare_channel_fault = true;
		opairshare_bridge_accepting = false;
	} else {
		opairshare_bridge_freq = freq;
		opairshare_bridge_accepting = true;
		opairshare_flow_maybe_wake_locked();
	}
unlock:
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	mutex_unlock(&opairshare_bridge_lifecycle);
	pr_info("OPAIRSHARECHANNELEND: vdev=%u cookie=%llu freq=%u error=%d\n",
		id, (unsigned long long)cookie, freq, rc);
	return rc;
}
