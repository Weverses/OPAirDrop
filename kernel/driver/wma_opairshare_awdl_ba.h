/* SPDX-License-Identifier: GPL-2.0-only */
/* Included at the end of wma_opairshare_pas_mgmt.h. BA work shares the bridge's
 * ordered queue and pending count, so the existing stop drains it too. */
static bool passthru_awdl_addba;
module_param(passthru_awdl_addba, bool, 0644);
MODULE_PARM_DESC(passthru_awdl_addba, "Answer AWDL ADDBA using native CDP RX BA setup");
static unsigned int passthru_awdl_ba_window = 64;
module_param(passthru_awdl_ba_window, uint, 0644);
static atomic64_t opairshare_ba_requests = ATOMIC64_INIT(0);
static atomic64_t opairshare_ba_prepared = ATOMIC64_INIT(0);
static atomic64_t opairshare_ba_tx_ok = ATOMIC64_INIT(0);
static atomic64_t opairshare_ba_tx_failed = ATOMIC64_INIT(0);
static atomic64_t opairshare_ba_setup_failed = ATOMIC64_INIT(0);
static atomic64_t opairshare_ba_delba = ATOMIC64_INIT(0);
static int opairshare_ba_stats_get(char *out, const struct kernel_param *kp)
{
	(void)kp;
	return scnprintf(out, 256,
		"enabled=%u window=%u requests=%lld prepared=%lld tx_ok=%lld tx_failed=%lld setup_failed=%lld delba=%lld\n",
		READ_ONCE(passthru_awdl_addba), READ_ONCE(passthru_awdl_ba_window),
		(long long)atomic64_read(&opairshare_ba_requests),
		(long long)atomic64_read(&opairshare_ba_prepared),
		(long long)atomic64_read(&opairshare_ba_tx_ok),
		(long long)atomic64_read(&opairshare_ba_tx_failed),
		(long long)atomic64_read(&opairshare_ba_setup_failed),
		(long long)atomic64_read(&opairshare_ba_delba));
}
static const struct kernel_param_ops opairshare_ba_stats_ops = { .get = opairshare_ba_stats_get };
module_param_cb(opairshare_ba_stats, &opairshare_ba_stats_ops, NULL, 0444);

struct opairshare_ba_event {
	struct work_struct work;
	struct work_struct native_work;
	struct wlan_objmgr_vdev *vdev;
	u8 frame[33];
	u16 freq;
	u8 tid;
	bool prepared;
};
void opairshare_pas_rx_order_start(const u8 *peer, u16 ssn);
void opairshare_pas_rx_order_end(void);

static void opairshare_ba_release(struct opairshare_ba_event *e)
{
	wlan_objmgr_vdev_release_ref(e->vdev, WLAN_MGMT_NB_ID);
	kfree(e);
	opairshare_bridge_done();
}

#include "wma_opairshare_native_rx_work.h"

static void opairshare_ba_finish(struct opairshare_ba_event *e, int status)
{
	int rc = 0;
	if (e->prepared)
		rc = cdp_addba_resp_tx_completion(cds_get_context(QDF_MODULE_ID_SOC),
			e->frame + 10, wlan_vdev_get_id(e->vdev), e->tid, status);
	if (!status && !rc)
		atomic64_inc(&opairshare_ba_tx_ok);
	else
		atomic64_inc(&opairshare_ba_tx_failed);
	if (e->prepared && !status && !rc && e->tid == 0)
		opairshare_pas_rx_order_start(e->frame + 10,
			(e->frame[31] | (e->frame[32] << 8)) >> 4);
	pr_info("OPAIRSHAREBA: complete peer=%pM tid=%u status=%d dp=%d\n",
		e->frame + 10, e->tid, status, rc);
	if (e->prepared && !status && !rc && opairshare_native_rx_queue(e))
		return;
	opairshare_ba_release(e);
}

static QDF_STATUS opairshare_ba_complete(void *context, qdf_nbuf_t buf,
				  uint32_t status, void *params)
{
	struct opairshare_ba_event *e = context;
	(void)params;
	cds_packet_free(buf);
	opairshare_ba_finish(e, status);
	return QDF_STATUS_SUCCESS;
}

static void opairshare_ba_worker(struct work_struct *work)
{
	struct opairshare_ba_event *e = container_of(work, struct opairshare_ba_event, work);
	tp_wma_handle wma = cds_get_context(QDF_MODULE_ID_WMA);
	void *soc = cds_get_context(QDF_MODULE_ID_SOC);
	struct wlan_objmgr_peer *self;
	struct wmi_mgmt_params mgmt = {0};
	u8 *f = e->frame, *data, token;
	u8 id = wlan_vdev_get_id(e->vdev);
	u16 param, window, ssn, timeout, code;
	qdf_nbuf_t buf;
	int rc;

	if (!READ_ONCE(opairshare_bridge_accepting) || !wma || !soc ||
	    !READ_ONCE(passthru_awdl_addba))
		goto out;
	if (f[25] == 2) {
		param = f[26] | (f[27] << 8);
		e->tid = param >> 12;
		/* Initiator=1: the sender is tearing down our receive agreement. */
		if ((param & BIT(11)) && opairshare_awdl_target_match(id, f + 10)) {
			if (e->tid == 0)
				opairshare_pas_rx_order_end();
			rc = cdp_delba_process(soc, f + 10, id, e->tid,
				f[28] | (f[29] << 8));
			atomic64_inc(&opairshare_ba_delba);
			pr_info("OPAIRSHAREBA: delba peer=%pM tid=%u rc=%d\n", f + 10, e->tid, rc);
		}
		goto out;
	}
	param = f[27] | (f[28] << 8);
	e->tid = (param >> 2) & 15;
	opairshare_target_reserve(wma, e->vdev, id, f + 10);
	rc = -ENOENT;
	if (!opairshare_awdl_target_match(id, f + 10))
		goto failed;
	rc = opairshare_target_ensure(wma, e->vdev, id, f + 10);
	if (rc)
		goto failed;
	window = min_t(unsigned int, READ_ONCE(passthru_awdl_ba_window), 64);
	window = max_t(unsigned int, window, 1);
	if (param >> 6)
		window = min_t(u16, window, param >> 6);
	ssn = (f[31] | (f[32] << 8)) >> 4;
	timeout = f[29] | (f[30] << 8);
	rc = cdp_addba_requestprocess(soc, f + 10, id, f[26], e->tid,
		timeout, window, ssn);
	if (rc)
		goto failed;
	e->prepared = true;
	rc = cdp_addba_responsesetup(soc, f + 10, id, e->tid, &token,
		&code, &window, &timeout);
	if (rc || code)
		goto abort;
	atomic64_inc(&opairshare_ba_prepared);
	rc = cds_packet_alloc(64, (void **)&data, (void **)&buf);
	if (rc)
		goto abort;
	memset(data, 0, 33);
	data[0] = 0xd0;
	memcpy(data + 4, f + 10, 6);
	memcpy(data + 10, f + 4, 6);
	memcpy(data + 16, f + 16, 6);
	data[24] = 3;
	data[25] = 1;
	data[26] = token;
	/* Immediate BA; A-MSDU-in-A-MPDU deliberately disabled for this test. */
	param = (window << 6) | (e->tid << 2) | BIT(1);
	data[29] = param;
	data[30] = param >> 8;
	data[31] = timeout;
	data[32] = timeout >> 8;
	self = wlan_objmgr_get_peer(wma->psoc,
		wlan_objmgr_pdev_get_pdev_id(wma->pdev),
		wlan_vdev_mlme_get_macaddr(e->vdev), WLAN_MGMT_NB_ID);
	if (!self) {
		cds_packet_free(buf);
		goto abort;
	}
	mgmt.tx_frame = buf;
	mgmt.frm_len = 33;
	mgmt.vdev_id = id;
	mgmt.pdata = data;
	mgmt.chanfreq = READ_ONCE(passthru_mgmt_home_channel) ? 0 : e->freq;
	mgmt.tx_params_valid = true;
	wma_update_tx_send_params(&mgmt.tx_param, RATEID_6MBPS);
	mgmt.qdf_ctx = cds_get_context(QDF_MODULE_ID_QDF_DEVICE);
	mgmt.use_6mbps = true;
	mgmt.tx_type = GENERIC_NODOWLOAD_ACK_COMP_INDEX;
	pr_info("OPAIRSHAREBA: response peer=%pM token=%u tid=%u window=%u ssn=%u\n",
		f + 10, token, e->tid, window, ssn);
	/* The management framework stores e as the completion context. On
	 * submission failure it returns the buffer to us without a callback. */
	rc = wlan_mgmt_txrx_mgmt_frame_tx(self, e, buf, NULL, opairshare_ba_complete,
		WLAN_UMAC_COMP_MLME, &mgmt);
	wlan_objmgr_peer_release_ref(self, WLAN_MGMT_NB_ID);
	if (!rc)
		return;
	cds_packet_free(buf);
abort:
	opairshare_ba_finish(e, WMI_MGMT_TX_COMP_TYPE_DISCARD);
	return;
failed:
	atomic64_inc(&opairshare_ba_setup_failed);
	pr_info("OPAIRSHAREBA: setup_failed peer=%pM tid=%u rc=%d\n", f + 10, e->tid, rc);
out:
	opairshare_ba_release(e);
}

/* IRQ-safe, borrowed skb. Native monitor and WMI RX may both observe an
 * action; CDP serializes requests while the response is in progress. */
void opairshare_pas_ba_rx(struct sk_buff *skb, u16 rtlen)
{
	struct opairshare_ba_event *e;
	tp_wma_handle wma;
	struct wlan_objmgr_vdev *vdev;
	u8 f[33] = {0}, id;
	u16 fc, param;
	unsigned long flags;
	unsigned int len = skb->len - rtlen;
	if (!READ_ONCE(passthru_awdl_addba) || len < 30 ||
	    skb_copy_bits(skb, rtlen, f, min_t(unsigned int, len, sizeof(f))))
		return;
	fc = f[0] | (f[1] << 8);
	if ((fc & ~IEEE80211_FCTL_RETRY) != IEEE80211_STYPE_ACTION || f[24] != 3 ||
	    (f[25] != 0 && f[25] != 2) || (f[4] & 1) || !is_valid_ether_addr(f + 10))
		return;
	if (f[25] == 0) {
		param = f[27] | (f[28] << 8);
		if (len < 33 || !(param & BIT(1)) || ((param >> 2) & 15) > 7)
			return;
	}
	wma = cds_get_context(QDF_MODULE_ID_WMA);
	if (!wma)
		return;
	e = kzalloc(sizeof(*e), GFP_ATOMIC);
	if (!e)
		return;
	spin_lock_irqsave(&opairshare_bridge_lock, flags);
	id = opairshare_bridge_id;
	if (!opairshare_bridge_accepting || !opairshare_bridge_wq || id >= wma->max_bssid ||
	    atomic64_read(&opairshare_bridge_pending) >= READ_ONCE(passthru_awdl_capacity))
		goto unlock;
	vdev = wma->interfaces[id].vdev;
	if (!vdev || !ether_addr_equal(f + 4, wlan_vdev_mlme_get_macaddr(vdev)) ||
	    QDF_IS_STATUS_ERROR(wlan_objmgr_vdev_try_get_ref(vdev, WLAN_MGMT_NB_ID)))
		goto unlock;
	if (!try_module_get(THIS_MODULE)) {
		wlan_objmgr_vdev_release_ref(vdev, WLAN_MGMT_NB_ID);
		goto unlock;
	}
	e->vdev = vdev;
	e->freq = opairshare_bridge_freq;
	memcpy(e->frame, f, sizeof(f));
	INIT_WORK(&e->work, opairshare_ba_worker);
	atomic64_inc(&opairshare_bridge_pending);
	if (!f[25])
		atomic64_inc(&opairshare_ba_requests);
	queue_work(opairshare_bridge_wq, &e->work);
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	return;
unlock:
	spin_unlock_irqrestore(&opairshare_bridge_lock, flags);
	kfree(e);
}
