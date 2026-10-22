#!/usr/bin/env python3
"""Check a packaged test module without installing or changing a device."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shlex
import subprocess
import tempfile
import zipfile


def run(command, env):
    result = subprocess.run(command, capture_output=True, text=True, env=env, timeout=10)
    if result.returncode:
        raise AssertionError(f'{command}: rc={result.returncode}\n{result.stdout}{result.stderr}')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
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

        # Exercise the actual installer preference selection with prior manual
        # off/strict/compatibility settings, inside a temporary state directory.
        state = root / 'state'
        state.mkdir()
        for name in ['coex-off', 'coex-same149-only', 'turbo-off', 'turbo-vht', 'turbo-on']:
            (state / name).touch()
        installer = (module / 'customize.sh').read_text()
        block = installer.split('# Explicit test-package choice:', 1)[1]
        block = block.split('rm -f /data/adb/opairshare/disabled', 1)[0]
        block = ('# Explicit test-package choice:' + block).replace('/data/adb/opairshare', str(state))
        run(['sh', '-eu', '-c', block], env)
        assert sorted(p.name for p in state.iterdir()) == ['turbo-fast']
        common = module / 'bin/common.sh'
        profile = run(['sh', '-c', '. "$1"; STATE=$2; turbo_module_args', 'test',
                       str(common), str(state)], env)
        cold_profile = dict(item.split('=', 1) for item in shlex.split(profile))
        expected = json.loads((module / 'build-info.json').read_text())['profile']
        for key, value in expected.items():
            assert cold_profile['passthru_awdl_' + key] == str(value)

        tools = root / 'tools'
        tools.mkdir()
        for name, code in {
            'flock': 'exit 0',
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
        print('PASS installer auto+fast defaults, cold-start parameters, kernel mode3, shell syntax, ZIP and all payload hashes')


if __name__ == '__main__':
    main()
