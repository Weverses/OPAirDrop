// SPDX-License-Identifier: GPL-2.0
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * System-Side Recovery (SSR) implementation. This module handles events
 * from the physical network device, such as it going down, and triggers
 * a re-initialization of the mac80211 features to recover.
 */
#define LOG_MODULE_NAME "ssr"

#include <linux/netdevice.h>

#include "core.h"
#include "mac80211.h"
#include "wonder_log.h"
#include "ssr.h"

static void wonder_pdev_down_work_handler(struct work_struct *work)
{
	struct wonder_data *wonder = container_of(work, struct wonder_data, pdev_down_work);

	wonder_features_exit(wonder);
	wonder_features_init(wonder);
}

static int wonder_netdev_event(struct notifier_block *nb, unsigned long event, void *ptr)
{
	struct net_device *dev = netdev_notifier_info_to_dev(ptr);
	struct wonder_data *wonder = container_of(nb, struct wonder_data, netdev_notifier);

	if (!wonder || !wonder->pdev || dev != wonder->pdev)
		return NOTIFY_DONE;

	switch (event) {
	case NETDEV_DOWN:
		wonder_info("Physical device %s is down. Triggering recovery.\n", dev->name);
		netif_stop_queue(wonder->vdev);
		schedule_work(&wonder->pdev_down_work);
		break;
	default:
		break;
	}
	return NOTIFY_DONE;
}

int wonder_ssr_init(struct wonder_data *wonder)
{
	wonder->netdev_notifier.notifier_call = wonder_netdev_event;
	INIT_WORK(&wonder->pdev_down_work, wonder_pdev_down_work_handler);

	/* Register for netdevice events to monitor pdev state */
	return register_netdevice_notifier(&wonder->netdev_notifier);
}

void wonder_ssr_exit(struct wonder_data *wonder)
{
	/*
	 * Unregister the notifier first to prevent new work from being scheduled
	 * during cleanup.
	 */
	unregister_netdevice_notifier(&wonder->netdev_notifier);

	/* Cancel any pending work and wait for it to finish. */
	cancel_work_sync(&wonder->pdev_down_work);
}
