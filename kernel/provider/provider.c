// SPDX-License-Identifier: GPL-2.0-only
/* DT-free Wonder component bridge. Does not own the PCIe/WLAN device. */
#include <linux/component.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/platform_device.h>

#include "wondertap.h"

#define PROVIDER_NAME "qcom,cnss-vendor-wlan-wonder"

static DEFINE_MUTEX(provider_lock);
static struct platform_device *provider;
static const struct wondertap_priv *active_priv;
static bool component_registered;

static int provider_bind(struct device *dev, struct device *master, void *data)
{
	dev_info(dev, "vendor wonder bound to master device %s\n", dev_name(master));
	return 0;
}

static void provider_unbind(struct device *dev, struct device *master, void *data)
{
	dev_info(dev, "vendor wonder unbound from master device %s\n", dev_name(master));
}

static const struct component_ops provider_ops = {
	.bind = provider_bind,
	.unbind = provider_unbind,
};

/* Same declaration/CRC as the reference CNSS export and the PLD caller.
 * Caller owns priv and its callbacks until the matching NULL call returns.
 * Never call this function from a component bind/unbind callback.
 */
int cnss_set_vendor_wonder_priv_data(const void *priv_data)
{
	const struct wondertap_priv *priv = priv_data;
	int ret = 0;

	mutex_lock(&provider_lock);
	if (!provider) {
		ret = -ENODEV;
		goto out;
	}
	if (priv) {
		if (!priv->wonder_ops || priv->ver < 0 || priv->ver >= WONDER_VERSION_MAX) {
			ret = -EINVAL;
			goto out;
		}
		/* A repeated registration must not add a duplicate component. */
		if (active_priv) {
			ret = active_priv == priv ? 0 : -EBUSY;
			goto out;
		}
		platform_set_drvdata(provider, (void *)priv);
		ret = component_add(&provider->dev, &provider_ops);
		if (ret) {
			platform_set_drvdata(provider, NULL);
			goto out;
		}
		component_registered = true;
		active_priv = priv;
	} else {
		/* Unbind the master before clearing/freeing the caller's ops. */
		if (component_registered)
			component_del(&provider->dev, &provider_ops);
		component_registered = false;
		active_priv = NULL;
		platform_set_drvdata(provider, NULL);
	}
	out:
	mutex_unlock(&provider_lock);
	return ret;
}
EXPORT_SYMBOL(cnss_set_vendor_wonder_priv_data);

static int __init provider_init(void)
{
	int ret;

	provider = platform_device_alloc(PROVIDER_NAME, PLATFORM_DEVID_NONE);
	if (!provider)
		return -ENOMEM;
	ret = platform_device_add(provider);
	if (ret) {
		platform_device_put(provider);
		provider = NULL;
		return ret;
	}
	pr_info("opairshare_wonder_provider: created %s; stock CNSS untouched\n", PROVIDER_NAME);
	return 0;
}

static void __exit provider_exit(void)
{
	cnss_set_vendor_wonder_priv_data(NULL);
	/* No platform driver remove callback: device destruction has one owner. */
	platform_device_unregister(provider);
	provider = NULL;
}

module_init(provider_init);
module_exit(provider_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("OPAirShare DT-free Wonder provider bridge, independent of CNSS");
