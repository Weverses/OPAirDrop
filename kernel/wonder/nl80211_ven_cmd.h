/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * Definitions for NL80211 vendor commands.
 */

#ifndef __WONDER_NL80211_VEN_CMD_H__
#define __WONDER_NL80211_VEN_CMD_H__

#include <net/cfg80211.h>

/**
 * @brief Retrieves the array of supported vendor commands.
 *
 * @return A const pointer to the vendor command array.
 */
const struct wiphy_vendor_command *wonder_get_wiphy_vendor_command(void);

/**
 * @brief Retrieves the number of elements in the vendor command array.
 *
 * @return The size of the vendor command array.
 */
size_t wonder_get_wiphy_vendor_command_array_size(void);

#endif /* __WONDER_NL80211_VEN_CMD_H__ */