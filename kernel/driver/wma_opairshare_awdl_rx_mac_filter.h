/* SPDX-License-Identifier: GPL-2.0-only */
/* Single-flight fixed149 RX. Uncertain operations remain one-shot. */
static DEFINE_SPINLOCK(opairshare_rx_filter_lock);
static DECLARE_WAIT_QUEUE_HEAD(opairshare_rx_filter_wait);
static bool opairshare_rx_filter_used;
static bool opairshare_rx_filter_pending, opairshare_rx_filter_active, opairshare_rx_filter_fault;
static bool opairshare_rx_filter_clear_submitted, opairshare_rx_filter_delete_acked;
static unsigned int opairshare_rx_filter_generation;
static u8 opairshare_rx_filter_id = WLAN_INVALID_VDEV_ID;
static u16 opairshare_rx_filter_freq;
static u8 opairshare_rx_filter_mac[6];
static struct wlan_objmgr_psoc *opairshare_rx_filter_psoc;
static int opairshare_rx_filter_reply_status = -1, opairshare_rx_filter_error;
static int opairshare_bridge_pause_locked(uint8_t id);


bool opairshare_awdl_rx_filter_reply(struct wlan_objmgr_psoc *psoc,
				  uint32_t id, uint32_t status)
{
	unsigned long flags;
	bool owned, poison = false;
	spin_lock_irqsave(&opairshare_rx_filter_lock, flags);
	owned = (passthru_awdl_rx_rearm ? opairshare_rx_filter_used : opairshare_rx_filter_armed) &&
		psoc == opairshare_rx_filter_psoc && id == opairshare_rx_filter_id;
	if (owned && opairshare_rx_filter_pending &&
	    (!passthru_awdl_rx_rearm || (!opairshare_rx_filter_fault &&
				       !opairshare_rx_filter_clear_submitted))) {
		opairshare_rx_filter_reply_status = status;
		opairshare_rx_filter_pending = false;
	} else if (owned && passthru_awdl_rx_rearm) {
		/* Duplicate/unsolicited replies invalidate a reusable session. */
		opairshare_rx_filter_reply_status = -EPROTO;
		opairshare_rx_filter_error = -EPROTO;
		opairshare_rx_filter_fault = true;
		opairshare_rx_filter_active = false;
		opairshare_rx_filter_pending = false;
		poison = true;
	}
	spin_unlock_irqrestore(&opairshare_rx_filter_lock, flags);
	if (poison) {
		spin_lock_irqsave(&opairshare_bridge_lock, flags);
		opairshare_bridge_accepting = false;
		opairshare_channel_fault = true;
		opairshare_channel_error = -EPROTO;
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	}
	if (owned) {
		pr_info("OPAIRSHARERXFILTER: reply vdev=%u status=%u poison=%u\n", id, status, poison);
		wake_up_all(&opairshare_rx_filter_wait);
	}
	return owned;
}

/* Called with lifecycle lock, after real TX drain, before vdev deletion. */
static int opairshare_rx_filter_clear_locked(u8 id)
{
	struct set_rx_mac_filter param = {0};
	tp_wma_handle wma = cds_get_context(QDF_MODULE_ID_WMA);
	QDF_STATUS status;
	unsigned long flags;
	if (!opairshare_rx_filter_armed || id != opairshare_rx_filter_id)
		return 0;
	if (!wma || wma->psoc != opairshare_rx_filter_psoc)
		return -EIO;
	param.vdev_id = id;
	param.freq = opairshare_rx_filter_freq;
	qdf_mem_copy(param.mac, opairshare_rx_filter_mac, 6);
	param.set = false;
	status = wmi_unified_set_mac_addr_rx_filter(wma->wmi_handle, &param);
	pr_info("OPAIRSHARERXFILTER: clear-submit vdev=%u freq=%u status=%u\n",
		id, param.freq, status);
	if (QDF_IS_STATUS_ERROR(status))
		return -EIO;
	/* Native clear is submit-only: never claim a delete acknowledgement or
	 * resume this session. Stop still performs the original vdev teardown.
	 */
	spin_lock_irqsave(&opairshare_rx_filter_lock, flags);
	opairshare_rx_filter_clear_submitted = true;
	opairshare_rx_filter_active = false;
	opairshare_rx_filter_armed = false;
	opairshare_rx_filter_pending = false;
	spin_unlock_irqrestore(&opairshare_rx_filter_lock, flags);
	return 0;
}

static int opairshare_rx_filter_state_get(char *out, const struct kernel_param *kp)
{
	unsigned long flags;
	int n;
	(void)kp;
	spin_lock_irqsave(&opairshare_rx_filter_lock, flags);
	n = scnprintf(out, 256,
		"used=%u armed=%u pending=%u active=%u fault=%u clear_submitted=%u vdev=%u freq=%u reply=%d error=%d delete_ack=%u generation=%u\n",
		opairshare_rx_filter_used, opairshare_rx_filter_armed, opairshare_rx_filter_pending,
		opairshare_rx_filter_active, opairshare_rx_filter_fault,
		opairshare_rx_filter_clear_submitted, opairshare_rx_filter_id,
		opairshare_rx_filter_freq, opairshare_rx_filter_reply_status, opairshare_rx_filter_error,
		opairshare_rx_filter_delete_acked, opairshare_rx_filter_generation);
	spin_unlock_irqrestore(&opairshare_rx_filter_lock, flags);
	return n;
}

bool opairshare_pas_native_rx_enabled(void);

static int opairshare_rx_filter_set(const char *value, const struct kernel_param *kp)
{
	tp_wma_handle wma;
	struct wlan_objmgr_vdev *vdev;
	struct set_rx_mac_filter param = {0};
	QDF_STATUS status;
	unsigned long flags;
	bool enable, resume = false;
	struct kernel_param boolean_param = { .arg = &enable };
	u8 id;
	int rc, reply;
	(void)kp;
	rc = param_ops_bool.set(value, &boolean_param);
	if (rc)
		return rc;
	mutex_lock(&opairshare_bridge_lifecycle);
	id = opairshare_bridge_id;
	if (!enable) {
		if (!opairshare_rx_filter_armed) {
			rc = 0;
			goto unlock;
		}
		rc = opairshare_bridge_pause_locked(id);
		if (!rc)
			rc = opairshare_rx_filter_clear_locked(id);
		/* Rebuild even when clear submission succeeds. */
		spin_lock_irqsave(&opairshare_bridge_lock, flags);
		opairshare_channel_fault = true;
		opairshare_channel_error = rc ? rc : -ESHUTDOWN;
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		goto unlock;
	}
	if (opairshare_rx_filter_used) {
		rc = -EALREADY;
		goto unlock;
	}
	wma = cds_get_context(QDF_MODULE_ID_WMA);
	if (!passthru_mgmt_bridge || !opairshare_bridge_wq || !wma ||
	    id >= wma->max_bssid || wma->interfaces[id].type != WMI_VDEV_TYPE_NDI ||
	    !(vdev = wma->interfaces[id].vdev) ||
	    wlan_vdev_mlme_get_opmode(vdev) != QDF_PASSTHRU_MODE ||
	    opairshare_bridge_freq != 5745 || !opairshare_bridge_accepting ||
	    opairshare_channel_busy || opairshare_channel_fault || READ_ONCE(opairshare_target_fault) ||
	    (((passthru_awdl_target_mac && *passthru_awdl_target_mac) || passthru_awdl_target_auto) &&
	     !opairshare_pas_native_rx_enabled()) ||
	    !is_valid_ether_addr(wlan_vdev_mlme_get_macaddr(vdev))) {
		rc = -EOPNOTSUPP;
		goto unlock;
	}
	rc = opairshare_bridge_pause_locked(id);
	if (rc) {
		spin_lock_irqsave(&opairshare_bridge_lock, flags);
		opairshare_channel_fault = true;
		opairshare_channel_error = rc;
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
		goto unlock;
	}
	param.vdev_id = id;
	param.freq = opairshare_bridge_freq;
	qdf_mem_copy(param.mac, wlan_vdev_mlme_get_macaddr(vdev), 6);
	param.set = true;
	spin_lock_irqsave(&opairshare_rx_filter_lock, flags);
	opairshare_rx_filter_used = opairshare_rx_filter_armed = opairshare_rx_filter_pending = true;
	opairshare_rx_filter_psoc = wma->psoc;
	opairshare_rx_filter_id = id;
	opairshare_rx_filter_freq = param.freq;
	qdf_mem_copy(opairshare_rx_filter_mac, param.mac, 6);
	spin_unlock_irqrestore(&opairshare_rx_filter_lock, flags);
	status = wmi_unified_set_mac_addr_rx_filter(wma->wmi_handle, &param);
	pr_info("OPAIRSHARERXFILTER: set-submit vdev=%u freq=%u mac=%pM status=%u\n",
		id, param.freq, param.mac, status);
	if (QDF_IS_STATUS_ERROR(status)) {
		rc = -EIO;
		resume = true; /* Native send failure frees the unsent WMI buffer. */
	} else if (!wait_event_timeout(opairshare_rx_filter_wait,
				      !READ_ONCE(opairshare_rx_filter_pending),
				      msecs_to_jiffies(2000))) {
		rc = -ETIMEDOUT;
	} else {
		spin_lock_irqsave(&opairshare_rx_filter_lock, flags);
		reply = opairshare_rx_filter_reply_status;
		spin_unlock_irqrestore(&opairshare_rx_filter_lock, flags);
		/* This event uses 1=success, 0=rejected. */
		rc = reply == 1 ? 0 : (reply == 0 ? -ECANCELED : -EPROTO);
		resume = reply == 1 || reply == 0;
	}
	spin_lock_irqsave(&opairshare_rx_filter_lock, flags);
	if (passthru_awdl_rx_rearm && opairshare_rx_filter_fault) {
		rc = opairshare_rx_filter_error ? opairshare_rx_filter_error : -EPROTO;
		resume = false;
	}
	opairshare_rx_filter_error = rc;
	opairshare_rx_filter_active = !rc;
	opairshare_rx_filter_fault = !resume;
	if (resume && rc) {
		opairshare_rx_filter_armed = false;
		opairshare_rx_filter_pending = false;
	}
	spin_unlock_irqrestore(&opairshare_rx_filter_lock, flags);
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	if (resume && !READ_ONCE(opairshare_target_fault) &&
	    !atomic64_read(&opairshare_bridge_pending)) {
		opairshare_bridge_accepting = true;
		opairshare_flow_maybe_wake_locked();
	} else {
		opairshare_channel_fault = true;
		opairshare_channel_error = rc ? rc : -EIO;
	}
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
unlock:
	mutex_unlock(&opairshare_bridge_lifecycle);
	return rc;
}

static const struct kernel_param_ops opairshare_rx_filter_ops = {
	.set = opairshare_rx_filter_set, .get = opairshare_rx_filter_state_get,
};
module_param_cb(opairshare_awdl_rx_mac_filter, &opairshare_rx_filter_ops, NULL, 0600);
MODULE_PARM_DESC(opairshare_awdl_rx_mac_filter,
	"Single-flight native RX MAC match; fixed149; uncertain state quarantines");

/* Real firmware handler and timeout handler call distinct branches. Neither
 * takes lifecycle mutex: HDD can hold it while waiting for firmware events.
 */
void opairshare_pas_rx_vdev_event(struct wlan_objmgr_psoc *psoc, u8 id,
			       bool deleted)
{
	unsigned long flags;
	bool owned, poison = false;
	if (!passthru_awdl_rx_rearm)
		return;
	spin_lock_irqsave(&opairshare_rx_filter_lock, flags);
	owned = opairshare_rx_filter_used && psoc == opairshare_rx_filter_psoc &&
		id == opairshare_rx_filter_id;
	if (owned) {
		if (deleted && opairshare_rx_filter_clear_submitted &&
		    !opairshare_rx_filter_armed && !opairshare_rx_filter_pending &&
		    !opairshare_rx_filter_active && !opairshare_rx_filter_fault &&
		    !opairshare_rx_filter_error && opairshare_rx_filter_reply_status == 1)
			opairshare_rx_filter_delete_acked = true;
		else {
			opairshare_rx_filter_fault = true;
			opairshare_rx_filter_error = deleted ? -EPROTO : -ETIMEDOUT;
			opairshare_rx_filter_active = false;
			poison = true;
		}
	}
	spin_unlock_irqrestore(&opairshare_rx_filter_lock, flags);
	if (poison) {
		spin_lock_irqsave(&opairshare_bridge_lock, flags);
		opairshare_bridge_accepting = false;
		opairshare_channel_fault = true;
		opairshare_channel_error = deleted ? -EPROTO : -ETIMEDOUT;
		spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	}
	if (owned)
		pr_info("OPAIRSHARERXSESSION: vdev=%u real_delete=%u poison=%u\n", id, deleted, poison);
}

/* Normal deinit only, after actual adapter destruction and OSIF cleanup.
 * Native API sends one response per set and none for clear. Never retry an
 * uncertain set, accept a timeout-synthesized delete, or reset on init failure.
 */
int opairshare_pas_rx_session_retire(u8 id)
{
	unsigned long flags;
	bool drained;
	int rc = 0;
	if (!passthru_awdl_rx_rearm)
		return 0;
	mutex_lock(&opairshare_bridge_lifecycle);
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	drained = !opairshare_bridge_wq && opairshare_bridge_id == WLAN_INVALID_VDEV_ID &&
		!opairshare_bridge_freq && !opairshare_bridge_accepting && !opairshare_channel_busy &&
		!opairshare_channel_fault && !READ_ONCE(opairshare_target_fault) &&
		!atomic64_read(&opairshare_bridge_pending);
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	spin_lock_irqsave(&opairshare_rx_filter_lock, flags);
	if (opairshare_rx_filter_used) {
		if (id != opairshare_rx_filter_id || !drained || !opairshare_rx_filter_delete_acked ||
		    !opairshare_rx_filter_clear_submitted || opairshare_rx_filter_armed ||
		    opairshare_rx_filter_pending || opairshare_rx_filter_active ||
		    opairshare_rx_filter_fault || opairshare_rx_filter_error ||
		    opairshare_rx_filter_reply_status != 1) {
			rc = -EIO;
			opairshare_rx_filter_fault = true;
			opairshare_rx_filter_error = rc;
		} else {
			opairshare_rx_filter_used = false;
			opairshare_rx_filter_clear_submitted = false;
			opairshare_rx_filter_delete_acked = false;
			opairshare_rx_filter_id = WLAN_INVALID_VDEV_ID;
			opairshare_rx_filter_freq = 0;
			opairshare_rx_filter_psoc = NULL;
			opairshare_rx_filter_reply_status = -1;
			qdf_mem_zero(opairshare_rx_filter_mac, sizeof(opairshare_rx_filter_mac));
			opairshare_rx_filter_generation++;
		}
	}
	spin_unlock_irqrestore(&opairshare_rx_filter_lock, flags);
	mutex_unlock(&opairshare_bridge_lifecycle);
	pr_info("OPAIRSHARERXSESSION: retire vdev=%u rc=%d generation=%u used=%u\n",
		id, rc, opairshare_rx_filter_generation, opairshare_rx_filter_used);
	return rc;
}

