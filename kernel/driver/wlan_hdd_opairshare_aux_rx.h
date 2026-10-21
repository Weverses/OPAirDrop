/* SPDX-License-Identifier: GPL-2.0-only */
/* Included by wlan_hdd_wondertap.c; native monitor companion RX prototype. */
#if defined(DRIVER_PASSTHRU_MODE) && defined(FEATURE_MONITOR_MODE_SUPPORT)
#include <linux/if_ether.h>
#include <linux/skbuff.h>
#include <linux/atomic.h>
#include "wlan_hdd_tx_rx.h"
#include "wlan_pkt_capture_ucfg_api.h"

static bool passthru_aux_monitor;
module_param(passthru_aux_monitor, bool, 0444);
MODULE_PARM_DESC(passthru_aux_monitor, "Opt-in native monitor RX companion for PAS/NDI");

/* Direct auxiliary creation must mirror the native OTHER_BSS setup. */
static bool opairshare_aux_flags_owned;
static uint32_t opairshare_aux_saved_flags;

bool opairshare_aux_monitor_coexist_owned(void)
{
	return READ_ONCE(opairshare_aux_flags_owned);
}



static bool passthru_sta_regular_rx;
module_param(passthru_sta_regular_rx, bool, 0444);
MODULE_PARM_DESC(passthru_sta_regular_rx,
	"Opt-in native STA HOST_BUF selection with auxiliary monitor status/delivery");

bool opairshare_aux_monitor_regular_rx(void)
{
	return passthru_sta_regular_rx && READ_ONCE(opairshare_aux_flags_owned);
}


static bool passthru_sta_action_rx;
module_param(passthru_sta_action_rx, bool, 0444);
MODULE_PARM_DESC(passthru_sta_action_rx,
	"Opt-in Action/Action-no-ACK FP reception with native STA HOST_BUF");

bool opairshare_aux_monitor_action_rx(void)
{
	return passthru_sta_action_rx && opairshare_aux_monitor_regular_rx();
}

static bool passthru_pas_discovery_actions;
module_param(passthru_pas_discovery_actions, bool, 0444);
MODULE_PARM_DESC(passthru_pas_discovery_actions, "Opt-in MO Action discovery alongside native FP RAW data");
bool opairshare_pas_discovery_actions(void)
{
	return passthru_pas_discovery_actions && opairshare_aux_monitor_action_rx();
}

static bool passthru_sta_rx_probe;
module_param(passthru_sta_rx_probe, bool, 0444);
MODULE_PARM_DESC(passthru_sta_rx_probe,
	"Diagnostic only: phase pauses and regular RX selection with live monitor");
extern int opairshare_coexist_rx_probe(void *handle, unsigned int action);

static int opairshare_aux_rx_probe(const char *phase, unsigned int action)
{
	int ret;

	if (!passthru_sta_rx_probe)
		return 0;
	if (!opairshare_aux_flags_owned)
		return -EPERM;
	pr_info("OPAIRSHARECOEXPHASE: phase=%s begin\n", phase);
	ret = opairshare_coexist_rx_probe(cds_get_context(QDF_MODULE_ID_SOC), action);
	if (ret)
		return ret;
	/* RTNL and the enclosing Wondertap creation transaction serialize teardown.
	 * This opt-in pause enables bound-wlan0 traffic measurement by phase.
	 */
	msleep(3000);
	pr_info("OPAIRSHARECOEXPHASE: phase=%s end\n", phase);
	return 0;
}

static int opairshare_aux_set_flags(struct hdd_context *hdd_ctx)
{
	void *soc = cds_get_context(QDF_MODULE_ID_SOC);
	cdp_config_param_type value = {0};
	QDF_STATUS status;

	if (!policy_mgr_opairshare_sta_coexist_enabled())
		return 0;
	if (!soc || opairshare_aux_flags_owned)
		return -EBUSY;
	status = cdp_txrx_get_psoc_param(soc, CDP_MONITOR_FLAG, &value);
	if (QDF_IS_STATUS_ERROR(status))
		return qdf_status_to_os_return(status);
	opairshare_aux_saved_flags = value.cdp_monitor_flag;
	opairshare_aux_flags_owned = true;
	ucfg_dp_set_mon_conf_flags(hdd_ctx->psoc,
		opairshare_aux_saved_flags | QDF_MONITOR_FLAG_OTHER_BSS);
	status = cdp_txrx_get_psoc_param(soc, CDP_MONITOR_FLAG, &value);
	if (QDF_IS_STATUS_ERROR(status) ||
	    value.cdp_monitor_flag != (opairshare_aux_saved_flags | QDF_MONITOR_FLAG_OTHER_BSS))
		return -EIO;
	pr_info("OPAIRSHARECOEXMON: saved_flags=%u actual_flags=%u\n",
		opairshare_aux_saved_flags, value.cdp_monitor_flag);
	return 0;
}

static int opairshare_aux_restore_flags(struct hdd_context *hdd_ctx)
{
	void *soc = cds_get_context(QDF_MODULE_ID_SOC);
	cdp_config_param_type value = {0};
	QDF_STATUS status;

	if (!opairshare_aux_flags_owned)
		return 0;
	if (!soc)
		return -EIO;
	status = cdp_txrx_get_psoc_param(soc, CDP_MONITOR_FLAG, &value);
	if (QDF_IS_STATUS_ERROR(status) ||
	    (value.cdp_monitor_flag != opairshare_aux_saved_flags &&
	     value.cdp_monitor_flag != (opairshare_aux_saved_flags | QDF_MONITOR_FLAG_OTHER_BSS)))
		return -EIO;
	ucfg_dp_set_mon_conf_flags(hdd_ctx->psoc, opairshare_aux_saved_flags);
	status = cdp_txrx_get_psoc_param(soc, CDP_MONITOR_FLAG, &value);
	if (QDF_IS_STATUS_ERROR(status) || value.cdp_monitor_flag != opairshare_aux_saved_flags)
		return -EIO;
	opairshare_aux_flags_owned = false;
	pr_info("OPAIRSHARECOEXMON: restored_flags=%u\n", value.cdp_monitor_flag);
	return 0;
}

static struct hdd_adapter *opairshare_aux_adapter;
static DEFINE_MUTEX(opairshare_aux_route_lock);
static struct net_device *opairshare_aux_target;
static bool opairshare_aux_tap_registered;
static bool opairshare_aux_forward_enabled;
static atomic64_t opairshare_aux_seen = ATOMIC64_INIT(0);
static atomic64_t opairshare_aux_forwarded = ATOMIC64_INIT(0);
static atomic64_t opairshare_aux_dropped = ATOMIC64_INIT(0);
static struct packet_type opairshare_aux_tap;

#include "wlan_hdd_opairshare_rx_ring_switch.h"

static int opairshare_aux_count_get(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, 32, "%lld\n",
		(long long)atomic64_read((atomic64_t *)kp->arg));
}
static const struct kernel_param_ops opairshare_aux_count_ops = { .get = opairshare_aux_count_get };
module_param_cb(opairshare_aux_seen, &opairshare_aux_count_ops, &opairshare_aux_seen, 0444);
module_param_cb(opairshare_aux_forwarded, &opairshare_aux_count_ops, &opairshare_aux_forwarded, 0444);
module_param_cb(opairshare_aux_dropped, &opairshare_aux_count_ops, &opairshare_aux_dropped, 0444);

#include "wlan_hdd_opairshare_awdl_rx_dedup.h"
#include "wlan_hdd_opairshare_rx_order.h"

void opairshare_pas_ba_rx(struct sk_buff *skb, u16 rtlen);

static int opairshare_aux_receive(struct sk_buff *skb, struct net_device *dev,
			    struct packet_type *pt, struct net_device *original)
{
	struct sk_buff *copy;
	struct net_device *target = READ_ONCE(opairshare_aux_target);
	uint8_t rt[8];
	uint16_t len;
	if (skb->pkt_type == PACKET_OUTGOING)
		goto out;
	atomic64_inc(&opairshare_aux_seen);
	if (!target || !READ_ONCE(opairshare_aux_forward_enabled) ||
	    !netif_running(target) || skb_copy_bits(skb, 0, rt, sizeof(rt)))
		goto drop;
	len = rt[2] | (rt[3] << 8);
	if (rt[0] || len < 8 || len + 2 > skb->len)
		goto drop;
	opairshare_pas_ba_rx(skb, len);
	if (opairshare_awdl_rx_duplicate(skb, target, len))
		goto out;
	if (opairshare_order_receive(skb, target, len))
		goto out;
	copy = skb_clone(skb, GFP_ATOMIC);
	if (!copy)
		goto drop;
	copy->dev = target;
	copy->skb_iif = target->ifindex;
	copy->pkt_type = PACKET_HOST;
	/* Required by the real wonder_rx_monitor_handler, not an IP path. */
	copy->protocol = htons(ETH_P_802_2);
	skb_reset_mac_header(copy);
	atomic64_inc(&opairshare_aux_forwarded);
	netif_rx(copy);
	goto out;
drop:
	atomic64_inc(&opairshare_aux_dropped);
out:
	consume_skb(skb);
	return NET_RX_SUCCESS;
}

static void opairshare_aux_route_stop_locked(void)
{
	WRITE_ONCE(passthru_awdl_rx_ring_select, 0);
	WRITE_ONCE(opairshare_aux_forward_enabled, false);
	if (opairshare_aux_tap_registered) {
		dev_remove_pack(&opairshare_aux_tap); /* synchronize_net drains callbacks */
		opairshare_aux_tap_registered = false;
		opairshare_aux_tap.dev = NULL;
	}
	opairshare_order_stop();
	opairshare_awdl_rx_reset();
	if (opairshare_aux_target) {
		dev_put(opairshare_aux_target);
		opairshare_aux_target = NULL;
	}
}

static void opairshare_aux_route_stop(void)
{
	mutex_lock(&opairshare_aux_route_lock);
	opairshare_aux_route_stop_locked();
	mutex_unlock(&opairshare_aux_route_lock);
}

/* Generic teardown removes routing before either adapter can disappear. */
void opairshare_pas_aux_pre_stop(struct hdd_adapter *adapter)
{
	mutex_lock(&opairshare_aux_route_lock);
	if (adapter == opairshare_aux_adapter || adapter->dev == opairshare_aux_target)
		opairshare_aux_route_stop_locked();
	if (adapter == opairshare_aux_adapter)
		opairshare_aux_adapter = NULL;
	mutex_unlock(&opairshare_aux_route_lock);
}

static int opairshare_aux_stop(struct hdd_context *hdd_ctx)
{
	struct hdd_adapter *mon = opairshare_aux_adapter;
	struct osif_vdev_sync *sync;
	QDF_STATUS status;
	int ret;
	opairshare_aux_route_stop();
	if (!mon)
		return opairshare_aux_restore_flags(hdd_ctx);
	ASSERT_RTNL();
	if (opairshare_aux_flags_owned) {
		void *soc = cds_get_context(QDF_MODULE_ID_SOC);

		if (!soc)
			return -EIO;
		/* Keep OTHER_BSS and mvdev live while native reset restores
		 * regular RXDMA filters. Reset after vdev destruction skips
		 * this restoration because mvdev has already disappeared.
		 */
		status = cdp_reset_monitor_mode(soc, OL_TXRX_PDEV_ID, false);
		if (QDF_IS_STATUS_ERROR(status))
			return qdf_status_to_os_return(status);
		pr_info("OPAIRSHARECOEXMON: regular_rx_reset=%u\n", status);
	}
	dev_close(mon->dev);
	ret = osif_vdev_sync_trans_start_wait(mon->dev, &sync);
	if (ret) {
		/* ndo_stop may already have cleared the published pointer. The
		 * still-registered adapter must remain reachable for cleanup.
		 */
		opairshare_aux_adapter = mon;
		pr_err("OPAIRSHAREAUXSTOPFAIL: phase=sync status=%d\n", ret);
		return ret;
	}
	status = hdd_stop_adapter(hdd_ctx, mon);
	if (QDF_IS_STATUS_ERROR(status)) {
		opairshare_aux_adapter = mon;
		osif_vdev_sync_trans_stop(sync);
		return qdf_status_to_os_return(status);
	}
	opairshare_aux_adapter = NULL;
	osif_vdev_sync_unregister(mon->dev);
	osif_vdev_sync_wait_for_ops(sync);
	wlan_hdd_release_intf_addr(hdd_ctx, mon->mac_addr.bytes);
	hdd_deinit_adapter(hdd_ctx, mon, true);
	clear_bit(DEVICE_IFACE_OPENED, &mon->event_flags);
	hdd_close_adapter(hdd_ctx, mon, true);
	osif_vdev_sync_trans_stop(sync);
	osif_vdev_sync_destroy(sync);
	pr_info("OPAIRSHAREAUXSTOP: seen=%lld forwarded=%lld dropped=%lld\n",
		(long long)atomic64_read(&opairshare_aux_seen),
		(long long)atomic64_read(&opairshare_aux_forwarded),
		(long long)atomic64_read(&opairshare_aux_dropped));
	return opairshare_aux_restore_flags(hdd_ctx);
}

static int opairshare_aux_start(struct hdd_context *hdd_ctx,
			  struct hdd_adapter *parent, uint32_t freq)
{
	struct hdd_adapter *mon = NULL;
	struct osif_vdev_sync *sync;
	struct hdd_monitor_ctx *ctx;
	const char *phase = "sync-create";
	int ret;
	bool support;
	if (!passthru_aux_monitor)
		return 0;
	ASSERT_RTNL();
	if (opairshare_aux_adapter)
		return -EBUSY;
	support = ucfg_mlme_is_sta_mon_conc_supported(hdd_ctx->psoc);
	pr_info("OPAIRSHAREAUXCAP: sta_mon=%u freq=%u\n", support, freq);
	if (!support || ucfg_pkt_capture_get_mode(hdd_ctx->psoc) != PACKET_CAPTURE_MODE_DISABLE)
		return -EOPNOTSUPP;
	ret = osif_vdev_sync_create_and_trans(hdd_ctx->parent_dev, &sync);
	if (ret)
		return ret;
	/* Retain the native concurrency guard; false avoids the deliberate
	 * STA disconnect in the ordinary RX-only-monitor creation variant.
	 */
	ret = wlan_hdd_add_monitor_check(hdd_ctx, &mon, "opairsharerx%d", true,
		NET_NAME_UNKNOWN, false);
	if (ret) {
		osif_vdev_sync_trans_stop(sync);
		osif_vdev_sync_destroy(sync);
		return ret;
	}
	/* Match _wlan_hdd_add_virtual_intf: register before ndo_open and
	 * release the creation transaction so ndo_open can start its own.
	 */
	osif_vdev_sync_register(mon->dev, sync);
	osif_vdev_sync_trans_stop(sync);
	opairshare_aux_adapter = mon;
	pr_info("OPAIRSHAREAUXSYNC: phase=registered netdev=%s\n", mon->dev->name);
	phase = "monitor-flags";
	ret = opairshare_aux_set_flags(hdd_ctx);
	if (ret)
		goto fail;
	phase = "dev-open";
	ret = dev_open(mon->dev, NULL);
	if (ret)
		goto fail;
	ret = opairshare_aux_rx_probe("after-open", 0);
	if (ret)
		goto fail;
	phase = "channel-sync";
	ret = osif_vdev_sync_trans_start_wait(mon->dev, &sync);
	if (ret)
		goto fail;
	ctx = WLAN_HDD_GET_MONITOR_CTX_PTR(mon->deflink);
	ctx->freq = freq;
	ctx->bandwidth = CH_WIDTH_20MHZ;
	ctx->phy_mode = hdd_cfg_xlate_to_csr_phy_mode(eHDD_DOT11_MODE_11n);
	phase = "set-channel";
	ret = wlan_hdd_set_mon_chan(mon);
	osif_vdev_sync_trans_stop(sync);
	if (ret)
		goto fail;
	ret = opairshare_aux_rx_probe("after-channel", 0);
	if (ret)
		goto fail;
	ret = opairshare_aux_rx_probe("regular-buffer-live-monitor", 1);
	if (ret)
		goto fail;
	ret = opairshare_aux_rx_probe("monitor-buffer-restored", 2);
	if (ret)
		goto fail;
	mutex_lock(&opairshare_aux_route_lock);
	dev_hold(parent->dev);
	opairshare_aux_target = parent->dev;
	opairshare_aux_tap.type = htons(ETH_P_ALL);
	opairshare_aux_tap.dev = mon->dev;
	opairshare_aux_tap.func = opairshare_aux_receive;
	WRITE_ONCE(opairshare_aux_forward_enabled, true);
	dev_add_pack(&opairshare_aux_tap);
	opairshare_aux_tap_registered = true;
	mutex_unlock(&opairshare_aux_route_lock);
	pr_info("OPAIRSHAREAUXREADY: rx=%s vdev=%u target=%s freq=%u\n",
		mon->dev->name, mon->deflink->vdev_id, parent->dev->name, freq);
	return 0;
fail:
	pr_err("OPAIRSHAREAUXFAIL: phase=%s status=%d\n", phase, ret);
	if (opairshare_aux_stop(hdd_ctx))
		pr_err("OPAIRSHAREAUXFAIL: native monitor cleanup failed\n");
	return ret;
}

static int opairshare_aux_set_freq(struct hdd_adapter *parent, uint32_t freq)
{
	struct hdd_monitor_ctx *ctx;
	struct osif_vdev_sync *sync;
	int ret;
	if (!opairshare_aux_adapter || opairshare_aux_target != parent->dev)
		return 0;
	WRITE_ONCE(opairshare_aux_forward_enabled, false);
	ret = osif_vdev_sync_trans_start(opairshare_aux_adapter->dev, &sync);
	if (ret)
		return ret;
	ctx = WLAN_HDD_GET_MONITOR_CTX_PTR(opairshare_aux_adapter->deflink);
	ctx->freq = freq;
	ret = wlan_hdd_set_mon_chan(opairshare_aux_adapter);
	osif_vdev_sync_trans_stop(sync);
	if (!ret)
		WRITE_ONCE(opairshare_aux_forward_enabled, true);
	return ret;
}
#else
bool opairshare_aux_monitor_regular_rx(void) { return false; }
bool opairshare_aux_monitor_action_rx(void) { return false; }
bool opairshare_pas_discovery_actions(void) { return false; }
bool opairshare_aux_monitor_coexist_owned(void) { return false; }
void opairshare_pas_aux_pre_stop(struct hdd_adapter *adapter) { }
static int opairshare_aux_start(struct hdd_context *hdd_ctx, struct hdd_adapter *parent, uint32_t freq) { return 0; }
static int opairshare_aux_stop(struct hdd_context *hdd_ctx) { return 0; }
static int opairshare_aux_set_freq(struct hdd_adapter *parent, uint32_t freq) { return 0; }
#endif
