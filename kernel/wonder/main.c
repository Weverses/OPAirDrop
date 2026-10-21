/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * Main entry point for the Wonder driver module. This file handles the
 * initialization and cleanup of the driver, locating the physical
 * network device and kicking off the mac80211 registration process.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/string.h>
#include <linux/version.h>
#include <linux/init.h>
#include <linux/netdevice.h>
#include <linux/platform_device.h>
#include <linux/component.h>
#include <linux/of.h>
#include <linux/of_platform.h>

#include "core.h"
#include "mac80211.h"
#include "wonder_log.h"
#include "include/wonder/wondertap.h"
#include "wondertap_internal.h"

/* Module parameter for setting the physical device name */
module_param(physical_name, charp, 0444);
MODULE_PARM_DESC(physical_name, "Interface name to use (e.g., wlan0, radiotap0, ...)");


#define WONDER_MAX_COMPAT_VERSIONS 4
static int wonder_ver_match_table[WONDER_VERSION_MAX][WONDER_MAX_COMPAT_VERSIONS] = {
	{ WONDER_VERSION_1_0, -1 },
	{ WONDER_VERSION_1_1, -1 },
	{ WONDER_VERSION_1_2, -1 },
	{ WONDER_VERSION_1_3, -1 },
	{ WONDER_VERSION_1_4, -1 },
	{ WONDER_VERSION_1_4_1, WONDER_VERSION_1_4, -1 },
	{ WONDER_VERSION_1_5, WONDER_VERSION_1_4, WONDER_VERSION_1_4_1, -1 },
	{ WONDER_VERSION_1_5_1, WONDER_VERSION_1_4, WONDER_VERSION_1_5, WONDER_VERSION_1_4_1 },
};

static bool wonder_ver_can_support(enum wondertap_ver slave, enum wondertap_ver master)
{
	int i;

	if (master < 0 || master >= WONDER_VERSION_MAX)
		return false;
	if (slave < 0 || slave >= WONDER_VERSION_MAX)
		return false;

	for (i = 0; i < WONDER_MAX_COMPAT_VERSIONS; i++) {
		if (wonder_ver_match_table[master][i] == -1)
			break;
		if (wonder_ver_match_table[master][i] == slave) {
			return true;
		}
	}
	return false;
}

/*
 * No-DT product support.
 *
 * Some products ship no device tree node for the wonder driver, so there is
 * no 'wondertap-provider' phandle to follow. On such machines the provider
 * device is created by the cnss2 module as a platform device with the
 * well-known name below, and this driver locates it by that name instead.
 */
#define WONDER_PROVIDER_DEV_NAME "qcom,cnss-vendor-wlan-wonder"

struct wonder_provider_match {
	struct device_node *np;    /* DT path: provider device_node */
	const char *name;          /* No-DT path: provider device name */
};

/*
 * Match descriptor handed to the component framework through
 * component_match_add_release(). It lives in static storage: the match entry
 * keeps this pointer until the master is torn down and its release callback
 * is NULL, so the descriptor stays valid for as long as the driver is
 * registered. A single instance is enough because only one wonder platform
 * device exists.
 */
static struct wonder_provider_match wonder_provider;

/*
 * Compare a device name with the provider name. The ".N" instance suffix
 * that the driver core appends to id-assigned devices (e.g.
 * "qcom,cnss-vendor-wlan-wonder.0") is tolerated.
 */
static bool wonder_dev_name_match(struct device *dev, const char *name)
{
	const char *devname = dev_name(dev);
	size_t len;

	if (!devname || !name)
		return false;
	if (!strcmp(devname, name))
		return true;

	len = strlen(name);
	return !strncmp(devname, name, len) && devname[len] == '.';
}

/*
 * component compare() callback: DT products match the provider by its
 * device_node, no-DT products by its platform device name.
 */
static int wonder_compare_dev(struct device *dev, void *data)
{
	const struct wonder_provider_match *pmatch = data;

	if (pmatch->np)
		return dev->of_node == pmatch->np;

	return wonder_dev_name_match(dev, pmatch->name);
}

/* bus_find_device() match callback used by the no-DT provider lookup */
static int wonder_bus_match_name(struct device *dev, const void *data)
{
	return wonder_dev_name_match(dev, data);
}

/*
 * No-DT provider lookup: find the platform device that cnss2 registers
 * under WONDER_PROVIDER_DEV_NAME.
 *
 * Returns the device with a reference held, so the caller must put_device()
 * it; returns NULL when the provider does not exist yet (nothing to release
 * in that case).
 */
static struct platform_device *wonder_find_provider_by_name(const char *name)
{
	struct device *dev;

	dev = bus_find_device(&platform_bus_type, NULL, name, wonder_bus_match_name);
	if (!dev)
		return NULL;

	return to_platform_device(dev);
}

static int wonder_master_bind(struct device *dev)
{
	struct wondertap_data *wondertap = dev_get_drvdata(dev);
	struct platform_device *wlan_pdev;
	struct wondertap_priv *wlan_priv;
	int ret;

	dev_info(dev, "%s(): Binding...\n", __func__);

	ret = component_bind_all(dev, NULL);
	if (ret)
		return ret;

	/*
	 * DT product: the provider is the device referenced by the
	 * 'wondertap-provider' phandle kept in wlan_node.
	 * No-DT product: wlan_node is NULL, so find the provider platform
	 * device that cnss2 registered under WONDER_PROVIDER_DEV_NAME.
	 * Both lookups return the device with a reference held, so each exit
	 * path below releases it with put_device().
	 */
	if (wondertap->wlan_node)
		wlan_pdev = of_find_device_by_node(wondertap->wlan_node);
	else
		wlan_pdev = wonder_find_provider_by_name(WONDER_PROVIDER_DEV_NAME);
	if (!wlan_pdev) {
		dev_err(dev, "%s(): Cannot find wlan platform device!\n", __func__);
		component_unbind_all(dev, NULL);
		return -ENODEV;
	}

	wlan_priv = platform_get_drvdata(wlan_pdev);
	if (!wlan_priv || !wlan_priv->wonder_ops)
		goto err;

	if (!wonder_ver_can_support(wlan_priv->ver, wondertap->ver)) {
		dev_err(dev, "%s(): wondertap interface version mismatch(%d,%d)!\n",
			__func__, wlan_priv->ver, wondertap->ver);
		goto err;
	}
	/* All matched, hook the ops to wondertap interface. */
	wondertap_register_ops(wlan_priv->wonder_ops);
	wondertap->wonder_ops = wlan_priv->wonder_ops;
	put_device(&wlan_pdev->dev);
	dev_info(dev, "%s(): Connected to wlan ver %d (cur: wonder ver %d)!\n",
		__func__, wlan_priv->ver, wondertap->ver);
	return 0;
err:
	dev_err(dev, "%s(): wlan driver data invalid!\n", __func__);
	put_device(&wlan_pdev->dev);
	component_unbind_all(dev, NULL);
	return -EINVAL;
}

static void wonder_master_unbind(struct device *dev)
{
	struct wondertap_data *wondertap = dev_get_drvdata(dev);

	dev_info(dev, "%s(): Unbinding...\n", __func__);

	wondertap_unregister_ops(wondertap->wonder_ops);
	component_unbind_all(dev, NULL);
}

static const struct component_master_ops wonder_comp_ops = {
	.bind = wonder_master_bind,
	.unbind = wonder_master_unbind,
};

static int wonder_probe(struct platform_device *pdev)
{
	struct wonder_data *wonder;
	struct wondertap_data *wondertap;
	struct component_match *match = NULL;
	struct device_node *provider_node;
	int ret;

	dev_info(&pdev->dev, "%s(): probe\n", __func__);
	wonder = wonder_mac80211_init();
	if (!wonder)
		return -ENODEV;

	wondertap = &wonder->wondertap_data;

	/*
	 * Resolve the provider device this master will bind against.
	 * DT product: follow the 'wondertap-provider' phandle as before.
	 * No-DT product (no of_node at all, e.g. the platform device this
	 * module registers for itself): match the provider device that
	 * cnss2 creates, by its well-known device name.
	 */
	provider_node = pdev->dev.of_node ?
		of_parse_phandle(pdev->dev.of_node, "wondertap-provider", 0) : NULL;

	if (provider_node) {
		/* DT path: the provider component is matched by its device_node. */
		wonder_provider.np = provider_node;
		wonder_provider.name = NULL;
		wondertap->wlan_node = provider_node;
	} else if (pdev->dev.of_node) {
		/* DT product with a broken phandle: keep failing as before. */
		dev_err(&pdev->dev, "%s(): Missing 'wondertap-provider' in DTS\n", __func__);
		return -ENODEV;
	} else {
		/* No-DT path: the provider component is matched by its device name. */
		dev_info(&pdev->dev, "%s(): No DT node, matching provider by name '%s'\n",
			__func__, WONDER_PROVIDER_DEV_NAME);
		wonder_provider.np = NULL;
		wonder_provider.name = WONDER_PROVIDER_DEV_NAME;
		wondertap->wlan_node = NULL;
	}

	/* Assign wondertap interface version will be used in the match process. */
	wondertap->ver = WONDER_VERSION_1_5_1;
	platform_set_drvdata(pdev, wondertap);
	component_match_add_release(&pdev->dev, &match, NULL,
						wonder_compare_dev, &wonder_provider);

	of_node_put(provider_node);
	ret = component_master_add_with_match(&pdev->dev, &wonder_comp_ops, match);
	if (ret) {
		dev_err(&pdev->dev, "%s(): Missing component\n", __func__);
		return -ENODEV;
	}
	return wonder_debugfs_init(wonder);
}

static void wonder_remove(struct platform_device *pdev)
{
	component_master_del(&pdev->dev, &wonder_comp_ops);
	wonder_debugfs_exit();
	wonder_mac80211_exit();
}

#if LINUX_VERSION_CODE < KERNEL_VERSION(6, 11, 0)
static int wonder_remove_wrapper(struct platform_device *pdev)
{
	wonder_remove(pdev);
	return 0;
}
#define wonder_remove wonder_remove_wrapper
#endif /* LINUX_VERSION_CODE < KERNEL_VERSION(6, 11, 0) */

static const struct of_device_id wonder_dt_ids[] = {
	{ .compatible = "google,wonder-drv-v1" },
	{ /* sentinel */ }
};
MODULE_DEVICE_TABLE(of, wonder_dt_ids);

static struct platform_driver wonder_driver = {
	.probe = wonder_probe,
	.remove = wonder_remove,
	.driver = {
		.name = "wonder",
		.of_match_table = wonder_dt_ids,
	},
};

/*
 * The driver registers itself from explicit init/exit functions instead of
 * module_platform_driver(): on no-DT products nothing creates a "wonder"
 * platform device for us, so init creates one itself. On DT products a
 * device may already have been populated, in which case init keeps the
 * existing one (platform_device_add() fails with -EEXIST) and just binds
 * the driver to it.
 */
static struct platform_device *wonder_device;

static int __init wonder_init(void)
{
	int ret;

	/* Add the platform device first, then the driver that binds to it. */
	wonder_device = platform_device_alloc("wonder", PLATFORM_DEVID_NONE);
	if (!wonder_device)
		return -ENOMEM;

	ret = platform_device_add(wonder_device);
	if (ret) {
		platform_device_put(wonder_device);
		wonder_device = NULL;
		if (ret != -EEXIST) {
			pr_err("wonder: failed to add platform device (%d)\n", ret);
			return ret;
		}
		pr_info("wonder: using the existing 'wonder' platform device\n");
	}

	ret = platform_driver_register(&wonder_driver);
	if (ret) {
		pr_err("wonder: failed to register platform driver (%d)\n", ret);
		platform_device_unregister(wonder_device);
		wonder_device = NULL;
		return ret;
	}

	return 0;
}

static void __exit wonder_exit(void)
{
	/*
	 * Unregister the driver first: it invokes wonder_remove() and the
	 * platform device it is bound to must still exist at that point.
	 */
	platform_driver_unregister(&wonder_driver);
	if (wonder_device) {
		platform_device_unregister(wonder_device);
		wonder_device = NULL;
	}
}
module_init(wonder_init);
module_exit(wonder_exit);

MODULE_DESCRIPTION("Google Wonder Virtual mac80211 Driver");
MODULE_LICENSE("GPL");
MODULE_AUTHOR("Google Android WiFi Team");
