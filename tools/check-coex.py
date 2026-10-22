#!/usr/bin/env python3
"""Check a packaged coexistence module without installing or changing a device."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shlex
import struct
import subprocess
import tempfile
import zipfile
from release import inspect_archive


def run(command, env):
    result = subprocess.run(command, capture_output=True, text=True, env=env, timeout=10)
    if result.returncode:
        raise AssertionError(f'{command}: rc={result.returncode}\n{result.stdout}{result.stderr}')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    props, _ = inspect_archive(args.archive)
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        module = root / 'module'
        with zipfile.ZipFile(args.archive) as archive:
            assert archive.testzip() is None
            names = archive.namelist()
            assert len(names) == len(set(names))
            assert all(not n.startswith('/') and '..' not in Path(n).parts for n in names)
            assert not any(n.startswith('gms-unlock/') or 'fast-test-' in n for n in names)
            manifest = dict(reversed(line.split(None, 1)) for line in
                            archive.read('SHA256SUMS').decode().splitlines())
            assert set(manifest) == set(names) - {'SHA256SUMS'}
            for name, expected in manifest.items():
                assert hashlib.sha256(archive.read(name)).hexdigest() == expected, name
            archive.extractall(module)
        for path in module.rglob('*.sh'):
            subprocess.run(['sh', '-n', str(path)], check=True)
        env = dict(os.environ)
        run(['sh', str(module / 'bin/verify.sh'), str(module)], env)
        assert 'passthru_sta_coexist=3' in (module / 'bin/control.sh').read_text()
        build_info = json.loads((module / 'build-info.json').read_text())
        assert build_info['channel'] == 'stable'
        assert props['version'] == build_info['version']
        for name in ('module.prop', 'README.md', 'CHANGELOG.md'):
            assert not re.search(r'测试版|测试包|实验包|coex-\w*-test\d|## 未发布', (module / name).read_text()), name
        print('PASS stable release identity, update metadata contract and release wording')
        if build_info.get('share_mode'):
            settings = (module / 'bin/settings.sh').read_text()
            assert 'share-mode)' in settings and 'share_mode_active' in settings
            assert 'init_share_mode || return 3' in (module / 'bin/control.sh').read_text()
            assert '[ "$(active_share_mode)" != quickshare ]' in (module / 'bin/receive-session.sh').read_text()
            assert '    if ! apply_share_mode;then' in (module / 'bin/ui-lifecycle.sh').read_text()
            with zipfile.ZipFile(module / 'companion/OShareAirDrop.apk') as bridge:
                assert any(b'Lcom/tlsu/oshareairdrop/ShareModeHooks;' in bridge.read(name)
                           for name in bridge.namelist() if name.startswith('classes') and name.endswith('.dex'))
            print('PASS share-mode API, lifecycle selection and matching bridge')
        if build_info.get('receive_ui'):
            props = dict(line.split('=', 1) for line in (module / 'module.prop').read_text().splitlines() if '=' in line)
            assert props['version'] == build_info['version'] == build_info['bridge_version']
            assert int(props['versionCode']) == build_info['versionCode']
            assert hashlib.sha256((module / 'companion/OShareAirDrop.apk').read_bytes()).hexdigest() == build_info['bridge_sha256']
            settings = (module / 'bin/settings.sh').read_text()
            assert '. "$MODDIR/bin/receive-directory.sh"' in settings
            assert 'receive-directory-set|receive-directory-reset)' in settings
            assert 'receive_directory "$(decode_name_hex "$(read_receive_directory_hex)")"' in settings
            assert 'opairshare_receive_directory_hex' in (module / 'bin/receive-directory.sh').read_text()
            assert build_info['oshare_source_sha256']
            print('PASS module/bridge version, APK hash, save-directory API and source provenance')
        if build_info.get('stability_patch'):
            patch = build_info['stability_patch']
            library = (module / 'system/system_ext/lib64/libmosey_daemon_ffi.so').read_bytes()
            assert hashlib.sha256(library).hexdigest() == patch['output_sha256']
            instruction = struct.unpack_from('<I', library, patch['file_offset'])[0]
            assert instruction == int(patch['new_instruction'], 16)
            assert instruction & 31 == 1 and (instruction >> 5) & 65535 == 384
            # Revert the one changed instruction and recover the exact input
            # SHA256: this proves no other code/data was changed in the library.
            restored = bytearray(library)
            struct.pack_into('<I', restored, patch['file_offset'], int(patch['old_instruction'], 16))
            assert hashlib.sha256(restored).hexdigest() == patch['input_sha256']
            ui = (module / 'bin/ui-lifecycle.sh').read_text()
            assert '. "$MODDIR/bin/receive-startup.sh"' in ui
            assert ui.index('receive_warmup_start\n') < ui.index('startup_trace PROVIDER_START_BEGIN')
            assert 'receive_warmup_wait\n' in ui and 'receive_warmup_cleanup\n' in ui
            assert 'transfer_check=$t' in (module / 'bin/speed-history.sh').read_text()
            print('PASS bounded expiry instruction, exact single-instruction binary diff, RX warmup ownership and recorder recheck')

        overlays = build_info.get('workspace_overlays', {})
        for name, expected in overlays.items():
            assert hashlib.sha256((module / name).read_bytes()).hexdigest() == expected, name
        if 'bin/webui-status.sh' in overlays:
            settings = (module / 'bin/settings.sh').read_text()
            assert '. "$MODDIR/bin/webui-status.sh"' in settings
            assert '    json_object \\\n' in settings and '    coexist)\n' not in settings
            assert 'sta_class "$sta_class"' in settings and 'sta_band "$sta_band"' in settings
            print('PASS optimized status, always-on coexistence, cross-band status fields and overlay provenance')

        # Upgrades retain manual choices; a clean install selects fast.
        state = root / 'state'
        state.mkdir()
        for name in ['coex-off', 'coex-same149-only', 'turbo-off', 'turbo-vht', 'turbo-on']:
            (state / name).touch()
        installer = (module / 'customize.sh').read_text()
        anchor = '# Preserve explicit compatibility profiles. Otherwise select the fast profile.'
        migration='rm -f /data/adb/opairshare/coex-off /data/adb/opairshare/coex-same149-only\n'
        assert migration.strip() in installer
        block = migration + installer.split(anchor, 1)[1].split('rm -f /data/adb/opairshare/disabled', 1)[0]
        block = block.replace('/data/adb/opairshare', str(state))
        run(['sh', '-eu', '-c', block], env)
        assert sorted(p.name for p in state.iterdir()) == ['turbo-off', 'turbo-on', 'turbo-vht']
        for path in state.iterdir():
            path.unlink()
        run(['sh', '-eu', '-c', block], env)
        assert sorted(p.name for p in state.iterdir()) == ['turbo-fast']
        common = module / 'bin/common.sh'
        profile = run(['sh', '-c', '. "$1"; STATE=$2; turbo_module_args', 'test',
                       str(common), str(state)], env)
        cold_profile = dict(item.split('=', 1) for item in shlex.split(profile))
        expected = build_info['profile']
        for key, value in expected.items():
            assert cold_profile['passthru_awdl_' + key] == str(value)

        tools = root / 'tools'
        tools.mkdir()
        for name, code in {
            'flock': 'exit 0',
            'date': 'echo 1792648059',
            'getprop': f'cat "{root}/prop-$1" 2>/dev/null || true',
            'setprop': f'printf "%s" "$2" > "{root}/prop-$1"',
        }.items():
            path = tools / name
            path.write_text('#!/bin/sh\n' + code + '\n')
            path.chmod(0o755)
        env['PATH'] = str(tools) + os.pathsep + env['PATH']
        original_common = common.read_text()
        original_coex = (module / 'bin/coex-session.sh').read_text()
        params = root / 'params'
        params.mkdir()
        common.write_text(original_common + f'\nSTATE="{state}"\nboot_id() {{ echo fixture-boot; }}\n'
                          f'framework() {{ cat "{root}/wifi"; }}\n')
        coex = module / 'bin/coex-session.sh'
        coex.write_text(original_coex.replace('/sys/module/qca_cld3_peach_v2/parameters', str(params)))
        for frequency, klass in [(5745, 'same149'), (5805, 'cross5g'),
                                 (2447, 'band24'), (6115, 'band6')]:
            (root / 'wifi').write_text('Wifi is enabled\nWifi is connected to fixture\n'
                                      f'Supplicant state: COMPLETED, Frequency: {frequency}MHz, Net ID: 7,\n')
            for name, value in cold_profile.items():
                (params / name).write_text(value + '\n')
            for name, value in {
                'passthru_sta_coexist': '3',
                'passthru_awdl_allow_concurrent': 'N',
                'passthru_mgmt_home_channel': 'N',
                'opairshare_bridge_pending': '0',
                'opairshare_channel_state': 'busy=0 fault=0 error=0 accepting=0 vdev=255 freq=0',
                'opairshare_awdl_rx_mac_filter': 'used=0 armed=0 pending=0 active=0 fault=0',
            }.items():
                (params / name).write_text(value + '\n')
            output = run(['sh', str(coex), 'prepare'], env)
            assert f'class={klass}' in output and 'mode=3' in output, output
            assert (state / 'coex-session/class').read_text().strip() == klass
            for name, value in cold_profile.items():
                assert (params / name).read_text().strip() == value, (frequency, name)
            run(['sh', str(coex), 'restore'], env)
            assert not (state / 'coex-session').exists()
            for name, value in cold_profile.items():
                assert (params / name).read_text().strip() == value
            # Re-prepare the next band with the same selected fast profile.
            print(f'PASS {frequency} MHz ({klass}): full fast retained through prepare/restore')
        print('PASS always-on coexistence migration, preserved TX profiles, fresh fast defaults, cold-start parameters, kernel mode3, shell syntax, ZIP and all payload hashes')


if __name__ == '__main__':
    main()
