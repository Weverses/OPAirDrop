#define LOG_MODULE_NAME "ven_cmd"

#include <net/cfg80211.h>
#include <net/netlink.h>
#include <net/mac80211.h>

#include "wondertap_internal.h"
#include "wonder_log.h"
#include "include/wonder/wonder_ven_cmd.h"
#include "nl80211_ven_cmd.h"

/* @brief Internal helpers to map raw byte sizes to NLA types */
#define _WONDER_TYPE_MAP_1  NLA_U8
#define _WONDER_TYPE_MAP_2  NLA_U16
#define _WONDER_TYPE_MAP_4  NLA_U32
#define _WONDER_TYPE_MAP_8  NLA_U64

#define _WONDER_IND_TYPE_MAP(val) _WONDER_TYPE_MAP_##val
#define _WONDER_GET_TYPE(size) _WONDER_IND_TYPE_MAP(size)

/**
 * @brief Auto-generate policy for integer attributes
 */
#define WONDER_POL_SCALAR(attr) \
	[attr] = { .type = _WONDER_GET_TYPE(attr##_SIZE) }

/**
 * @brief Auto-generate policy for fixed-size binary blobs
 */
#define WONDER_POL_BINARY(attr) \
	[attr] = { .type = NLA_BINARY, .len = attr##_SIZE }

/**
 * @brief Auto-generate policy for fixed-max-length strings
 */
#define WONDER_POL_STRING(attr) \
	[attr] = { .type = NLA_NUL_STRING, .len = attr##_SIZE }

static const struct nla_policy
wonder_set_frequency_policy[WONDER_VEN_ATTR_CHANNEL_ATTR_MAX + 1] = {
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_CHANNEL_FREQ),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_CHANNEL_BANDWIDTH),
};

static const struct nla_policy
wonder_filter_params_policy[WONDER_VEN_ATTR_FILTER_PARAM_MAX + 1] = {
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_FILTER_BSSID_ENABLED),
	WONDER_POL_BINARY(WONDER_VEN_ATTR_FILTER_BSSID_ADDR),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_FILTER_FRAME_ENABLED),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_FILTER_FRAME_TYPE),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_FILTER_FRAME_SUBTYPE),
};

static const struct nla_policy
wonder_fixed_rate_policy[WONDER_VEN_ATTR_FIXED_TX_RATE_MAX + 1] = {
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_FIXED_TX_RATE_PREAMBLE),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_FIXED_TX_RATE_BW),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_FIXED_TX_RATE_GI),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_FIXED_TX_RATE_NSS),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_FIXED_TX_RATE_MCS),
};

static const struct nla_policy
wonder_tx_rate_mask_policy[WONDER_VEN_ATTR_TX_RATE_TEST_MAX + 1] = {
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_TX_RATE_TEST_PREAMBLE),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_TX_RATE_TEST_BW),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_TX_RATE_TEST_NSS),
	WONDER_POL_SCALAR(WONDER_VEN_ATTR_TX_RATE_TEST_MCS),
};

static const struct nla_policy
wonder_set_reg_policy[WONDER_VEN_ATTR_REG_MAX + 1] = {
	WONDER_POL_STRING(WONDER_VEN_ATTR_REG_COUNTRY_CODE),
};

static int wonder_vendor_cmd_set_frequency(struct wiphy *wiphy,
					   struct wireless_dev *wdev,
					   const void *data, int data_len)
{
	struct ieee80211_hw *hw = wiphy_to_ieee80211_hw(wiphy);
	struct wonder_data *wonder = hw->priv;
	struct nlattr *tb[WONDER_VEN_ATTR_CHANNEL_ATTR_MAX + 1];
	struct wondertap_set_freq_params params;

	if (nla_parse(tb, WONDER_VEN_ATTR_CHANNEL_ATTR_MAX, data, data_len,
				 wonder_set_frequency_policy, NULL) < 0) {
		wonder_error("Invalid attributes\n");
		return -EINVAL;
	}

	/* Check that mandatory attributes are present */
	if (!tb[WONDER_VEN_ATTR_CHANNEL_FREQ] || !tb[WONDER_VEN_ATTR_CHANNEL_BANDWIDTH]) {
		wonder_error("Missing mandatory attributes\n");
		return -EINVAL;
	}

	/* Populate the params struct with the received data */
	params.freq = nla_get_u32(tb[WONDER_VEN_ATTR_CHANNEL_FREQ]);
	params.bandwidth = nla_get_u16(tb[WONDER_VEN_ATTR_CHANNEL_BANDWIDTH]);

	wonder_info("SET_FREQUENCY: freq=%u MHz, bandwidth=%u\n",
		params.freq, params.bandwidth);

	return wondertap_set_freq(&wonder->wondertap_data, &params);
}

static int wonder_vendor_cmd_set_filter(struct wiphy *wiphy,
								struct wireless_dev *wdev,
								const void *data, int data_len)
{
	struct ieee80211_hw *hw = wiphy_to_ieee80211_hw(wiphy);
	struct wonder_data *wonder = hw->priv;
	struct nlattr *tb[WONDER_VEN_ATTR_TOP_FILTER_MAX + 1];
	struct nlattr *params_tb[WONDER_VEN_ATTR_FILTER_PARAM_MAX + 1];
	enum wonder_vendor_filter_type filter_type;
	int ret;

	/* Parse top-level attributes */
	if (nla_parse(tb, WONDER_VEN_ATTR_TOP_FILTER_MAX, data, data_len, NULL, NULL) < 0) {
		wonder_error("Failed to parse top-level attributes\n");
		return -EINVAL;
	}

	if (!tb[WONDER_VEN_ATTR_TOP_FILTER_TYPE] || !tb[WONDER_VEN_ATTR_TOP_FILTER_PARAMS]) {
		wonder_error("Missing mandatory attributes: FILTER_TYPE or FILTER_PARAMS\n");
		return -EINVAL;
	}

	filter_type = nla_get_u32(tb[WONDER_VEN_ATTR_TOP_FILTER_TYPE]);
	wonder_info("Configuring filter type: %u\n", filter_type);

	if (nla_parse(params_tb, WONDER_VEN_ATTR_FILTER_PARAM_MAX,
						nla_data(tb[WONDER_VEN_ATTR_TOP_FILTER_PARAMS]),
						nla_len(tb[WONDER_VEN_ATTR_TOP_FILTER_PARAMS]),
							wonder_filter_params_policy, NULL) < 0) {
		wonder_error("2 Invalid attributes\n");
		return -EINVAL;
	}

	switch (filter_type) {
	case WONDER_VEN_ATTR_FILTER_TYPE_BSSID: {
		struct wondertap_bssid_filter_params params = {};

		if (!params_tb[WONDER_VEN_ATTR_FILTER_BSSID_ENABLED]) {
			wonder_error("BSSID filter missing ENABLED attribute\n");
			return -EINVAL;
		}

		params.enabled = nla_get_u8(
			params_tb[WONDER_VEN_ATTR_FILTER_BSSID_ENABLED]);
		wonder_info("BSSID filter enabled: %s\n", params.enabled ? "true" : "false");
		if (params.enabled) {
			if (!params_tb[WONDER_VEN_ATTR_FILTER_BSSID_ADDR])
				return -EINVAL;
			nla_memcpy(params.bssid,
						params_tb[WONDER_VEN_ATTR_FILTER_BSSID_ADDR],
						ETH_ALEN);
			wonder_info("BSSID filter address: %02X:XX:XX:XX:XX:%02X\n",
				params.bssid[0], params.bssid[5]);
		}
		ret = wondertap_set_bssid_filter(&wonder->wondertap_data, params.bssid);
		break;
	}
	case WONDER_VEN_ATTR_FILTER_TYPE_FRAME: {
		struct wondertap_frame_filter_params params = {};

		if (!params_tb[WONDER_VEN_ATTR_FILTER_FRAME_ENABLED]) {
			wonder_error("Frame filter missing ENABLED attribute\n");
			return -EINVAL;
		}

		params.enabled = nla_get_u8(
			params_tb[WONDER_VEN_ATTR_FILTER_FRAME_ENABLED]);
		wonder_info("Frame filter enabled: %s\n", params.enabled ? "true" : "false");
		if (params.enabled) {
			if (!params_tb[WONDER_VEN_ATTR_FILTER_FRAME_TYPE] ||
				!params_tb[WONDER_VEN_ATTR_FILTER_FRAME_SUBTYPE]) {
				wonder_error(
					"Frame filter is enabled but missing TYPE or SUBTYPE attributes\n");
				return -EINVAL;
			}
			params.frame_type = nla_get_u16(
				params_tb[WONDER_VEN_ATTR_FILTER_FRAME_TYPE]);
			params.frame_subtype = nla_get_u16(
				params_tb[WONDER_VEN_ATTR_FILTER_FRAME_SUBTYPE]);
			wonder_info("Frame filter type=0x%04x, subtype=0x%04x\n",
						params.frame_type, params.frame_subtype);
		}
		ret = wondertap_set_filter(&wonder->wondertap_data,
			WONDERTAP_FILTER_TYPE_FRAME, &params);
		break;
	}
	default:
		ret = -EINVAL;
		break;
	}

	return ret;
}

static int wonder_vendor_cmd_set_fixed_tx_rate(struct wiphy *wiphy,
								struct wireless_dev *wdev,
								const void *data, int data_len)
{
	struct ieee80211_hw *hw = wiphy_to_ieee80211_hw(wiphy);
	struct wonder_data *wonder = hw->priv;
	struct nlattr *tb[WONDER_VEN_ATTR_FIXED_TX_RATE_MAX + 1];
	struct wondertap_fixed_tx_rate_params params = {};

	if (nla_parse(tb, WONDER_VEN_ATTR_FIXED_TX_RATE_MAX, data, data_len,
					wonder_fixed_rate_policy, NULL) < 0) {
		wonder_error("Failed to parse fixed TX rate attributes\n");
		return -EINVAL;
	}

	/* Check that all mandatory attributes are present */
	if (!tb[WONDER_VEN_ATTR_FIXED_TX_RATE_PREAMBLE] || !tb[WONDER_VEN_ATTR_FIXED_TX_RATE_BW] ||
		!tb[WONDER_VEN_ATTR_FIXED_TX_RATE_GI] || !tb[WONDER_VEN_ATTR_FIXED_TX_RATE_NSS] ||
		!tb[WONDER_VEN_ATTR_FIXED_TX_RATE_MCS]) {
		wonder_error("Missing mandatory attributes for fixed TX rate\n");
		return -EINVAL;
	}

	/* Populate the params struct with the received data */
	params.preamble = nla_get_u32(tb[WONDER_VEN_ATTR_FIXED_TX_RATE_PREAMBLE]);
	params.bw = nla_get_u16(tb[WONDER_VEN_ATTR_FIXED_TX_RATE_BW]);
	params.gi = nla_get_u32(tb[WONDER_VEN_ATTR_FIXED_TX_RATE_GI]);
	params.nss = nla_get_u8(tb[WONDER_VEN_ATTR_FIXED_TX_RATE_NSS]);
	params.mcs = nla_get_u8(tb[WONDER_VEN_ATTR_FIXED_TX_RATE_MCS]);

	wonder_info("Set fixed TX rate: preamble=%u, bw=%u, gi=%u, nss=%u, mcs=%u\n",
				params.preamble, params.bw, params.gi, params.nss, params.mcs);

	return wondertap_set_fixed_tx_rate(&wonder->wondertap_data, &params);
}

static int wonder_vendor_cmd_set_tx_rate_test(struct wiphy *wiphy,
								struct wireless_dev *wdev,
								const void *data, int data_len)
{
	struct ieee80211_hw *hw = wiphy_to_ieee80211_hw(wiphy);
	struct wonder_data *wonder = hw->priv;
	struct nlattr *tb[WONDER_VEN_ATTR_TX_RATE_TEST_MAX + 1];
	struct wondertap_tx_rate_mask_params tx_rate_params = {};

	if (nla_parse(tb, WONDER_VEN_ATTR_TX_RATE_TEST_MAX, data, data_len,
					wonder_tx_rate_mask_policy, NULL) < 0) {
		wonder_error("Failed to parse TX rate mask attributes\n");
		return -EINVAL;
	}

	/* Check that all mandatory attributes are present */
	if (!tb[WONDER_VEN_ATTR_TX_RATE_TEST_PREAMBLE] || !tb[WONDER_VEN_ATTR_TX_RATE_TEST_BW] ||
		!tb[WONDER_VEN_ATTR_TX_RATE_TEST_NSS] || !tb[WONDER_VEN_ATTR_TX_RATE_TEST_MCS]) {
		wonder_error("Missing mandatory attributes for TX rate\n");
		return -EINVAL;
	}

	// Test only
	tx_rate_params.max_preamble = nla_get_u32(tb[WONDER_VEN_ATTR_TX_RATE_TEST_PREAMBLE]);
	tx_rate_params.max_bw = nla_get_u16(tb[WONDER_VEN_ATTR_TX_RATE_TEST_BW]);
	tx_rate_params.max_nss = nla_get_u8(tb[WONDER_VEN_ATTR_TX_RATE_TEST_NSS]);
	tx_rate_params.max_mcs = nla_get_u8(tb[WONDER_VEN_ATTR_TX_RATE_TEST_MCS]);
	wonder_info("Apply TX rate: max_preamble=%u, max_bw=%u, max_nss=%u, max_mcs=%u\n",
		tx_rate_params.max_preamble, tx_rate_params.max_bw, tx_rate_params.max_nss,
		tx_rate_params.max_mcs);
	wondertap_set_tx_rate_mask(&wonder->wondertap_data, &tx_rate_params);

	return 0;
}

static int wonder_vendor_cmd_set_reg(struct wiphy *wiphy,
								struct wireless_dev *wdev,
								const void *data, int data_len)
{
	struct ieee80211_hw *hw = wiphy_to_ieee80211_hw(wiphy);
	struct wonder_data *wonder = hw->priv;
	struct nlattr *tb[WONDER_VEN_ATTR_REG_MAX + 1];
	const char *country_code;


	if (nla_parse(tb, WONDER_VEN_ATTR_REG_MAX, data, data_len, wonder_set_reg_policy,
			NULL) < 0) {
		wonder_error("Failed to parse attributes\n");
		return -EINVAL;
	}

	/* Check that the mandatory attribute is present */
	if (!tb[WONDER_VEN_ATTR_REG_COUNTRY_CODE]) {
		wonder_error("Missing COUNTRY_CODE attribute\n");
		return -EINVAL;
	}

	/* Retrieve the string from the attribute */
	country_code = nla_data(tb[WONDER_VEN_ATTR_REG_COUNTRY_CODE]);

	wonder_info("Setting regulatory country code to: %s\n", country_code);

	return wondertap_set_reg(&wonder->wondertap_data, country_code);
}

static int wonder_vendor_cmd_get_if_mac_addr(struct wiphy *wiphy,
								struct wireless_dev *wdev,
								const void *data, int data_len)
{
	struct ieee80211_hw *hw = wiphy_to_ieee80211_hw(wiphy);
	struct wonder_data *wonder = hw->priv;
	struct sk_buff *skb;
	u8 mac_addr[ETH_ALEN];

	wondertap_get_interface_mac_address(&wonder->wondertap_data, &mac_addr);

	wonder_info("Handling GET_MAC. Found MAC: %02X:XX:XX:XX:XX:%02X\n",
		mac_addr[0], mac_addr[5]);

	skb = cfg80211_vendor_cmd_alloc_reply_skb(wiphy, nla_total_size(ETH_ALEN));
	if (!skb) {
		wonder_error("Failed to allocate reply skb\n");
		return -ENOMEM;
	}

	/* Put the MAC address attribute into the skb. */
	if (nla_put(skb, WONDER_VEN_ATTR_IF_ADDR_MAC_ADDR, ETH_ALEN, mac_addr)) {
		pr_err("Failed to put MAC attribute\n");
		kfree_skb(skb);
		return -EMSGSIZE;
	}

	return cfg80211_vendor_cmd_reply(skb);
}

static const struct wiphy_vendor_command wonder_vendor_cmds[] = {
	{
		.info = {
			.vendor_id = WONDER_VENDOR_ID,
			.subcmd = WONDER_VEN_SUBCMD_SET_FREQUENCY
		},
		.flags = WIPHY_VENDOR_CMD_NEED_WDEV | WIPHY_VENDOR_CMD_NEED_NETDEV,
		.doit = wonder_vendor_cmd_set_frequency,
		.policy = VENDOR_CMD_RAW_DATA,
	},
	{
		.info = {
			.vendor_id = WONDER_VENDOR_ID,
			.subcmd = WONDER_VEN_SUBCMD_SET_FILTER
		},
		.flags = WIPHY_VENDOR_CMD_NEED_WDEV | WIPHY_VENDOR_CMD_NEED_NETDEV,
		.doit = wonder_vendor_cmd_set_filter,
		.policy = VENDOR_CMD_RAW_DATA,
	},
	{
		.info = {
			.vendor_id = WONDER_VENDOR_ID,
			.subcmd = WONDER_VEN_SUBCMD_SET_FIXED_TX_RATE
		},
		.flags = WIPHY_VENDOR_CMD_NEED_WDEV | WIPHY_VENDOR_CMD_NEED_NETDEV,
		.doit = wonder_vendor_cmd_set_fixed_tx_rate,
		.policy = VENDOR_CMD_RAW_DATA,
	},
	{
		.info = {
			.vendor_id = WONDER_VENDOR_ID,
			.subcmd = WONDER_VEN_SUBCMD_SET_TX_RATE_TEST
		},
		.flags = WIPHY_VENDOR_CMD_NEED_WDEV | WIPHY_VENDOR_CMD_NEED_NETDEV,
		.doit = wonder_vendor_cmd_set_tx_rate_test,
		.policy = VENDOR_CMD_RAW_DATA,
	},
	{
		.info = {
			.vendor_id = WONDER_VENDOR_ID,
			.subcmd = WONDER_VEN_SUBCMD_SET_REGULATORY
		},
		.flags = WIPHY_VENDOR_CMD_NEED_WDEV | WIPHY_VENDOR_CMD_NEED_NETDEV,
		.doit = wonder_vendor_cmd_set_reg,
		.policy = VENDOR_CMD_RAW_DATA,
	},
	{
		.info = {
			.vendor_id = WONDER_VENDOR_ID,
			.subcmd = WONDER_VEN_SUBCMD_GET_IF_MAC_ADDR
		},
		.flags = WIPHY_VENDOR_CMD_NEED_WDEV | WIPHY_VENDOR_CMD_NEED_NETDEV,
		.doit = wonder_vendor_cmd_get_if_mac_addr,
		.policy = VENDOR_CMD_RAW_DATA,
	},
};

const struct wiphy_vendor_command *wonder_get_wiphy_vendor_command(void) {
	return wonder_vendor_cmds;
}

size_t wonder_get_wiphy_vendor_command_array_size(void) {
	return ARRAY_SIZE(wonder_vendor_cmds);
}
