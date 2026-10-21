/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * Shared definitions and function prototypes for the Wonder driver.
 */

#ifndef __WONDER_REG_H__
#define __WONDER_REG_H__

/* Define the regulatory domain for Worldwide */
static struct ieee80211_regdomain wonder_regdomain_world = {
	.alpha2 = "00",
	/* Set to specific DFS region if known for world, otherwise UNSET */
	.dfs_region = NL80211_DFS_UNSET,
	.n_reg_rules = 5,
	.reg_rules = {
		/* 2.4 GHz band: 2400-2483.5 MHz, 40 MHz BW, 20 dBm EIRP */
		REG_RULE(2400 - 10, 2483 + 10, 40, 20, 0, 0),
		/* 5 GHz UNII-1 band: 5150-5250 MHz, 80 MHz BW, 23 dBm EIRP */
		REG_RULE(5150 - 10, 5250 + 10, 80, 23, 0, 0),
		/* 5 GHz UNII-2A band: 5250-5350 MHz, 80 MHz BW, 23 dBm EIRP, DFS required */
		REG_RULE(5250 - 10, 5350 + 10, 80, 23, 0, NL80211_RRF_DFS),
		/* 5 GHz UNII-2C band: 5470-5725 MHz, 80 MHz BW, 30 dBm EIRP, DFS required */
		REG_RULE(5470 - 10, 5725 + 10, 80, 30, 0, NL80211_RRF_DFS),
		/* 5 GHz UNII-3 band: 5725-5850 MHz, 80 MHz BW, 30 dBm EIRP */
		REG_RULE(5725 - 10, 5850 + 10, 80, 30, 0, 0),
	},
};

static inline void wonder_get_regulator_domain(struct ieee80211_hw *hw)
{
	const struct ieee80211_regdomain *regdomain;
	int i;

	rcu_read_lock();
	regdomain = get_wiphy_regdom(hw->wiphy);
	if (regdomain) {
		wonder_info("Current regdomain: %c%c (DFS region: %d)\n",
				regdomain->alpha2[0], regdomain->alpha2[1],
				regdomain->dfs_region);
		wonder_info("n_reg_rules: %u\n", regdomain->n_reg_rules);
		for (i = 0; i < regdomain->n_reg_rules; i++) {
			const struct ieee80211_reg_rule *rule = &regdomain->reg_rules[i];

			wonder_info("Rule %d: %u KHz - %u KHz (max_bw: %u KHz), max_eirp: %u mBm,"
					" flags: 0x%x\n",
					i, rule->freq_range.start_freq_khz,
					rule->freq_range.end_freq_khz,
					rule->freq_range.max_bandwidth_khz,
					rule->power_rule.max_eirp, rule->flags);
		}
	}
	rcu_read_unlock();
}

static inline void wonder_set_custom_regulator(struct ieee80211_hw *hw)
{
	/* The driver manages its own regulatory domain */
	hw->wiphy->regulatory_flags = REGULATORY_WIPHY_SELF_MANAGED;
	/* Set a specific regulatory domain for the wiphy */
	regulatory_set_wiphy_regd(hw->wiphy, &wonder_regdomain_world);
}

#endif /* __WONDER_REG_H__ */