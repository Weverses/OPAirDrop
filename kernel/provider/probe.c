// SPDX-License-Identifier: GPL-2.0-only
/* Binding/lifetime probe with real ABI types and fail-closed callbacks. */
#include <linux/module.h>
#include <linux/errno.h>
#include <linux/string.h>
#include "wondertap.h"

extern int cnss_set_vendor_wonder_priv_data(const void *priv_data);

static int probe_init_op(void **handle, const struct wondertap_init_params *params)
{
	*handle = NULL;
	return -EOPNOTSUPP;
}
static void probe_deinit_op(void *handle, const struct wondertap_deinit_params *params) {}
static int probe_freq(void *handle, const struct wondertap_set_freq_params *params)
{
	return -EOPNOTSUPP;
}
static int probe_filter(void *handle, enum wondertap_filter_type type, const void *params)
{
	return -EOPNOTSUPP;
}
static int probe_rate(void *handle, const struct wondertap_fixed_tx_rate_params *params)
{
	return -EOPNOTSUPP;
}
static int probe_mask(void *handle, const struct wondertap_tx_rate_mask_params *params)
{
	return -EOPNOTSUPP;
}
static int probe_capabilities(void *handle, struct wondertap_capability *features)
{
	memset(features, 0, sizeof(*features));
	return 0;
}
static const struct wondertap_ops ops = {
	.init = probe_init_op, .deinit = probe_deinit_op,
	.set_freq = probe_freq, .set_filter = probe_filter,
	.set_fixed_tx_rate = probe_rate, .set_tx_rate_mask = probe_mask,
	.get_capabilities = probe_capabilities,
};
static const struct wondertap_priv priv = {
	.ver = WONDER_VERSION_1_4_1,
	.wonder_ops = &ops,
};
static const struct wondertap_priv other_priv = {
	.ver = WONDER_VERSION_1_4_1,
	.wonder_ops = &ops,
};

static int __init probe_init(void)
{
	int ret;

	ret = cnss_set_vendor_wonder_priv_data(&priv);
	if (ret)
		return ret;
	/* Exercise duplicate registration and reject a competing owner. */
	if (cnss_set_vendor_wonder_priv_data(&priv) ||
	    cnss_set_vendor_wonder_priv_data(&other_priv) != -EBUSY) {
		cnss_set_vendor_wonder_priv_data(NULL);
		return -EINVAL;
	}
	pr_info("opairshare_route_b_probe: registered ver=%d; duplicate/conflict checks passed\n", priv.ver);
	return 0;
}

static void __exit probe_exit(void)
{
	cnss_set_vendor_wonder_priv_data(NULL);
	pr_info("opairshare_route_b_probe: ops cleared\n");
}
module_init(probe_init);
module_exit(probe_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Typed Wonder binding probe; no radio operations");
