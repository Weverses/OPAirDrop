#!/system/bin/sh
# Read-only, bounded sampling for live coexistence experiments.
# Use with root; packet capture is started separately by the host.
set -u
P=/sys/module/qca_cld3_peach_v2/parameters
count=${1:-300}
case "$count" in ''|*[!0-9]*) exit 2;; esac
[ "$count" -gt 0 ] && [ "$count" -le 1800 ] || exit 2
i=0
while [ "$i" -lt "$count" ]; do
    printf '\nSAMPLE wall=%s uptime=' "$(date +%s)"
    cat /proc/uptime
    for key in opairshare_channel_state opairshare_awdl_rx_mac_filter \
        opairshare_native_rx_stats opairshare_rx_order_stats \
        opairshare_awdl_rx_duplicates opairshare_ba_stats \
        opairshare_bridge_pending opairshare_bridge_retried \
        opairshare_bridge_retry_gaveup opairshare_tx_metrics; do
        printf '%s ' "$key"
        cat "$P/$key" 2>/dev/null || true
    done
    for iface in mosey0 wlan0; do
        printf 'IFACE %s ' "$iface"
        for key in rx_bytes tx_bytes rx_dropped tx_dropped; do
            printf '%s=' "$key"
            cat "/sys/class/net/$iface/statistics/$key" 2>/dev/null || true
        done
    done
    ss -tinm6 dst fe80::/10 2>&1
    if [ $((i % 5)) -eq 0 ]; then iw dev wlan0 info; fi
    i=$((i+1))
    sleep 1
done
