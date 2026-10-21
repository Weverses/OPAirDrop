/* SPDX-License-Identifier: GPL-2.0-only */
/* Helpers called only under opairshare_bridge_lock for the sole PAS instance.
 * Opt-in host ownership was verified at original wonder0 injection: native
 * radiotap RATE-only, Sequence Control zero, no TX_FLAGS/NOSEQ directive.
 * Wonder can rebuild radiotap; its later absence alone is not that proof.
 */
static u16 opairshare_awdl_sequences[16];

static void opairshare_awdl_sequence_reset(void)
{
	memset(opairshare_awdl_sequences, 0, sizeof(opairshare_awdl_sequences));
}

static bool opairshare_awdl_sequence_scope(const u8 *rt, u16 fc, const u8 *hdr)
{
	u32 present = rt[4] | (rt[5] << 8) | (rt[6] << 16) | ((u32)rt[7] << 24);
	/* Current ABI uses a 9-byte RATE-only header; Wonder may replace it
	 * by a 9-byte empty header. Other layouts and any explicit directives
	 * are outside this experiment. Keep nonzero or fragmented input intact.
	 */
	return !rt[0] && rt[2] == 9 && !rt[3] &&
		(present == 0 || present == 4) && fc == 0x0088 &&
		!hdr[22] && !hdr[23];
}

static u16 opairshare_awdl_sequence_allocate(u8 tid)
{
	u16 seq = opairshare_awdl_sequences[tid & 15];
	opairshare_awdl_sequences[tid & 15] = (seq + 1) & 0x0fff;
	return seq << 4;
}

static void opairshare_awdl_sequence_apply(u8 *frame, u16 seq_control)
{
	frame[22] = seq_control & 0xff;
	frame[23] = seq_control >> 8;
}
