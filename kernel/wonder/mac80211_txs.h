/* SPDX-License-Identifier: GPL-2.0 */
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * Shared definitions for the TXS queue.
 */

#ifndef __WONDER_MAC80211_TXS_H__
#define __WONDER_MAC80211_TXS_H__

#include <net/mac80211.h>

struct txs_queue {
	struct ieee80211_tx_status *base;
	struct ieee80211_tx_info *tx_info;
	u32 len;
	u32 desc_sz;
	u16 read;
	u16 write;
};

int wonder_txs_queue_init(void);
void wonder_txs_queue_exit(void);
int wonder_txs_enqueue(struct ieee80211_sta *sta, struct sk_buff *skb);
int wonder_txs_dequeue(struct ieee80211_hw *hw);
void wonder_txs_direct_report(struct ieee80211_hw *hw, struct ieee80211_sta *sta,
		struct sk_buff *skb);


#endif /* __WONDER_MAC80211_TXS_H__ */