// SPDX-License-Identifier: GPL-2.0
/*
 * Google Wonder WiFi Virtual Soft-MAC Driver
 *
 * This file implements a simple circular buffer for managing and reporting
 * fake TX status (TXS) frames back to the mac80211 subsystem. This is used
 * for rate control and other TX-related decisions in mac80211. The driver
 * can either queue status reports and send them later or report them directly.
 */

#include "mac80211_txs.h"
#include <linux/vmalloc.h>

/**
 * g_txs_queue - Global instance of the TX status queue.
 *
 * This queue is used to hold fake TX status reports before they are
 * passed to the mac80211 subsystem.
 */
static struct txs_queue g_txs_queue;

/**
 * get_txs_queue() - Get the global TX status queue.
 *
 * Return: A pointer to the global &g_txs_queue instance.
 */
static inline struct txs_queue *get_txs_queue(void)
{
	return &g_txs_queue;
}

/**
 * _queue_get_read_count() - Calculate the number of items available to be read.
 * @r: The current read index.
 * @w: The current write index.
 * @len: The total length of the buffer.
 *
 * Return: The number of items in the queue.
 */
static inline u16 _queue_get_read_count(u16 r, u16 w, u16 len)
{
	return (w < r) ? (len - r + w) : (w - r);
}

/**
 * _queue_get_write_count() - Calculate the free space available for writing.
 * @r: The current read index.
 * @w: The current write index.
 * @len: The total length of the buffer.
 *
 * Return: The number of empty slots in the queue.
 */
static inline u16 _queue_get_write_count(u16 r, u16 w, u16 len)
{
	return (w < r) ? (r - w - 1) : (len - w - 1 + r);
}

/**
 * queue_get_read_count() - Get the number of items to be read from the queue.
 * @ring: The TX status queue.
 *
 * Return: The number of pending status reports.
 */
static inline u16 queue_get_read_count(struct txs_queue *ring)
{
	u16 cnt;

	cnt = _queue_get_read_count(ring->read, ring->write, ring->len);
	return cnt > ring->len ? 0 : cnt;
}

/**
 * queue_get_write_count() - Get the number of free slots in the queue.
 * @ring: The TX status queue.
 *
 * Return: The number of available slots for new status reports.
 */
static inline u16 queue_get_write_count(struct txs_queue *ring)
{
	u16 cnt;

	cnt = _queue_get_write_count(ring->read, ring->write, ring->len);
	return cnt > ring->len ? 0 : cnt;
}

/**
 * queue_get_read_base() - Get the pointer to the current read position.
 * @ring: The TX status queue.
 *
 * Return: A pointer to the next status report to be read.
 */
static inline u8 *queue_get_read_base(struct txs_queue *ring)
{
	return (u8 *)ring->base + ring->read * ring->desc_sz;
}

/**
 * queue_get_write_base() - Get the pointer to the current write position.
 * @ring: The TX status queue.
 *
 * Return: A pointer to the next available slot for writing a status report.
 */
static inline u8 *queue_get_write_base(struct txs_queue *ring)
{
	return (u8 *)ring->base + ring->write * ring->desc_sz;
}

/**
 * queue_update_write() - Advance the write pointer.
 * @ring: The TX status queue.
 *
 * This moves the write index to the next position, wrapping around if needed.
 */
static inline void queue_update_write(struct txs_queue *ring)
{
	ring->write = (ring->write + 1) % ring->len;
}

/**
 * queue_update_read() - Advance the read pointer.
 * @ring: The TX status queue.
 *
 * This moves the read index to the next position, wrapping around if needed.
 */
static inline void queue_update_read(struct txs_queue *ring)
{
	ring->read = (ring->read + 1) % ring->len;
}

/**
 * wonder_txs_get_status() - Get an empty TX status slot from the queue.
 * @ts: The TX status queue.
 *
 * Return: A pointer to an empty &struct ieee80211_tx_status, or NULL if the
 * queue is full.
 */
static struct ieee80211_tx_status *wonder_txs_get_status(struct txs_queue *ts)
{
	struct ieee80211_tx_status *status;

	if (queue_get_write_count(ts) == 0)
		return NULL;
	status = (struct ieee80211_tx_status *)queue_get_write_base(ts);
	queue_update_write(ts);
	return status;
}

/**
 * wonder_txs_queue_init() - Initialize the TX status queue.
 *
 * Allocates memory for the TX status queue and associated tx_info structures.
 * Return: 0 on success, or a negative error code on failure.
 */
int wonder_txs_queue_init(void)
{
	int i;
	struct txs_queue *ts = get_txs_queue();

#define DEFAULT_TX_STATUS_LEN 1024
	ts->len = DEFAULT_TX_STATUS_LEN;
	ts->read = 0;
	ts->write = 0;
	ts->desc_sz = sizeof(struct ieee80211_tx_status);
	ts->base = vzalloc(ts->desc_sz * ts->len);
	if (!ts->base) {
		return -ENOMEM;
	}
	ts->tx_info = vzalloc(sizeof(struct ieee80211_tx_info) * ts->len);
	if (!ts->tx_info) {
		vfree(ts->base);
		return -ENOMEM;
	}
	for (i = 0; i < ts->len; i++) {
		ts->base[i].info = &ts->tx_info[i];
	}
	return 0;
}

/**
 * wonder_txs_queue_exit() - Clean up and free the TX status queue.
 */
void wonder_txs_queue_exit(void)
{
	struct txs_queue *ts = get_txs_queue();
	if (ts->base) {
		vfree(ts->base);
	}
	if (ts->tx_info) {
		vfree(ts->tx_info);
	}
	memset(ts, 0, sizeof(*ts));
}

/**
 * wonder_txs_enqueue() - Enqueue a fake TX status report for a given SKB.
 * @sta: The station the frame was sent to.
 * @skb: The socket buffer of the transmitted frame.
 *
 * This function generates a fake successful TX status report and adds it to
 * the queue to be processed later by wonder_txs_dequeue().
 *
 * Return: 0 on success, or -ENOBUFS if the queue is full.
 */
int wonder_txs_enqueue(struct ieee80211_sta *sta, struct sk_buff *skb)
{
	struct txs_queue *ts = get_txs_queue();
	struct ieee80211_tx_status *status = wonder_txs_get_status(ts);
	struct ieee80211_tx_info *info = IEEE80211_SKB_CB(skb);
	struct ieee80211_tx_rate *rate = &info->control.rates[0];

	if (status == NULL) {
		return -ENOBUFS;
	}
	/* assign tx_info */
	info = status->info;
	info->flags = IEEE80211_TX_STAT_ACK;
	info->status.ack_signal = -30;
	info->status.ampdu_len = 1;
	info->status.ampdu_ack_len = 1;
	info->status.flags |=
			IEEE80211_TX_STATUS_ACK_SIGNAL_VALID;
	info->status.rates[0].count = 1;
	info->status.rates[0].idx = rate->idx;
	info->status.rates[0].flags = rate->flags;
	/* Assign tx_status */
	status->sta = sta;
	return 0;
}

/**
 * wonder_txs_dequeue() - Dequeue and report all pending TX statuses.
 * @hw: Pointer to the ieee80211_hw structure.
 *
 * This function iterates through all pending TX status reports in the queue,
 * reports them to mac80211, and clears the queue.
 * Return: The number of status reports processed.
 */
int wonder_txs_dequeue(struct ieee80211_hw *hw)
{
	struct txs_queue *ts = get_txs_queue();
	struct ieee80211_tx_status *status;
	int cnt = queue_get_read_count(ts);
	int i;

	if (!cnt) {
		return 0;
	}
	for (i = 0 ; i < cnt; i++) {
		status = (struct ieee80211_tx_status *)queue_get_read_base(ts);
		ieee80211_tx_status_ext(hw, status);
		queue_update_read(ts);
	}
	return cnt;
}

/**
 * wonder_txs_direct_report() - Immediately report a fake TX status.
 * @hw: Pointer to the ieee80211_hw structure.
 * @sta: The station the frame was sent to.
 * @skb: The socket buffer of the transmitted frame.
 *
 * This function generates a fake successful TX status and reports it directly
 * to mac80211 without using the intermediate queue.
 */
void wonder_txs_direct_report(struct ieee80211_hw *hw, struct ieee80211_sta *sta,
		struct sk_buff *skb)
{
	struct ieee80211_tx_status status = { 0 };
	struct ieee80211_tx_info *info = IEEE80211_SKB_CB(skb);
	struct ieee80211_tx_rate *rate = &info->control.rates[0];

	/* assign tx_info */
	ieee80211_tx_info_clear_status(info);
	info->flags |= IEEE80211_TX_STAT_ACK;
	info->status.ack_signal = -30;
	info->status.ampdu_len = 1;
	info->status.ampdu_ack_len = 1;
	info->status.flags |=
			IEEE80211_TX_STATUS_ACK_SIGNAL_VALID;
	info->status.rates[0].count = 1;
	info->status.rates[0].idx = rate->idx;
	info->status.rates[0].flags = rate->flags;
	/* Assign tx_status */
	status.sta = sta;
	status.info = info;
	ieee80211_tx_status_ext(hw, &status);
}
