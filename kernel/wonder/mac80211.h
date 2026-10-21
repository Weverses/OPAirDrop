/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * Shared definitions and function prototypes for the Wonder driver.
 */

#ifndef __WONDER_MAC80211_H__
#define __WONDER_MAC80211_H__

#include <net/mac80211.h>
#include <net/cfg80211.h>
#include <net/ieee80211_radiotap.h>
#include "core.h"
extern char *physical_name;

void *wonder_mac80211_init(void);
void wonder_mac80211_exit(void);
int wonder_debugfs_init(void *data);
void wonder_debugfs_exit(void);
int wonder_features_init(struct wonder_data *wonder);
void wonder_features_exit(struct wonder_data *wonder);

#endif /* __WONDER_MAC80211_H__ */