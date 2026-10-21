// SPDX-License-Identifier: GPL-2.0
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * Core definitions for the Wonder driver.
 */

#ifndef __WONDER_CORE_H__
#define __WONDER_CORE_H__

#include <linux/nl80211.h>

#include "wondertap_internal.h"

#define DRV_NAME "wonder"
#define PDEV_NAME "wondertap0"
#define CDEV_NAME "wlan0"
#define VDEV_NAME "wonder0"

/* Vendor ID and Subcommands for NL80211 Vendor Command Registration */
#define OUI_GOOGLE 0x001A11
#define WONDER_VENDOR_ID OUI_GOOGLE /* Placeholder for Google Vendor ID */

#define WONDER_2GHZ_CHANNEL 6
#define WONDER_5GHZ_CHANNEL 149
#define WONDER_JP_CHANNEL 44


struct wonder_data {
	struct ieee80211_hw *hw;
	struct ieee80211_vif *vif;
	struct net_device *pdev; /* Physical network device */
	struct net_device *cdev; /* Control network device */
	struct net_device *vdev; /* Virtual network device */
	/* Vendor Abstraction */
	void *vendor_handle; /* Opaque pointer to vendor's private data */
	u8 data_version;
	enum nl80211_iftype iftype;
	unsigned int config_filters;
	bool tx_stop;
	struct wondertap_data wondertap_data;
	struct work_struct pdev_down_work;
	struct notifier_block netdev_notifier;
};

static inline void hexdump(const char *pfx, unsigned char *msg, int msglen)
{
	int i, col;
	char buf[80];

	col = 0;

	pr_err("dump_addr: %#lx\n", (unsigned long)msg);
	for (i = 0; i < msglen; i++, col++) {
		if (col % 16 == 0)
			strcpy(buf, pfx);
		snprintf(buf + strlen(buf), sizeof(buf) - strlen(buf), "%02x", msg[i]);
		if ((col + 1) % 16 == 0)
			pr_err("%s\n", buf);
		else
			snprintf(buf + strlen(buf), sizeof(buf) - strlen(buf), " ");
	}

	if (col % 16 != 0)
		pr_err("%s\n", buf);
}
#endif /* __WONDER_CORE_H__ */