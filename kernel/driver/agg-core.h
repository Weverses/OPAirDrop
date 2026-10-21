/* SPDX-License-Identifier: GPL-2.0-only */
/* stage54: pure helpers for the OPAirShare AWDL bridge.
 *
 *   * classification of a queued AWDL QoS-data frame (A-MSDU eligibility and
 *     "plain TCP ACK" recognition),
 *   * the join rule that decides whether a queued frame may ride in the same
 *     A-MSDU as the frame at the head of the queue,
 *   * the A-MSDU subframe layout (IEEE 802.11-2016 9.3.2.2.2: DA | SA | Length
 *     big-endian | MSDU | pad to 4 between subframes, none after the last).
 *
 * This file is compiled twice from the SAME bytes: inside the kernel module
 * (stage54-agg.py inlines it into wma_opairshare_pas_mgmt.h) and by the host selftest
 * (stage54-agg-selftest.c).  It therefore uses nothing but uint*_t, size_t,
 * memcmp/memcpy/memset; the includer provides those.  No locking, no
 * allocation, no skb: callers hand it plain byte pointers.
 *
 * MSDU of an AWDL data frame, as seen on the air (STAGE54 capture dump):
 *     LLC/SNAP  aa aa 03 00 17 f2 08 00                       (8)
 *     AWDL hdr  03 04 <seq16 LE> 00 00 86 dd                   (8)
 *     IPv6 packet                                              (40 + ...)
 */
#ifndef STAGE54_AGG_CORE_H
#define STAGE54_AGG_CORE_H

#define OPAIRSHARE_AGG_QOS_HDR	26u	/* 802.11 QoS data header, no HTC */
#define OPAIRSHARE_AGG_SUB_HDR	14u	/* DA(6) SA(6) Length(2) */
#define OPAIRSHARE_AGG_LLC_LEN	8u
#define OPAIRSHARE_AGG_AWDL_LEN	8u
#define OPAIRSHARE_AGG_IPV6_LEN	40u
#define OPAIRSHARE_AGG_MAX_MSDU	2304u
#define OPAIRSHARE_AGG_HARD_MAX	5u	/* subframes per A-MSDU, hard ceiling */
/* LLC + AWDL header + IPv6 + TCP with the 12-byte timestamp option */
#define OPAIRSHARE_AGG_PEEK		(OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN + OPAIRSHARE_AGG_IPV6_LEN + 32u)

struct opairshare_agg_meta {
	uint8_t  eligible;	/* unicast AWDL QoS data that may be A-MSDU'd */
	uint8_t  ack_pure;	/* plain TCP ACK: no payload, no SACK, flags == ACK */
	uint8_t  ra[6];
	uint8_t  ta[6];
	uint8_t  qos[2];
	uint16_t msdu_len;	/* bytes following the 26-byte QoS header */
	uint8_t  flow[36];	/* IPv6 src + dst + TCP src/dst port */
	uint32_t ack_seq;
};

/* Subframe padding: aligns (14 + msdu_len) to 4; written BETWEEN subframes only. */
static inline unsigned int opairshare_agg_pad(unsigned int msdu_len)
{
	return (4u - ((OPAIRSHARE_AGG_SUB_HDR + msdu_len) & 3u)) & 3u;
}

static inline uint32_t opairshare_agg_be32(const uint8_t *p)
{
	return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
	       ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

/* h:      the 26-byte 802.11 header exactly as queued
 * peek:   the first peek_len (<= OPAIRSHARE_AGG_PEEK) bytes of the MSDU
 * msdu_len: total MSDU length (bytes after the 26-byte header)
 */
static inline void opairshare_agg_parse(const uint8_t *h, const uint8_t *peek,
				  size_t peek_len, size_t msdu_len,
				  struct opairshare_agg_meta *m)
{
	static const uint8_t llc[OPAIRSHARE_AGG_LLC_LEN] =
		{0xaa, 0xaa, 0x03, 0x00, 0x17, 0xf2, 0x08, 0x00};
	const uint8_t *awdl, *ip, *tcp;
	unsigned int doff;

	memset(m, 0, sizeof(*m));
	/* QoS data, to-DS/from-DS clear, unprotected, no +HTC/order bit. */
	if (h[0] != 0x88 || h[1] != 0x00)
		return;
	if (h[4] & 1u)			/* group-addressed: never aggregated */
		return;
	if (msdu_len < OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN ||
	    msdu_len > OPAIRSHARE_AGG_MAX_MSDU)
		return;
	if (peek_len < OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN ||
	    memcmp(peek, llc, sizeof(llc)))
		return;
	memcpy(m->ra, h + 4, 6);
	memcpy(m->ta, h + 10, 6);
	m->qos[0] = h[24];
	m->qos[1] = h[25];
	m->msdu_len = (uint16_t)msdu_len;
	m->eligible = 1;

	/* Plain-TCP-ACK recognition (used only by the ACK filter). */
	if (peek_len < OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN + OPAIRSHARE_AGG_IPV6_LEN + 20u)
		return;
	awdl = peek + OPAIRSHARE_AGG_LLC_LEN;
	if (awdl[0] != 0x03 || awdl[1] != 0x04 || awdl[6] != 0x86 || awdl[7] != 0xdd)
		return;
	ip = awdl + OPAIRSHARE_AGG_AWDL_LEN;
	if ((ip[0] >> 4) != 6 || ip[6] != 6)	/* IPv6, next header TCP */
		return;
	tcp = ip + OPAIRSHARE_AGG_IPV6_LEN;
	doff = (unsigned int)(tcp[12] >> 4) * 4u;
	if (doff != 20u && doff != 32u)
		return;
	if (peek_len < OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN + OPAIRSHARE_AGG_IPV6_LEN + doff)
		return;
	/* Only NOP NOP TIMESTAMP: any SACK/other option keeps the ACK untouched. */
	if (doff == 32u && !(tcp[20] == 1 && tcp[21] == 1 && tcp[22] == 8 && tcp[23] == 10))
		return;
	if (tcp[13] != 0x10)			/* flags must be exactly ACK */
		return;
	if ((((unsigned int)ip[4] << 8) | ip[5]) != doff)	/* no TCP payload */
		return;
	if (msdu_len != OPAIRSHARE_AGG_LLC_LEN + OPAIRSHARE_AGG_AWDL_LEN + OPAIRSHARE_AGG_IPV6_LEN + doff)
		return;
	memcpy(m->flow, ip + 8, 32);		/* src + dst address */
	memcpy(m->flow + 32, tcp, 4);		/* src + dst port */
	m->ack_seq = opairshare_agg_be32(tcp + 8);
	m->ack_pure = 1;
}

/* May `cand` follow the frames already gathered?  Returns the new body length
 * (everything after the 26-byte header) or 0 if not.
 *   n_have      subframes already in the aggregate (>= 1, the leader counts)
 *   body_have   body length so far (last subframe unpadded)
 *   last_len    MSDU length of the last gathered subframe
 * Order is preserved by the caller stopping at the first refusal.
 */
static inline uint32_t opairshare_agg_join(const struct opairshare_agg_meta *lead,
				     const struct opairshare_agg_meta *cand,
				     unsigned int n_have, uint32_t body_have,
				     unsigned int last_len, unsigned int max_n,
				     uint32_t max_mpdu)
{
	uint32_t body;

	if (!lead->eligible || !cand->eligible || n_have >= max_n ||
	    n_have >= OPAIRSHARE_AGG_HARD_MAX)
		return 0;
	if (memcmp(cand->ra, lead->ra, 6) || memcmp(cand->ta, lead->ta, 6) ||
	    cand->qos[0] != lead->qos[0] || cand->qos[1] != lead->qos[1])
		return 0;
	body = body_have + opairshare_agg_pad(last_len) + OPAIRSHARE_AGG_SUB_HDR + cand->msdu_len;
	if (OPAIRSHARE_AGG_QOS_HDR + body > max_mpdu)
		return 0;
	return body;
}

/* Write DA | SA | Length(big-endian) of one subframe.  14 bytes. */
static inline void opairshare_agg_put_subhdr(uint8_t *p, const uint8_t *da,
				       const uint8_t *sa, unsigned int msdu_len)
{
	memcpy(p, da, 6);
	memcpy(p + 6, sa, 6);
	p[12] = (uint8_t)(msdu_len >> 8);
	p[13] = (uint8_t)(msdu_len & 0xff);
}

/* Offset of subframe i's header inside the body, and the body length.
 * Returns 0 for an invalid request.
 */
static inline uint32_t opairshare_agg_layout(const uint16_t *msdu_len, unsigned int n,
				       uint32_t *off)
{
	uint32_t pos = 0;
	unsigned int i;

	if (!n || n > OPAIRSHARE_AGG_HARD_MAX)
		return 0;
	for (i = 0; i < n; i++) {
		if (!msdu_len[i] || msdu_len[i] > OPAIRSHARE_AGG_MAX_MSDU)
			return 0;
		off[i] = pos;
		pos += OPAIRSHARE_AGG_SUB_HDR + msdu_len[i];
		if (i + 1 < n)
			pos += opairshare_agg_pad(msdu_len[i]);
	}
	return pos;
}

/* A queued pure ACK `older` is redundant once `newer` of the same flow, same
 * link addresses and a strictly larger cumulative ACK number is queued behind
 * it.  Equal ACK numbers (duplicate ACKs, window updates) are never dropped.
 */
static inline int opairshare_ackf_redundant(const struct opairshare_agg_meta *older,
				      const struct opairshare_agg_meta *newer)
{
	return older->ack_pure && newer->ack_pure &&
	       !memcmp(older->flow, newer->flow, sizeof(older->flow)) &&
	       !memcmp(older->ra, newer->ra, 6) && !memcmp(older->ta, newer->ta, 6) &&
	       (int32_t)(newer->ack_seq - older->ack_seq) > 0;
}

#endif /* STAGE54_AGG_CORE_H */
