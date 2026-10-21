/* SPDX-License-Identifier: GPL-2.0-only */
/* Included by policy_mgr_get_set_utils.c after native PM helpers. */
#include <linux/moduleparam.h>

/* No changes to ordinary personas until explicitly enabled at module load. */
static unsigned int passthru_sta_coexist;
module_param(passthru_sta_coexist, uint, 0444);
MODULE_PARM_DESC(passthru_sta_coexist,
	"Fixed149 STA coexistence: 0=legacy, 1=DBS 2.4G, 2=DBS plus same149 SCC");

bool policy_mgr_opairshare_sta_coexist_enabled(void)
{
	unsigned int mode = READ_ONCE(passthru_sta_coexist);

	return mode == 1 || mode == 2;
}

/* Snapshot frequencies under the native list lock; never retain list indices
 * across an unlock. Only a single STA/PAS pair is eligible. Other concurrency
 * combinations continue through the native tables and restrictions.
 */
static bool opairshare_sta_counterpart(struct wlan_objmgr_psoc *psoc,
				enum policy_mgr_con_mode mode,
				uint32_t *other_freq, uint32_t *total)
{
	struct policy_mgr_psoc_priv_obj *pm_ctx;
	enum policy_mgr_con_mode other;
	uint32_t i, own = 0, opposite = 0, frequency = 0, count = 0;
	bool unexpected = false;

	if (!policy_mgr_opairshare_sta_coexist_enabled())
		return false;
	if (mode != PM_STA_MODE && mode != PM_PASSTHRU_MODE)
		return false;
	pm_ctx = policy_mgr_get_context(psoc);
	if (!pm_ctx)
		return false;
	other = mode == PM_STA_MODE ? PM_PASSTHRU_MODE : PM_STA_MODE;
	qdf_mutex_acquire(&pm_ctx->qdf_conc_list_lock);
	for (i = 0; i < MAX_NUMBER_OF_CONC_CONNECTIONS; i++) {
		if (!pm_conc_connection_list[i].in_use)
			continue;
		count++;
		if (pm_conc_connection_list[i].mode == other) {
			opposite++;
			frequency = pm_conc_connection_list[i].freq;
		} else if (pm_conc_connection_list[i].mode == mode) {
			own++;
		} else {
			unexpected = true;
		}
	}
	qdf_mutex_release(&pm_ctx->qdf_conc_list_lock);
	if (unexpected || opposite != 1 || own > 1 || !frequency)
		return false;
	*other_freq = frequency;
	*total = count;
	return true;
}

bool policy_mgr_opairshare_sta_pcl_override(struct wlan_objmgr_psoc *psoc,
				     enum policy_mgr_con_mode mode)
{
	uint32_t frequency, total;

	return opairshare_sta_counterpart(psoc, mode, &frequency, &total) && total == 1;
}

bool policy_mgr_opairshare_sta_freq_allowed(struct wlan_objmgr_psoc *psoc,
				     enum policy_mgr_con_mode mode,
				     uint32_t freq)
{
	uint32_t other, total, sta, pas;

	if (!freq || !opairshare_sta_counterpart(psoc, mode, &other, &total))
		return false;
	sta = mode == PM_STA_MODE ? freq : other;
	pas = mode == PM_PASSTHRU_MODE ? freq : other;
	/* Existing AWDL matcher/session implementation is fixed149 only. */
	if (pas != 5745)
		return false;
	if (sta == pas)
		return READ_ONCE(passthru_sta_coexist) == 2;
	if (sta < 2412 || sta > 2484 ||
	    !policy_mgr_is_hw_dbs_capable(psoc))
		return false;
	return !policy_mgr_2_freq_same_mac_in_dbs(psoc, sta, pas);
}
