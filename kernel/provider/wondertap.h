/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * Public API for the WonderTap Operations.
 *
 * This header file defines the complete API contract that a vendor-specific
 * driver must implement to integrate with the generic WonderTap operations.
 * It includes the core operations structure (wondertap_ops), all related
 * data structures, and the public functions for registration and control.
 */
#ifndef __WONDER_WONDERTAP_H__
#define __WONDER_WONDERTAP_H__

#include <linux/errno.h>
#include <linux/if_ether.h>

#define WONDERTAP_VHT_NSS_MAX   8
#define WONDERTAP_HE_NSS_MAX    8
#define WONDERTAP_EHT_NSS_MAX   8
#define WONDERTAP_HT_NSS_MAX    4

/** @brief Defines the PHY preamble/protocol for the TX rate. */
enum wondertap_rate_preamble {
	WONDERTAP_RATE_PREAMBLE_LEGACY = 0, /* 802.11a/g rates (non-HT) */
	WONDERTAP_RATE_PREAMBLE_HT = 1, /* 802.11n High Throughput */
	WONDERTAP_RATE_PREAMBLE_VHT = 2, /* 802.11ac Very High Throughput */
	WONDERTAP_RATE_PREAMBLE_HE  = 3, /* 802.11ax High Efficiency */
	WONDERTAP_RATE_PREAMBLE_EHT = 4, /* 802.11be Extremely High Throughput */
	WONDERTAP_RATE_PREAMBLE_MAX,
};

/**
 * @brief Defines which TX rate masks are active in a `wondertap_tx_rate_mask`
 * configuration.
 */
enum wondertap_tx_rate_mask_enable {
	/**
	 * @brief If set, the legacy_rates bitmap is valid and should be applied.
	 */
	WONDERTAP_RATEMASK_EN_LEGACY = BIT(WONDERTAP_RATE_PREAMBLE_LEGACY),

	/**
	 * @brief If set, the ht_mcs bitmap is valid and should be applied.
	 */
	WONDERTAP_RATEMASK_EN_HT     = BIT(WONDERTAP_RATE_PREAMBLE_HT),

	/**
	 * @brief If set, the vht_mcs bitmap is valid and should be applied.
	 */
	WONDERTAP_RATEMASK_EN_VHT    = BIT(WONDERTAP_RATE_PREAMBLE_VHT),

	/**
	 * @brief If set, the he_mcs bitmap is valid and should be applied.
	 */
	WONDERTAP_RATEMASK_EN_HE     = BIT(WONDERTAP_RATE_PREAMBLE_HE),

	/**
	 * @brief If set, the eht_mcs bitmap is valid and should be applied.
	 */
	WONDERTAP_RATEMASK_EN_EHT    = BIT(WONDERTAP_RATE_PREAMBLE_EHT),
};

/** @brief Defines the channel bandwidth. */
enum wondertap_rate_bw {
	WONDERTAP_RATE_BW_20 = 0,
	WONDERTAP_RATE_BW_40 = 1,
	WONDERTAP_RATE_BW_80 = 2,
	WONDERTAP_RATE_BW_160 = 3,
	WONDERTAP_RATE_BW_320 = 4,
};

/** @brief Defines the Guard Interval (GI). */
enum wondertap_rate_gi {
	WONDERTAP_RATE_GI_DEFAULT = 0, /* Driver uses default (e.g., Long GI or 0.8us) */
	WONDERTAP_RATE_GI_SHORT   = 1, /* Short GI for HT/VHT */
	WONDERTAP_RATE_GI_0_8_US  = 2, /* Specific HE GI values */
	WONDERTAP_RATE_GI_1_6_US  = 3,
	WONDERTAP_RATE_GI_3_2_US  = 4,
};

/** @brief Channel and bandwidth configuration. */
struct wondertap_set_freq_params {
	u32 freq;
	enum wondertap_rate_bw bandwidth;
};

/**
 * @brief Enumeration for the different types of hardware filters supported.
 *
 * This enum is used in the `wondertap_set_filter` function to specify which
 * filter is being configured.
 */
enum wondertap_filter_type {
	/**
	 * @brief Configures a filter based on the 802.11 frame's type and subtype.
	 */
	WONDERTAP_FILTER_TYPE_FRAME,
};

/**
 * @brief Parameters for configuring the BSSID filter.
 */
struct wondertap_bssid_filter_params {
	/**
	 * @brief Set to 'true' to enable the filter, 'false' to disable it.
	 */
	bool enabled;

	/**
	 * @brief The BSSID to filter on when the filter is enabled.
	 * This field is ignored if 'enabled' is false.
	 */
	u8 bssid[ETH_ALEN];
};

/**
 * @brief Parameters for configuring the frame type/subtype filter.
 */
struct wondertap_frame_filter_params {
	/**
	 * @brief Set to 'true' to enable the filter, 'false' to disable it.
	 */
	bool enabled;

	/**
	 * @brief The 802.11 frame type to match.
	 * This field is ignored if 'enabled' is false.
	 *
	 * The frame type defines the major category of the frame.
	 * Use standard Linux kernel definitions from <linux/ieee80211.h>:
	 * - IEEE80211_FTYPE_MGMT (0x0000): Management frames
	 * - IEEE80211_FTYPE_CTRL (0x0004): Control frames
	 * - IEEE80211_FTYPE_DATA (0x0008): Data frames
	 */
	u16 frame_type;

	/**
	 * @brief The 802.11 frame subtype to match.
	 * This field is ignored if 'enabled' is false.
	 *
	 * The subtype specifies the frame's exact purpose within its category.
	 * Use standard Linux kernel definitions from <linux/ieee80211.h>:
	 * - e.g., IEEE80211_STYPE_BEACON  (0x0080)
	 * - e.g., IEEE80211_STYPE_PROBE_REQ (0x0040)
	 * - e.g., IEEE80211_STYPE_QOS_DATA  (0x0080)
	 */
	u16 frame_subtype;
};

/**
 * @brief Configures the regulatory domain for the Wi-Fi hardware.
 *
 * @param handle The opaque driver instance handle.
 * @param country_code A two-character null-terminated string representing
 * the country code in ISO 3166-1 alpha-2 format (e.g., "US",
 * "GB", "JP", "TW").
 *
 * @return: 0 on success, or a negative errno code on failure.
 */

/**
 * @brief Parameters to configure a specific TX rate.
 *
 * This structure is used to define a complete transmission rate,
 * including its PHY characteristics and special features.
 */
struct wondertap_fixed_tx_rate_params {
	/**
	 * @brief The preamble/PHY type for this rate.
	 */
	enum wondertap_rate_preamble preamble;

	/**
	 * @brief The channel bandwidth for this rate.
	 */
	enum wondertap_rate_bw bw;

	/**
	 * @brief The Guard Interval (GI) for this rate.
	 */
	enum wondertap_rate_gi gi;

	/**
	 * @brief The number of spatial streams (NSS).
	 * Typically 1-4 for client devices. 0 is invalid.
	 */
	u8 nss;

	/**
	 * @brief The Modulation and Coding Scheme (MCS) index.
	 * - For HT (802.11n): 0-7 (up to 31 for 4 streams).
	 * - For VHT (802.11ac): 0-9.
	 * - For HE (802.11ax): 0-11.
	 * - For Legacy: This field is interpreted as the legacy rate index
	 * (e.g., index for 54 Mbps, 48 Mbps, etc.). Ignored by some drivers.
	 */
	u8 mcs;

	/** @brief Reserved for future use. */
	u8 reserved[2];
};

/**
 * @brief Per-packet transmission descriptor.
 *
 * This structure is intended to be stored in the `sk_buff->cb` control buffer
 * to provide per-packet transmission instructions to the underlying driver.
 */
struct wonder_txd {
	/** @brief True if the frame is a unicast transmission. */
	bool is_unicast;
	/** @brief The 802.11 frame type (e.g., IEEE80211_FTYPE_DATA). */
	u8 frame_type;
	/** @brief The Traffic Identifier (TID) for QoS. */
	u8 tid;
	/** @brief Reserved for future use. */
	u8 reserved[1];
};
// limit the wonder_txd size
static_assert(sizeof(struct wonder_txd) <= 48);

/**
 * @brief A unified structure to define the permitted transmission rates for
 * the rate control algorithm.
 */
struct wondertap_tx_rate_mask_params {
	/**
	 * @brief The maximum preamble/PHY type for this rate.
	 */
	enum wondertap_rate_preamble max_preamble;

	/**
	 * @brief The maximum channel bandwidth for this rate.
	 */
	enum wondertap_rate_bw max_bw;

	/**
	 * @brief The number of spatial streams (NSS).
	 * Typically 1-4 for client devices. 0 is invalid.
	 */
	u8 max_nss;

	/**
	 * @brief The Maximun Modulation and Coding Scheme (MCS) index.
	 * - For HT (802.11n): 0-7 (up to 31 for 4 streams).
	 * - For VHT (802.11ac): 0-9.
	 * - For HE (802.11ax): 0-11.
	 * - For Legacy: This field is interpreted as the legacy rate index
	 * (e.g., index for 54 Mbps, 48 Mbps, etc.). Ignored by some drivers.
	 */
	u8 max_mcs;

	/** @brief Reserved for future use. */
	u8 reserved[2];
};


/** @brief Supported hardware/software features. Used for get_capabilities. */
/** @brief Supported hardware/software features. */
struct wondertap_capability {
    /** @brief Capability structure version.
      * @note For the initial implementation, this must be set to 0.
      */
    u32 version;
    union {
        /* @brief All capability flags as a single 32-bit word. */
        u32 raw_bits;
        /* @brief Access to individual capability bits. */
        struct {
            /* @brief Dynamic rate adaptation is supported. */
            u32 rate_adaptation: 1;
            /* @brief STA (Station) coexistence is supported. */
            u32 sta_coexist: 1;
            /* @brief SAP (Soft AP) coexistence is supported. */
            u32 sap_coexist: 1;
            /* @brief P2P (Wi-Fi Direct) coexistence is supported. */
            u32 p2p_coexist: 1;
            /* @brief NAN (Neighbor Awareness Networking) coexistence is supported. */
            u32 nan_coexist: 1;
            /* @brief Ranging coexistence is supported. */
            u32 ranging_coexist: 1;
            /* @brief A-MSDU aggregation is supported. */
            u32 amsdu_aggregation: 1;
            /* @brief A-MPDU aggregation is supported. */
            u32 ampdu_aggregation: 1;
            /* @brief Dynamic frequency/channel changes are supported. */
            u32 dynamic_freq: 1;
            /* @brief Dynamic setting a fixed TX rate is supported. */
            u32 dynamic_fixed_tx_rate: 1;
            /* @brief Setting custom management frame retry limits is supported. */
            u32 custom_mgmt_retry_limit: 1;
            /* @brief Setting custom data frame retry limits is supported. */
            u32 custom_data_retry_limit: 1;
            /* @brief Frame type filtering is supported. */
            u32 frame_type_filter: 1;
            /* @brief Reserved for future use. Must be 0. */
            u32 reserved: 19;
        } bits;
    };
};

/** @brief Initialization parameters passed from the core to the vendor driver. */
struct wondertap_init_params {
    /**
     * @brief The initial channel and frequency for the interface.
     */
    struct wondertap_set_freq_params channel;

    /**
     * @brief The default fixed transmission rate.
     */
    struct wondertap_fixed_tx_rate_params tx_rate;

    /**
     * @brief The MAC address for this interface.
     */
    u8 mac_addr[ETH_ALEN];

    /**
     * @brief The BSSID to filter.
     */
    u8 bssid[ETH_ALEN];

    /**
     * @brief Max retransmission attempts for management frames.
     *
     * This value controls the retry behavior for the packet at the hardware
     * level. The interpretation is as follows:
     * - 0: The frame will be transmitted once with no retries.
     * - 1-254: The frame will be re-transmitted up to this many times if no
     *   acknowledgment is received.
     * - 255: The hardware will use an unlimited number of retries.
	 */
    u8 mgmt_retry_limit;

    /**
     * @brief Max retransmission attempts for data frames.
     *
     * This value controls the retry behavior for the packet at the hardware
     * level. The interpretation is as follows:
     * - 0: The frame will be transmitted once with no retries.
     * - 1-254: The frame will be re-transmitted up to this many times if no
     *   acknowledgment is received.
     * - 255: The hardware will use an unlimited number of retries.
     */
    u8 data_retry_limit;

    /**
     * @brief Aggregation feature control
     */
    u8 amsdu_enable: 1;
    u8 ampdu_enable: 1;

	/**
     * @brief Rate Adaptation feature control
     */
	u8 rate_adaptation_enable: 1;

    /**
     * @brief Reserved for future use and alignment.
     */
    u8 reserved1: 5;
    u8 reserved2;

    /**
     * @brief The two-letter ISO 3166 country code (e.g., "US", "TW").
     *
     * @note Includes the null terminator (\0), hence the size of 3.
     */
    char country_code[3];
    u8 reserved3;

    /**
     * @brief The initial transmission rate mask.
     */
	struct wondertap_tx_rate_mask_params tx_rate_mask;
};

/**
 * @brief Deinitialization parameters passed from the core to the vendor driver.
 */
struct wondertap_deinit_params {
    /**
     * @brief The two-letter ISO 3166 country code (e.g., "US", "TW").
     *
     * @note Includes the null terminator (\0), hence the size of 3.
     */
    char country_code[3];
    u8 reserved1;
};

/**
 * @brief Vendor operations implemented by the specific hardware driver.
 *
 * This structure is the core API between the generic layer and the
 * vendor-specific implementation. Each operation is strongly typed.
 */
struct wondertap_ops {
	/**
	 * @brief Initializes the vendor driver instance.
	 * @param handle Opaque handle, to be allocated by the vendor driver.
	 * @param params Initialization parameters.
	 * @return 0 on success, negative error code on failure.
	 */
	int (*init)(void **handle, const struct wondertap_init_params *params);

	/**
	 * @brief Deinitializes and frees the vendor driver instance.
	 * @param handle Opaque handle to the instance to be deinitialized.
	 */
	void (*deinit)(void *handle, const struct wondertap_deinit_params* params);

	/**
	 * @brief Sets the operating channel.
	 * @param handle The driver instance handle.
	 * @param params Channel and bandwidth information.
	 * @return 0 on success, negative error code.
	 */
	int (*set_freq)(void *handle, const struct wondertap_set_freq_params *params);

	/**
	 * @brief Configures a specific hardware packet filter.
	 *
	 * This is a versatile function that dispatches the configuration to the
	 * appropriate hardware filter based on the @filter_type. The caller must
	 * provide a pointer to a parameter structure that corresponds to the
	 * specified filter type.
	 *
	 * @param handle The opaque driver instance handle.
	 * @param filter_type The type of filter to configure, as defined in
	 * `enum wondertap_filter_type`.
	 * @param params A void pointer to the filter-specific parameter structure.
	 * - For WONDERTAP_FILTER_TYPE_BSSID: This must be a pointer to
	 * `struct wondertap_bssid_filter_params`.
	 * - For WONDERTAP_FILTER_TYPE_FRAME: This must be a pointer to
	 * `struct wondertap_frame_filter_params`.
	 *
	 * @return: 0 on success, or a negative errno code on failure.
	 */
	int (*set_filter)(void *handle, enum wondertap_filter_type filter_type,
			const void *params);

	/**
	 * @brief Configures a fixed transmission rate, overriding any
	 * automatic rate control algorithm.
	 * @handle: The opaque driver instance handle.
	 * @params: A pointer to the detailed TX rate parameters (preamble,
	 * MCS, NSS, etc.).
	 *
	 * @return: 0 on success, or a negative errno code on failure.
	 */
	int (*set_fixed_tx_rate)(void *handle, const struct wondertap_fixed_tx_rate_params *params);

	/**
	 * @brief Configures a mask of permitted transmission rates for
	 * the automatic rate control algorithm.
	 *
	 * @param handle The opaque driver instance handle.
	 * @param params A pointer to the rate mask structure defining the permitted rates.
	 *
	 * @return: 0 on success, or a negative errno code on failure.
	 */
	int (*set_tx_rate_mask)(void *handle, const struct wondertap_tx_rate_mask_params *params);

	/**
	 * @brief Retrieves a bitmask of supported vendor features.
	 * @param handle The driver instance handle.
	 * @param features A pointer to a struct to be filled with feature flags.
	 * @return 0 on success, negative error code.
	 */
	int (*get_capabilities)(void *handle, struct wondertap_capability *features);
};

/**
 * @brief Enumeration of WonderTap interface versions.
 *
 * This enum defines the supported versions of the WonderTap interface.
 */
enum wondertap_ver {
	WONDER_VERSION_1_0,
	WONDER_VERSION_1_1,
	WONDER_VERSION_1_2,
	WONDER_VERSION_1_3,
	WONDER_VERSION_1_4,
	WONDER_VERSION_1_4_1,
	WONDER_VERSION_1_5,
	WONDER_VERSION_1_5_1,
	WONDER_VERSION_MAX,
};

/**
 * @brief Private data structure for the WonderTap driver.
 *
 * This structure holds the version information and the operations
 * table for the specific vendor implementation.
 */
struct wondertap_priv {
	/** @brief The version of the WonderTap interface being used. */
	enum wondertap_ver ver;
	/** @brief Pointer to the vendor-specific operations table. */
	const struct wondertap_ops *wonder_ops;
};
#endif /* __WONDER_WONDERTAP_H__ */
