#!/system/bin/sh
ui_print "- OPAirDrop v$(sed -n 's/^version=//p' "$MODPATH/module.prop")：为一加 13 开启 AirDrop"
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
for f in "$MODPATH"/bin/*.sh "$MODPATH/service.sh" "$MODPATH/post-fs-data.sh" "$MODPATH/uninstall.sh";do set_perm "$f" 0 0 0755;done
set_perm "$MODPATH/system/system_ext/bin/mosey_server" 0 0 0755
ui_print '- 配置内置 Unlock CN GMS v3.8'
gms_result=$(sh "$MODPATH/bin/setup-gms.sh" "$MODPATH" 2>&1) || abort "GMS 配置生成失败：$gms_result"
printf '%s\n' "$gms_result" | while IFS= read -r line; do ui_print "- $line"; done
if [ -s "$MODPATH/gms-unlock/mounts.list" ]; then
    for standalone in /data/adb/modules/com.fei_ke.unlockcngms /data/adb/modules_update/com.fei_ke.unlockcngms; do
        if [ -d "$standalone" ]; then
            touch "$standalone/disable"
            ui_print '- 已停用独立 Unlock CN GMS 模块，重启后由内置版本接管'
        fi
    done
fi
mkdir -p /data/adb/opairshare
if [ ! -e /data/adb/opairshare/autostart-off ];then touch /data/adb/opairshare/autostart;fi
# Preserve explicit compatibility profiles. Otherwise select the tested fast profile.
if [ ! -e /data/adb/opairshare/turbo-off ] && [ ! -e /data/adb/opairshare/turbo-vht ] && [ ! -e /data/adb/opairshare/turbo-on ]; then
    touch /data/adb/opairshare/turbo-fast
fi
rm -f /data/adb/opairshare/disabled /data/adb/opairshare/startup-incomplete
ui_print '- Reboot once; the driver/native service starts automatically'
ui_print '- Fast profile is the default; compatibility fallback: su -c "sh /data/adb/modules/opairshare/bin/turbo.sh off"'
ui_print '- AirDrop receives in background on Wi-Fi channel40/149, independently of OShare visibility'
ui_print '- Original signed APKs stay in system; callback APK mounts after boot; no firmware or partition writes'
ui_print '- RX re-arms after real vdev delete and normal teardown; uncertain states quarantine'

set_perm "$MODPATH/payload/native/opairshare-socket-buffer" 0 0 0755
set_perm "$MODPATH/bin/turbo.sh" 0 0 0755

# Earlier releases used a different ID; only one driver owner may start at boot.
for legacy_dir in /data/adb/modules/"op""13-quickshare-wondertap" /data/adb/modules_update/"op""13-quickshare-wondertap"; do
    if [ -d "$legacy_dir" ]; then
        touch "$legacy_dir/disable"
        ui_print '- 已停用旧版 AirDrop 模块，重启后由 OPAirDrop 接管'
    fi
done

ui_print '- 检测 LSPosed 并安装、激活 OShare Xposed 模块'
oshare_result=$(sh "$MODPATH/bin/install-oshare.sh" "$MODPATH" install 2>&1)
oshare_status=$?
printf '%s\n' "$oshare_result" | while IFS= read -r line; do ui_print "- $line"; done
[ "$oshare_status" = 0 ] || ui_print '- OShare 配置尚未完成，重启后将自动重试；请查看 /data/adb/opairshare/oshare-install.log'

ui_print '- Open module WebUI for settings, status and logs'
