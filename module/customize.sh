#!/system/bin/sh
ui_print '- OPAirShare r27 Miuix: OShare + guarded coexistence; fast VHT2SS TX + concurrent peers + native RX + cold-start transfer callbacks'
[ "$(getprop ro.product.model)" = PJZ110 ] || abort 'Only PJZ110 is supported'
[ "$(getprop ro.build.version.sdk)" = 37 ] || abort 'SDK37 required'
[ "$(uname -r)" = '6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k' ] || abort 'Kernel changed; ABI revalidation required'
[ "${KSU:-false}" = true ] || [ -x /data/adb/ksud ] || abort 'KernelSU required'
[ -e /data/adb/metamodule ] || abort 'Existing Magic Mount metamodule required'
sh "$MODPATH/bin/verify.sh" "$MODPATH" >/dev/null || abort 'Payload integrity check failed'
# Replacement must not merge obsolete split APKs from the earlier module.
REPLACE="/system/system_ext/priv-app/MoseyApp"
for item in "$MODPATH"/*;do
    [ "$item" != "$MODPATH/webroot" ] || continue
    if [ -d "$item" ];then set_perm_recursive "$item" 0 0 0755 0644
    else set_perm "$item" 0 0 0644;fi
done
for f in "$MODPATH"/bin/*.sh "$MODPATH/service.sh" "$MODPATH/action.sh" "$MODPATH/uninstall.sh";do set_perm "$f" 0 0 0755;done
set_perm "$MODPATH/system/system_ext/bin/mosey_server" 0 0 0755
mkdir -p /data/adb/opairshare
if [ ! -e /data/adb/opairshare/autostart-off ];then touch /data/adb/opairshare/autostart;fi
# Preserve explicit compatibility profiles. Otherwise select the tested fast profile.
if [ ! -e /data/adb/opairshare/turbo-off ] && [ ! -e /data/adb/opairshare/turbo-vht ] && [ ! -e /data/adb/opairshare/turbo-on ]; then
    touch /data/adb/opairshare/turbo-fast
fi
rm -f /data/adb/opairshare/disabled /data/adb/opairshare/startup-incomplete
ui_print '- Reboot once; the driver/native service starts automatically'
ui_print '- Fast profile is the default; compatibility fallback: su -c "sh /data/adb/modules/opairshare/bin/turbo.sh off"'
ui_print '- Open OShare/QuickShare on Wi-Fi channel40/149; close UI for normal radio release'
ui_print '- KSU Action is explicit full-stack recovery only'
ui_print '- Original signed APKs stay in system; callback APK mounts after boot; no firmware or partition writes'
ui_print '- RX re-arms after real vdev delete and normal teardown; uncertain states quarantine'

set_perm "$MODPATH/payload/native/opairshare-socket-buffer" 0 0 0755
set_perm "$MODPATH/bin/turbo.sh" 0 0 0755

ui_print '- Install companion/OShareAirDrop-v0.10-fixed149.apk and enable OShare/Mosey in LSPosed'

ui_print '- Open module WebUI for settings, status and logs'
