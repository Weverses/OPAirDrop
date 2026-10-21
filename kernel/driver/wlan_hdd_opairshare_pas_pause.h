/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef OPAIRSHARE_PAS_PAUSE_H
#define OPAIRSHARE_PAS_PAUSE_H
/* Private mask only: never a netif_reason_type or stats-array index. */
#define OPAIRSHARE_PAS_PAUSE_BIT BIT(31)
#ifdef DRIVER_PASSTHRU_MODE
struct net_device;
int opairshare_pas_flow_queue_control(struct net_device *dev, unsigned int action, unsigned int trace_id);
#endif
#endif
