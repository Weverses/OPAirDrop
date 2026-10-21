/* SPDX-License-Identifier: GPL-2.0-only */
/* Included after auxiliary-route state in wlan_hdd_opairshare_aux_rx.h. */
static unsigned int passthru_awdl_rx_ring_select;
static int opairshare_rx_ring_set(const char *text, const struct kernel_param *kp)
{
	unsigned int mode;
	int ret = kstrtouint(text, 0, &mode);
	(void)kp;
	if (ret)
		return ret;
	if (mode > 1)
		return -EINVAL;
	/* Native adapter teardown also owns RTNL. Never call into a disappearing
	 * mvdev, and don't wait for RTNL while holding the module-parameter lock.
	 */
	if (!rtnl_trylock())
		return -EBUSY;
	if (!opairshare_aux_flags_owned || !opairshare_aux_adapter || !opairshare_aux_target ||
	    !opairshare_aux_forward_enabled) {
		ret = mode ? -ENODEV : 0;
		goto out;
	}
	ret = opairshare_coexist_rx_probe(cds_get_context(QDF_MODULE_ID_SOC),
					mode ? 1 : 2);
out:
	if (!ret)
		WRITE_ONCE(passthru_awdl_rx_ring_select, mode);
	pr_info("OPAIRSHARERXSWITCH: requested=%u retained=%u rc=%d\n", mode,
		READ_ONCE(passthru_awdl_rx_ring_select), ret);
	rtnl_unlock();
	return ret;
}
static const struct kernel_param_ops opairshare_rx_ring_ops = {
	.set = opairshare_rx_ring_set, .get = param_get_uint,
};
module_param_cb(passthru_awdl_rx_ring_select, &opairshare_rx_ring_ops,
		&passthru_awdl_rx_ring_select, 0644);
MODULE_PARM_DESC(passthru_awdl_rx_ring_select,
	"Session-only RX ring probe: 1 regular HOST_BUF selection, 0 monitor filter");


/* Called only by a pending bridge event; teardown drains that event before
 * removing the owned auxiliary adapter. Do not acquire RTNL in this path.
 */
int opairshare_pas_native_ring_activate(void)
{
	int ret;
	if (!READ_ONCE(opairshare_aux_flags_owned) || !READ_ONCE(opairshare_aux_forward_enabled))
		return -ENODEV;
	ret = opairshare_coexist_rx_probe(cds_get_context(QDF_MODULE_ID_SOC), 1);
	if (!ret)
		WRITE_ONCE(passthru_awdl_rx_ring_select, 1);
	return ret;
}
