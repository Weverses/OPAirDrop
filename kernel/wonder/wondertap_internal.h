/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __WONDERTAP_INTERNAL_H__
#define __WONDERTAP_INTERNAL_H__

#include "include/wonder/wondertap.h"

enum wondertap_state {
    WONDERTAP_STATE_DOWN,
    WONDERTAP_STATE_UP,
};

/*
 * Bitflags to track which cached values have been explicitly set
 * by the user.
 */
#define WONDERTAP_CACHE_FREQ_SET         (1 << 0)
#define WONDERTAP_CACHE_TX_RATE_SET      (1 << 1)
#define WONDERTAP_CACHE_BSSID_SET        (1 << 2)
#define WONDERTAP_CACHE_COUNTRY_CODE_SET (1 << 3)

struct wondertap_data {
	void *vendor_handle;
	enum wondertap_state state;
	struct mutex lock;
	u8 mac_addr[ETH_ALEN];
	u32 cache_flags;
	struct wondertap_set_freq_params cached_freq;
	struct wondertap_frame_filter_params cached_frame_filter;
	struct wondertap_fixed_tx_rate_params cached_tx_rate;
	u8 cached_bssid[ETH_ALEN];
	char cached_country_code[3];
	enum wondertap_ver ver;
	struct device_node *wlan_node;
	const struct wondertap_ops *wonder_ops;
};

/**
 * wondertap_prep() - Prepares a wondertap data structure for initial use.
 * @wondertap: The wondertap data structure to prepare.
 *
 * This function initializes the core fields of a `wondertap_data`
 * structure. It sets the initial state to DOWN and initializes the mutex lock.
 * This should be called once when the structure is first allocated.
 */
static inline void wondertap_prep(struct wondertap_data *wondertap)
{
	wondertap->state = WONDERTAP_STATE_DOWN;
	mutex_init(&wondertap->lock);
}

/**
 * @brief Initializes the wondertap0 interface.
 *
 * This function serves as the entry point to initialize the wondertap0.
 * It finds the registered vendor operations and invokes the vendor-specific
 * init() callback to allocate and prepare the wondertap0 interface.
 *
 * @param wondertap A pointer to the wondertap instance data.
 * @param params A pointer to the initialization parameters required by the vendor
 * driver.
 *
 * Return: 0 on success, or a negative errno code on failure.
 */
int wondertap_init(struct wondertap_data *wondertap, const struct wondertap_init_params *params);

/**
 * @brief Deinitializes the wondertap0 interface.
 *
 * This function tears down a driver instance, calling the vendor-specific
 * deinit() callback to free all allocated resources and power down the
 * wondertap0 interface.
 *
 * @param wondertap A pointer to the wondertap instance data.
 */
void wondertap_deinit(struct wondertap_data *wondertap);

/**
 * @brief Sets the hardware operating channel and bandwidth.
 *
 * This function calls the vendor-specific set_freq() callback to configure
 * the radio to operate on a specific channel with a given bandwidth.
 *
 * @param wondertap A pointer to the wondertap instance data.
 * @param params A pointer to the channel and bandwidth configuration.
 *
 * Return: 0 on success, or a negative errno code on failure.
 */
int wondertap_set_freq(struct wondertap_data *wondertap, const struct wondertap_set_freq_params *params);

/**
 * @brief Configures a specific packet filter.
 *
 * This is a versatile function that dispatches the configuration to the
 * appropriate packet filter based on the @filter_type. The caller must
 * provide a pointer to a parameter structure that corresponds exactly to the
 * specified filter type.
 *
 * @param wondertap A pointer to the wondertap instance data.
 * @param filter_type The type of filter to configure, as defined in
 * `enum wondertap_filter_type`. This parameter determines how the
 * @params argument is interpreted by the driver.
 * @param params A void pointer to the filter-specific parameter structure. The
 * caller must cast this to the correct type based on @filter_type.
 *
 * Return: 0 on success, or a negative errno code on failure.
 */
int wondertap_set_filter(struct wondertap_data *wondertap, enum wondertap_filter_type filter_type,
                    const void *params);

/**
 * @brief Sets a fixed transmission rate for the hardware.
 *
 * This function calls the vendor-specific set_tx_rate() callback to force
 * the hardware to use a specific, fixed rate for transmissions.
 *
 * @param wondertap A pointer to the wondertap instance data.
 * @param params A pointer to the desired transmission rate parameters (MCS, NSS, GI, etc.).
 *
 * Return: 0 on success, or a negative errno code on failure.
 */
int wondertap_set_fixed_tx_rate(struct wondertap_data *wondertap, const struct wondertap_fixed_tx_rate_params *params);

/**
 * @brief Configures a mask of permitted transmission rates.
 *
 * @param wondertap A pointer to the wondertap instance data.
 * @param params A pointer to the rate mask structure that defines the permitted
 * rates for Legacy, HT, VHT, and HE PHY modes.
 *
 * Return: 0 on success, or a negative errno code on failure.
 */
int wondertap_set_tx_rate_mask(struct wondertap_data *wondertap, const struct wondertap_tx_rate_mask_params *params);

/**
 * @brief Configures the regulatory domain for the Wi-Fi hardware.
 *
 * @param wondertap A pointer to the wondertap instance data.
 * @param country_code A two-character null-terminated string representing the
 * country code in ISO 3166-1 alpha-2 format (e.g., "US",
 * "GB", "JP", "TW").
 *
 * Return: 0 on success, or a negative errno code on failure.
 */
int wondertap_set_reg(struct wondertap_data *wondertap, const char* country_code);

/**
 * @brief Retrieves supported vendor features.
 *
 * This function calls the vendor-specific get_capabilities() callback to query
 * the hardware and driver for its capabilities.
 *
 * @param wondertap A pointer to the wondertap instance data.
 * @param features A pointer to a struct wondertap_capability that will be populated
 * with the feature flags supported by the vendor.
 *
 * Return: 0 on success, or a negative errno code on failure.
 */
int wondertap_get_capabilities(struct wondertap_data *wondertap, struct wondertap_capability *capabilities);

/**
 * @brief Retrieves the MAC address.
 *
 * @param wondertap A pointer to the wondertap instance data.
 * @param mac_addr A pointer to a buffer of size ETH_ALEN (6 bytes) that
 * this function will populate with the wondertap0's MAC address.
 * The caller is responsible for allocating this buffer.
 *
 * Return: 0 on success, or a negative errno code on failure.
 */
int wondertap_get_interface_mac_address(struct wondertap_data *wondertap, u8 (*mac_addr)[ETH_ALEN]);

/**
 * @brief Sets the BSSID filter for the wondertap interface.
 *
 * @param wondertap A pointer to the wondertap instance data.
 * @param bssid A pointer to a 6-byte array containing the BSSID to
 * filter on.
 *
 * @return 0 on success, or a negative error code on failure.
 */
int wondertap_set_bssid_filter(struct wondertap_data *wondertap, const u8 *bssid);

/**
 * @brief Register a vendor's wondertap operations.
 *
 * @param ops A pointer to the vendor's statically defined wondertap_ops structure.
 * This pointer must remain valid until wondertap_unregister_ops() is
 * called.
 *
 * Return 0 on success.
 * @note Only one vendor implementation can be registered at a time.
 */
int wondertap_register_ops(const struct wondertap_ops *ops);

/**
 * @brief Unregister a vendor's wondertap operations.
 *
 * @param ops The exact same pointer to the wondertap_ops structure that was
 * previously passed to wondertap_register_ops(). The unregistration
 * will only proceed if this pointer matches the currently active one.
 */
void wondertap_unregister_ops(const struct wondertap_ops *ops);
#endif /* __WONDERTAP_INTERNAL_H__ */
