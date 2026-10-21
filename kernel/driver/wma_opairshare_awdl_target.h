/* SPDX-License-Identifier: GPL-2.0-only */
/* Create runs in the ordered bridge worker; stop drains it before delete. */
#include <linux/etherdevice.h>

static char *passthru_awdl_target_mac;
module_param(passthru_awdl_target_mac, charp, 0444);
/* Diagnostic: override the target peer type created by opairshare_target_ensure().
 * -1 = keep WMI_PEER_TYPE_NAN_DATA (current behaviour).
 */
static int passthru_awdl_target_peer_type = -1;
module_param(passthru_awdl_target_peer_type, int, 0644);
MODULE_PARM_DESC(passthru_awdl_target_peer_type,
	"Diagnostic: peer type for the AWDL target (-1 = NAN_DATA)");
MODULE_PARM_DESC(passthru_awdl_target_mac,
	"Optional single diagnostic NDI target MAC; fixed at module load");
/* Diagnostic: 0=legacy CONN, 1=host AUTH, 2=host+native FW authorize. */
static unsigned int passthru_awdl_target_auth;
bool opairshare_pas_native_rx_enabled(void);
static bool passthru_awdl_raw_rx;
module_param(passthru_awdl_raw_rx, bool, 0444);
MODULE_PARM_DESC(passthru_awdl_raw_rx,
	"Opt-in per-PAS-vdev firmware RAW RX before diagnostic target creation");
static bool passthru_awdl_target_enable;
module_param(passthru_awdl_target_auth, uint, 0444);
MODULE_PARM_DESC(passthru_awdl_target_auth, "Target authorization diagnostic 0..2; not association emulation");
/* Learn the target MAC from the first unicast TCP frame instead of a load-time MAC. */
static bool passthru_awdl_target_auto;
module_param(passthru_awdl_target_auto, bool, 0444);
MODULE_PARM_DESC(passthru_awdl_target_auto,
	"With an empty passthru_awdl_target_mac, learn up to eight owned NDI peers from unicast TCP or directed ADDBA per radio session");
static u8 opairshare_target_id = WLAN_INVALID_VDEV_ID;
static bool opairshare_target_fault, opairshare_target_armed;
/* Discovery and an actual upload can use different peers concurrently.
 * The ordered bridge worker reserves/creates; IRQ callbacks only update
 * the matching slot. Slots remain stable until normal Stop drains work. */
#define OPAIRSHARE_TARGET_SLOTS 8
struct opairshare_target_peer {
    u8 mac[ETH_ALEN];
    bool used, created, mapped, deleting, unmapped, delete_response;
    u16 peer_id;
    int auth_error;
};
static struct opairshare_target_peer opairshare_targets[OPAIRSHARE_TARGET_SLOTS];
static struct opairshare_target_peer *opairshare_target_find(const u8 *mac)
{
    unsigned int i;
    for (i = 0; i < OPAIRSHARE_TARGET_SLOTS; i++)
        if (opairshare_targets[i].used && ether_addr_equal(opairshare_targets[i].mac, mac))
            return &opairshare_targets[i];
    return NULL;
}
static DEFINE_SPINLOCK(opairshare_target_lock);
static DECLARE_WAIT_QUEUE_HEAD(opairshare_target_wait);

static int opairshare_target_state_get(char *out, const struct kernel_param *kp)
{
    unsigned long flags;
    unsigned int i, count = 0, peers = 0;
    (void)kp;
    spin_lock_irqsave(&opairshare_target_lock, flags);
    for (i = 0; i < OPAIRSHARE_TARGET_SLOTS; i++) peers += opairshare_targets[i].used;
    count += scnprintf(out + count, PAGE_SIZE - count,
        "armed=%u fault=%u vdev=%u peers=%u\n",
        opairshare_target_armed, opairshare_target_fault, opairshare_target_id, peers);
    for (i = 0; i < OPAIRSHARE_TARGET_SLOTS; i++) {
        struct opairshare_target_peer *t = &opairshare_targets[i];
        if (!t->used) continue;
        count += scnprintf(out + count, PAGE_SIZE - count,
            "slot=%u mac=%pM created=%u mapped=%u deleting=%u unmapped=%u reply=%u peer=%u auth_error=%d\n",
            i, t->mac, t->created, t->mapped, t->deleting, t->unmapped,
            t->delete_response, t->peer_id, t->auth_error);
    }
    spin_unlock_irqrestore(&opairshare_target_lock, flags);
    return count;
}
static const struct kernel_param_ops opairshare_target_state_ops = { .get = opairshare_target_state_get };
module_param_cb(opairshare_awdl_target_state, &opairshare_target_state_ops, NULL, 0444);

bool opairshare_awdl_target_vdev_match(u8 id)
{
	return READ_ONCE(opairshare_target_armed) && id == READ_ONCE(opairshare_target_id);
}

bool opairshare_awdl_target_match(u8 id, const u8 *mac)
{
	unsigned long flags;
	bool match;
	spin_lock_irqsave(&opairshare_target_lock, flags);
	match = opairshare_target_armed && id == opairshare_target_id && opairshare_target_find(mac);
	spin_unlock_irqrestore(&opairshare_target_lock, flags);
	return match;
}

void opairshare_awdl_target_map(u8 id, const u8 *mac, u16 peer_id)
{
	unsigned long flags;
    struct opairshare_target_peer *t;
	bool matched = false;
	spin_lock_irqsave(&opairshare_target_lock, flags);
    t = opairshare_target_find(mac);
    if (opairshare_target_armed && id == opairshare_target_id && t && !t->deleting) {
        t->peer_id = peer_id;
        WRITE_ONCE(t->mapped, true);
        matched = true;
    }
	spin_unlock_irqrestore(&opairshare_target_lock, flags);
	if (matched) {
		pr_info("OPAIRSHARETARGET: mapped vdev=%u mac=%pM peer=%u\n", id, mac, peer_id);
		wake_up_all(&opairshare_target_wait);
	}
}

void opairshare_awdl_target_unmap(u8 id, const u8 *mac, u16 peer_id)
{
	unsigned long flags;
    struct opairshare_target_peer *t;
	bool matched = false;
	spin_lock_irqsave(&opairshare_target_lock, flags);
    t = opairshare_target_find(mac);
    if (opairshare_target_armed && id == opairshare_target_id && t && t->deleting && (!t->mapped || peer_id == t->peer_id)) {
        WRITE_ONCE(t->unmapped, true);
        matched = true;
    }
	spin_unlock_irqrestore(&opairshare_target_lock, flags);
	if (matched) {
		pr_info("OPAIRSHARETARGET: unmapped vdev=%u mac=%pM peer=%u\n", id, mac, peer_id);
		wake_up_all(&opairshare_target_wait);
	}
}

bool opairshare_awdl_target_delete_reply(u8 id, const u8 *mac)
{
	unsigned long flags;
    struct opairshare_target_peer *t;
	bool matched = false;
	spin_lock_irqsave(&opairshare_target_lock, flags);
    t = opairshare_target_find(mac);
    if (opairshare_target_armed && id == opairshare_target_id && t && t->deleting) {
        WRITE_ONCE(t->delete_response, true);
        matched = true;
    }
	spin_unlock_irqrestore(&opairshare_target_lock, flags);
	if (matched) {
		pr_info("OPAIRSHARETARGET: delete response vdev=%u mac=%pM\n", id, mac);
		wake_up_all(&opairshare_target_wait);
	}
	return matched;
}

static int opairshare_target_prepare(tp_wma_handle wma, struct wlan_objmgr_vdev *vdev, u8 id)
{
    struct wlan_objmgr_peer *existing;
    u8 mac[ETH_ALEN];
    unsigned long flags;
    unsigned int i;
    bool fixed = passthru_awdl_target_mac && *passthru_awdl_target_mac;
    if (passthru_awdl_target_auth > 2) return -EINVAL;
    if (READ_ONCE(opairshare_target_fault)) return -EIO;
    for (i = 0; i < OPAIRSHARE_TARGET_SLOTS; i++)
        if (READ_ONCE(opairshare_targets[i].created)) return -EBUSY;
    if (!fixed && !passthru_awdl_target_auto) return 0;
    if (fixed) {
        if (!mac_pton(passthru_awdl_target_mac, mac) || !is_valid_ether_addr(mac) ||
            ether_addr_equal(mac, wlan_vdev_mlme_get_macaddr(vdev))) return -EINVAL;
        existing = wlan_objmgr_get_peer_by_mac(wma->psoc, mac, WLAN_MGMT_NB_ID);
        if (existing) {
            wlan_objmgr_peer_release_ref(existing, WLAN_MGMT_NB_ID);
            return -EEXIST;
        }
    }
    spin_lock_irqsave(&opairshare_target_lock, flags);
    memset(opairshare_targets, 0, sizeof(opairshare_targets));
    if (fixed) {
        ether_addr_copy(opairshare_targets[0].mac, mac);
        opairshare_targets[0].used = true;
    }
    opairshare_target_id = id;
    opairshare_target_armed = true;
    spin_unlock_irqrestore(&opairshare_target_lock, flags);
    return 0;
}

bool opairshare_awdl_diag_target_match(const u8 *mac)
{
    unsigned long flags;
    bool match;
    spin_lock_irqsave(&opairshare_target_lock, flags);
    match = opairshare_target_armed && opairshare_target_find(mac);
    spin_unlock_irqrestore(&opairshare_target_lock, flags);
    return match;
}

/* Only called on the ordered bridge queue, from TCP TX or a directed ADDBA.
 * No eviction: live peers and their RX BA state survive nearby discovery. */
static void opairshare_target_reserve(tp_wma_handle wma, struct wlan_objmgr_vdev *vdev,
                                u8 id, const u8 *receiver)
{
    struct wlan_objmgr_peer *existing;
    struct opairshare_target_peer *t;
    unsigned long flags;
    unsigned int i;
    u8 mac[ETH_ALEN];
    if (!READ_ONCE(passthru_awdl_target_enable) || !READ_ONCE(opairshare_target_armed) ||
        READ_ONCE(opairshare_target_fault) || id != READ_ONCE(opairshare_target_id) ||
        !is_valid_ether_addr(receiver) ||
        ether_addr_equal(receiver, wlan_vdev_mlme_get_macaddr(vdev))) return;
    if (opairshare_awdl_target_match(id, receiver)) return;
    if (!passthru_awdl_target_auto ||
        (passthru_awdl_target_mac && *passthru_awdl_target_mac)) return;
    ether_addr_copy(mac, receiver);
    existing = wlan_objmgr_get_peer_by_mac(wma->psoc, mac, WLAN_MGMT_NB_ID);
    if (existing) {
        wlan_objmgr_peer_release_ref(existing, WLAN_MGMT_NB_ID);
        return;
    }
    spin_lock_irqsave(&opairshare_target_lock, flags);
    for (i = 0; i < OPAIRSHARE_TARGET_SLOTS; i++) {
        t = &opairshare_targets[i];
        if (t->used) continue;
        ether_addr_copy(t->mac, mac);
        t->used = true;
        pr_info("OPAIRSHARETARGET: learned peer %pM vdev=%u slot=%u\n", mac, id, i);
        break;
    }
    spin_unlock_irqrestore(&opairshare_target_lock, flags);
}

static int opairshare_target_enable_set(const char *value, const struct kernel_param *kp)
{
	bool enable;
	int rc = kstrtobool(value, &enable);
	if (rc)
		return rc;
	/* Enable once within a session. Disable only after normal owned teardown.
	 * The same radio can supply no-peer then peer controls without hot reload.
	 */
	if (!enable && READ_ONCE(opairshare_target_armed))
		return -EBUSY;
	WRITE_ONCE(*(bool *)kp->arg, enable);
	return 0;
}
static const struct kernel_param_ops opairshare_target_enable_ops = {
	.set = opairshare_target_enable_set, .get = param_get_bool,
};
module_param_cb(passthru_awdl_target_enable, &opairshare_target_enable_ops, &passthru_awdl_target_enable, 0644);

/* Auto mode: the first unicast IPv6/TCP AWDL data frame names the peer.  Called by
 * the bridge worker with the receiver address of the frame it is about to send.
 * Anything doubtful is ignored (the frame still goes out untargeted, as before).
 */
static bool opairshare_target_frame_is_tcp(const u8 *b)
{
	static const u8 llc[8] = {0xaa, 0xaa, 0x03, 0x00, 0x17, 0xf2, 0x08, 0x00};

	/* b = LLC(8) AWDL data header(8) IPv6 header(first 8 bytes) */
	return !memcmp(b, llc, sizeof(llc)) && b[8] == 0x03 && b[9] == 0x04 &&
		   b[14] == 0x86 && b[15] == 0xdd && (b[16] >> 4) == 6 && b[22] == 6;
}

static void opairshare_target_learn(tp_wma_handle wma, struct opairshare_bridge_item *item,
                              u8 id, const u8 *receiver)
{
    u8 b[24];
    if (item->skb->len < (unsigned int)item->radiotap_len + 26 + sizeof(b) ||
        skb_copy_bits(item->skb, item->radiotap_len + 26, b, sizeof(b)) ||
        !opairshare_target_frame_is_tcp(b)) return;
    opairshare_target_reserve(wma, item->vdev, id, receiver);
}

static int opairshare_target_ensure(tp_wma_handle wma, struct wlan_objmgr_vdev *vdev,
			      u8 id, const u8 *receiver)
{
	void *soc = cds_get_context(QDF_MODULE_ID_SOC);
	QDF_STATUS status;
    struct opairshare_target_peer *t;
	if (!READ_ONCE(passthru_awdl_target_enable) || !opairshare_awdl_target_match(id, receiver))
		return 0;
	t = opairshare_target_find(receiver);
	if (READ_ONCE(opairshare_target_fault))
		return -EIO;
	if (READ_ONCE(t->created))
		return READ_ONCE(t->mapped) ? READ_ONCE(t->auth_error) : -EAGAIN;
	if (!soc || wma->interfaces[id].vdev != vdev || cds_is_driver_recovering())
		return -EAGAIN;
	if (passthru_awdl_raw_rx) {
		if (!opairshare_pas_native_rx_enabled() ||
		    wlan_vdev_mlme_get_opmode(vdev) != QDF_PASSTHRU_MODE ||
		    wma->interfaces[id].type != WMI_VDEV_TYPE_NDI)
			return -EOPNOTSUPP;
		/* Native per-vdev RX format command, not a global PDEV setting.
		 * Queue it before CREATE so the target peer inherits RAW instead
		 * of the normal Ethernet resource default. The monitor TLV is
		 * the verification; submission success alone proves no format.
		 */
		status = wma_vdev_set_param(wma->wmi_handle, id,
			wmi_vdev_param_rx_decap_type, htt_cmn_pkt_type_raw);
		pr_info("OPAIRSHARERAWRX: vdev=%u before_create=1 format=%u submit_status=%u\n",
			id, htt_cmn_pkt_type_raw, status);
		if (QDF_IS_STATUS_ERROR(status))
			return -EIO;
	}
	status = wma_create_peer(wma, t->mac, NULL,
				 (READ_ONCE(passthru_awdl_target_peer_type) < 0 ?
				  WMI_PEER_TYPE_NAN_DATA :
				  (enum wmi_peer_type)READ_ONCE(passthru_awdl_target_peer_type)),
				 id, NULL, false);
	pr_info("OPAIRSHARETARGET: create vdev=%u mac=%pM status=%u\n", id, t->mac, status);
	if (QDF_IS_STATUS_ERROR(status)) {
		/* Creation may have partially initialized DP/FW. Do not retry
		 * or delete a peer whose ownership was never confirmed.
		 */
		WRITE_ONCE(opairshare_target_fault, true);
		return -EIO;
	}
	WRITE_ONCE(t->created, true);
	if (!cdp_find_peer_exist_on_vdev(soc, id, t->mac) ||
	    !wait_event_timeout(opairshare_target_wait, READ_ONCE(t->mapped),
				msecs_to_jiffies(2000))) {
		WRITE_ONCE(opairshare_target_fault, true);
		pr_err("OPAIRSHARETARGET: missing host peer or FW MAP vdev=%u; quarantine\n", id);
		return -ETIMEDOUT;
	}
	/* Same host state transition as wma_add_sta_ndi_mode. */
	status = cdp_peer_state_update(soc, t->mac,
		passthru_awdl_target_auth ? OL_TXRX_PEER_STATE_AUTH : OL_TXRX_PEER_STATE_CONN);
	pr_info("OPAIRSHARETARGETAUTH: vdev=%u mode=%u host_status=%u\n",
		id, passthru_awdl_target_auth, status);
	if (QDF_IS_STATUS_ERROR(status)) {
		WRITE_ONCE(t->auth_error, -EIO);
		return -EIO;
	}
	if (passthru_awdl_target_auth == 2) {
		/* Exact native hdd_change_peer_state -> SME -> WMA operation.
		 * This return is submission status, not a firmware ACK event.
		 */
		status = wma_set_peer_param(wma, t->mac,
			WMI_HOST_PEER_AUTHORIZE, 1, id);
		pr_info("OPAIRSHARETARGETAUTH: vdev=%u fw_submit_status=%u\n", id, status);
		if (QDF_IS_STATUS_ERROR(status)) {
			WRITE_ONCE(t->auth_error, -EIO);
			return -EIO;
		}
	}
	return 0;
}

static int opairshare_target_stop_peer(tp_wma_handle wma, u8 id, struct opairshare_target_peer *t)
{
	QDF_STATUS status;
	unsigned long flags;
	bool sync_delete;
	if (READ_ONCE(opairshare_target_fault))
		return -EIO;
	if (!READ_ONCE(t->created)) return 0;
	if (!wma || id != READ_ONCE(opairshare_target_id) || id >= wma->max_bssid ||
	    wma->interfaces[id].type != WMI_VDEV_TYPE_NDI)
		return -EINVAL;
	sync_delete = wmi_service_enabled(wma->wmi_handle, wmi_service_sync_delete_cmds);
	spin_lock_irqsave(&opairshare_target_lock, flags);
	t->deleting = true;
	t->unmapped = t->delete_response = false;
	spin_unlock_irqrestore(&opairshare_target_lock, flags);
	status = wma_remove_peer(wma, t->mac, id, false);
	pr_info("OPAIRSHARETARGET: delete vdev=%u mac=%pM status=%u\n", id, t->mac, status);
	/* Native delete can detach the host object even if FW command failed.
	 * Prevent a second delete and keep bridge resources on such failure.
	 */
	WRITE_ONCE(t->created, false);
	if (QDF_IS_STATUS_ERROR(status)) {
		WRITE_ONCE(opairshare_target_fault, true);
		return -EIO;
	}
	if (!wait_event_timeout(opairshare_target_wait,
		READ_ONCE(t->unmapped) &&
		(!sync_delete || READ_ONCE(t->delete_response)),
		msecs_to_jiffies(2000))) {
		WRITE_ONCE(opairshare_target_fault, true);
		pr_err("OPAIRSHARETARGET: pending FW UNMAP/delete response vdev=%u; quarantine\n", id);
		return -ETIMEDOUT;
	}

	return 0;
}


static int opairshare_target_stop(tp_wma_handle wma, u8 id)
{
    unsigned int i;
    unsigned long flags;
    int rc;
    if (READ_ONCE(opairshare_target_fault)) return -EIO;
    for (i = 0; i < OPAIRSHARE_TARGET_SLOTS; i++) {
        rc = opairshare_target_stop_peer(wma, id, &opairshare_targets[i]);
        if (rc) return rc;
    }
    spin_lock_irqsave(&opairshare_target_lock, flags);
    opairshare_target_armed = false;
    opairshare_target_id = WLAN_INVALID_VDEV_ID;
    memset(opairshare_targets, 0, sizeof(opairshare_targets));
    spin_unlock_irqrestore(&opairshare_target_lock, flags);
    return 0;
}
