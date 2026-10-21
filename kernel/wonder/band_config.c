#include <linux/nl80211.h>
#include <linux/ieee80211.h>
#include <net/cfg80211.h>

/* --- Hardware Capability Definition (IEEE 802.11 bands and channels) --- */
static struct ieee80211_channel wonder_channels_2ghz[] = {
	/* 2.4 GHz Band (Channels 6) */
	{ .band = NL80211_BAND_2GHZ, .center_freq = 2437, .max_power = 20, .flags = 0, }, /* Ch 6 */
};

/* --- Dedicated Rate Tables for each Band --- */
/* 2.4 GHz Rates: 802.11b/g */
static struct ieee80211_rate wonder_rates_2ghz[] = {
	/* 802.11b/G rates (mandatory in 2.4 GHz) */
	{ .bitrate = 10, .flags = IEEE80211_RATE_MANDATORY_B | IEEE80211_RATE_MANDATORY_G },
	{ .bitrate = 20, .flags = IEEE80211_RATE_MANDATORY_B | IEEE80211_RATE_MANDATORY_G },
	{ .bitrate = 55, .flags = IEEE80211_RATE_MANDATORY_B | IEEE80211_RATE_MANDATORY_G },
	{ .bitrate = 110, .flags = IEEE80211_RATE_MANDATORY_B | IEEE80211_RATE_MANDATORY_G },

	/* 802.11g OFDM rates (mandatory for G, optional for B) */
	{ .bitrate = 60, .flags = IEEE80211_RATE_MANDATORY_G }, /* 6 Mbps */
	{ .bitrate = 90, .flags = 0 }, /* 9 Mbps */
	{ .bitrate = 120, .flags = IEEE80211_RATE_MANDATORY_G }, /* 12 Mbps */
	{ .bitrate = 180, .flags = 0 }, /* 18 Mbps */
	{ .bitrate = 240, .flags = IEEE80211_RATE_MANDATORY_G }, /* 24 Mbps */
	{ .bitrate = 360, .flags = 0 }, /* 36 Mbps */
	{ .bitrate = 480, .flags = 0 }, /* 48 Mbps */
	{ .bitrate = 540, .flags = 0 }, /* 54 Mbps */
};

static const struct ieee80211_sband_iftype_data wonder_sband_iftype_data_2ghz[] = {
	{
		.types_mask = BIT(NL80211_IFTYPE_ADHOC),
		.he_cap = {
			.has_he = true,
			.he_cap_elem = {
            /* MAC Capabilities */
            .mac_cap_info[0] = cpu_to_le16(IEEE80211_HE_MAC_CAP0_TWT_RES),
            .mac_cap_info[1] = cpu_to_le16(0),

            /* PHY Capabilities: Supports 20MHz and 40MHz */
            .phy_cap_info[0] = cpu_to_le32(IEEE80211_HE_PHY_CAP0_CHANNEL_WIDTH_SET_40MHZ_IN_2G),
            .phy_cap_info[1] = cpu_to_le32(0),
            },
		},
	},
};

struct ieee80211_supported_band wonder_band_2ghz = {
	.channels = wonder_channels_2ghz,
	.n_channels = ARRAY_SIZE(wonder_channels_2ghz),
	.band = NL80211_BAND_2GHZ,
	.bitrates = wonder_rates_2ghz, /* Use 2.4 GHz rates */
	.n_bitrates = ARRAY_SIZE(wonder_rates_2ghz),
	/* HT (802.11n) Capabilities */
	.ht_cap.ht_supported = true,
	.ht_cap.cap = IEEE80211_HT_CAP_SUP_WIDTH_20_40 |
			IEEE80211_HT_CAP_SM_PS |
			IEEE80211_HT_CAP_SGI_20 |
			IEEE80211_HT_CAP_MAX_AMSDU,
	.ht_cap.ampdu_factor = IEEE80211_HT_MAX_AMPDU_64K,
	.ht_cap.ampdu_density = IEEE80211_HT_MPDU_DENSITY_NONE,
	.ht_cap.mcs = {
		.rx_mask = { 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, },
		.rx_highest = cpu_to_le16(0),
		.tx_params = IEEE80211_HT_MCS_TX_DEFINED,
	},
	/* HE (802.11ax) Capabilities (nested attribute) */
	.n_iftype_data = 1,
	.iftype_data = wonder_sband_iftype_data_2ghz,
};

/* --- Hardware Capability Definition (IEEE 802.11 bands and channels) --- */
static struct ieee80211_channel wonder_channels_5ghz[] = {
	{
		.band = NL80211_BAND_5GHZ,
		.center_freq = 5180, /* Channel 36 */
		.hw_value = 36,
		.max_power = 23,
		.flags = 0,
	},
	{
		.band = NL80211_BAND_5GHZ,
		.center_freq = 5200, /* Channel 40 */
		.hw_value = 40,
		.max_power = 23,
		.flags = 0,
	},
	{
		.band = NL80211_BAND_5GHZ,
		.center_freq = 5220, /* Channel 44 */
		.hw_value = 44,
		.max_power = 23,
		.flags = 0,
	},
	{
		.band = NL80211_BAND_5GHZ,
		.center_freq = 5240, /* Channel 48 */
		.hw_value = 48,
		.max_power = 23,
		.flags = 0,
	},
	{
		.band = NL80211_BAND_5GHZ,
		.center_freq = 5745, /* Channel 149 */
		.hw_value = 149,
		.max_power = 30,
		.flags = 0,
	},
	{
		.band = NL80211_BAND_5GHZ,
		.center_freq = 5765, /* Channel 153 */
		.hw_value = 153,
		.max_power = 30,
		.flags = 0,
	},
	{
		.band = NL80211_BAND_5GHZ,
		.center_freq = 5785, /* Channel 157 */
		.hw_value = 157,
		.max_power = 30,
		.flags = 0,
	},
	{
		.band = NL80211_BAND_5GHZ,
		.center_freq = 5805, /* Channel 161 */
		.hw_value = 161,
		.max_power = 30,
		.flags = 0,
	},
};

/* 5 GHz Rates: 802.11a/g (OFDM only) */
static struct ieee80211_rate wonder_rates_5ghz[] = {
	/* OFDM rates (mandatory for 5 GHz operation) */
	{ .bitrate = 60, .flags = IEEE80211_RATE_MANDATORY_A },
	{ .bitrate = 90, .flags = 0 }, /* 9 Mbps */
	{ .bitrate = 120, .flags = IEEE80211_RATE_MANDATORY_A },
	{ .bitrate = 180, .flags = 0 }, /* 18 Mbps */
	{ .bitrate = 240, .flags = IEEE80211_RATE_MANDATORY_A },
	{ .bitrate = 360, .flags = 0 }, /* 36 Mbps */
	{ .bitrate = 480, .flags = 0 }, /* 48 Mbps */
	{ .bitrate = 540, .flags = 0 }, /* 54 Mbps */
};

static const struct ieee80211_sband_iftype_data wonder_sband_iftype_data_5ghz[] = {
	{
		.types_mask = BIT(NL80211_IFTYPE_ADHOC),
		.he_cap = {
			.has_he = true,
			.he_cap_elem = {
                .mac_cap_info[0] = cpu_to_le16(IEEE80211_HE_MAC_CAP0_TWT_RES),
                .mac_cap_info[1] = cpu_to_le16(0),

                /* PHY Capabilities: Supports up to 80 MHz in 5 GHz */
                .phy_cap_info[0] = cpu_to_le32(IEEE80211_HE_PHY_CAP0_CHANNEL_WIDTH_SET_40MHZ_80MHZ_IN_5G),
                .phy_cap_info[1] = cpu_to_le32(0),
            },
		},
	},
};

struct ieee80211_supported_band wonder_band_5ghz = {
	.channels = wonder_channels_5ghz,
	.n_channels = ARRAY_SIZE(wonder_channels_5ghz),
	.band = NL80211_BAND_5GHZ,
	.bitrates = wonder_rates_5ghz,
	.n_bitrates = ARRAY_SIZE(wonder_rates_5ghz),
	/* HT Capabilities */
	.ht_cap.ht_supported = true,
	.ht_cap.cap = IEEE80211_HT_CAP_SUP_WIDTH_20_40 |
			IEEE80211_HT_CAP_SM_PS |
			IEEE80211_HT_CAP_SGI_20 |
			IEEE80211_HT_CAP_MAX_AMSDU,
	.ht_cap.ampdu_factor = IEEE80211_HT_MAX_AMPDU_64K,
	.ht_cap.ampdu_density = IEEE80211_HT_MPDU_DENSITY_NONE,
	.ht_cap.mcs = {
		.rx_mask = { 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, },
		.rx_highest = cpu_to_le16(0), /* No support for high rates beyond standard MCS */
		.tx_params = IEEE80211_HT_MCS_TX_DEFINED,
	},

	/* VHT Capabilities */
	.vht_cap.vht_supported = true,
	.vht_cap.cap = IEEE80211_VHT_CAP_MAX_MPDU_LENGTH_11454 |
					IEEE80211_VHT_CAP_SHORT_GI_80 |
					IEEE80211_VHT_CAP_RXSTBC_1 |
					IEEE80211_VHT_CAP_SU_BEAMFORMER_CAPABLE,
	.vht_cap.vht_mcs = {
		.rx_mcs_map = cpu_to_le16(
			(IEEE80211_VHT_MCS_SUPPORT_0_9 << 0) |   /* NSS=1 */
			(IEEE80211_VHT_MCS_SUPPORT_0_9 << 2) |   /* NSS=2 (2x2 device) */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 4) | /* NSS=3 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 6) | /* NSS=4 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 8) | /* NSS=5 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 10) | /* NSS=6 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 12) | /* NSS=7 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 14)), /* NSS=8 */
		.tx_mcs_map = cpu_to_le16(
			(IEEE80211_VHT_MCS_SUPPORT_0_9 << 0) |   /* NSS=1 */
			(IEEE80211_VHT_MCS_SUPPORT_0_9 << 2) |   /* NSS=2 (2x2 device) */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 4) | /* NSS=3 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 6) | /* NSS=4 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 8) | /* NSS=5 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 10) | /* NSS=6 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 12) | /* NSS=7 */
			(IEEE80211_VHT_MCS_NOT_SUPPORTED << 14)), /* NSS=8 */
	},

	/* HE (802.11ax) Capabilities (nested attribute) */
	.n_iftype_data = 1,
	.iftype_data = wonder_sband_iftype_data_5ghz,
};
