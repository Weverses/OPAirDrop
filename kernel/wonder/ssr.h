/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * System-Side Recovery (SSR) support for handling physical device events.
 */

#ifndef __WONDER_SSR_H__
#define __WONDER_SSR_H__

#include "core.h"

/**
 * wonder_ssr_init() - Initializes the System-Side Recovery module.
 * @wonder: Pointer to the main driver data structure.
 *
 * This function sets up the necessary components for monitoring the physical
 * device's state. It initializes a workqueue for handling recovery tasks
 * and registers a netdevice notifier to listen for events like NETDEV_DOWN.
 *
 * Return: 0 on success, or a negative errno code on failure.
 */
int wonder_ssr_init(struct wonder_data *wonder);

/**
 * wonder_ssr_exit() - Deinitializes the System-Side Recovery module.
 * @wonder: Pointer to the main driver data structure.
 *
 * This function cleans up the resources used by the SSR module. It unregisters
 * the netdevice notifier to stop listening for device events and cancels any
 * pending recovery work to ensure a clean shutdown.
 */
void wonder_ssr_exit(struct wonder_data *wonder);

#endif /* __WONDER_SSR_H__ */
