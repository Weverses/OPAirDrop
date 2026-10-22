#!/usr/bin/env python3
"""Build an opt-in PAS149/20MHz NA20 candidate on the existing Linux builder.

Run on that builder only. Replays the exact retained mode3 overlay, backs up
every input and restores only files still containing this invocation's bytes.
"""
from pathlib import Path, PurePosixPath
import argparse
import difflib
import fcntl
import hashlib
import json
import re
import shutil
import subprocess
import tarfile
import time

TREE = Path('/mnt/op13-build/op13')
ARCHIVE = Path('/mnt/op13-build/opairshare-overlay-bc743f.tar')
EXPECTED = '18029d413a013d8a1a760511d35ebf4db22470dc36c258f67fec57465524d1c6'
PREFIX = 'vendor/qcom/opensource/wlan/'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def replace(text, old, new, count=1):
    if text.count(old) != count:
        raise ValueError(f'Unexpected occurrence count for {old!r}: {text.count(old)}')
    return text.replace(old, new)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    if work.parent != Path('/home/evermore') or not work.name.startswith('opairshare-na20-'):
        parser.error('Use a new /home/evermore/opairshare-na20-* directory')
    work.mkdir(exist_ok=False)
    lock = (TREE / '.codex-coex-build.lock').open('a')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    processes = subprocess.check_output(['ps', '-eo', 'args'], text=True)
    if re.search(r'bazel[^\n]*\s(?:build|run)\s', processes):
        raise RuntimeError('Another Bazel build client is active; no source changed')
    if sha(ARCHIVE.read_bytes()) != EXPECTED:
        raise ValueError('Retained mode3 overlay identity changed')
    files = {}
    with tarfile.open(ARCHIVE) as archive:
        for member in archive.getmembers():
            if not member.isfile():
                continue
            rel = PurePosixPath(member.name)
            if rel.is_absolute() or '..' in rel.parts or not str(rel).startswith('vendor/'):
                raise ValueError(f'Unexpected overlay path: {rel}')
            if str(rel) in files:
                raise ValueError(f'Duplicate overlay path: {rel}')
            files[str(rel)] = archive.extractfile(member).read()
    if len(files) != 42:
        raise ValueError(f'Expected 42 retained overlay files, got {len(files)}')
    integration = {
        'qcacld-3.0/core/hdd/src/wlan_hdd_main.c': [
            ('oneplus13_pas_aux_pre_stop', 'opairshare_pas_aux_pre_stop', 2),
            ('oneplus13_pas_mgmt_stop', 'opairshare_pas_mgmt_stop', 2)],
        'qcacld-3.0/components/target_if/p2p/src/target_if_p2p.c': [
            ('oneplus13_awdl_rx_filter_reply', 'opairshare_awdl_rx_filter_reply', 2)],
        'qca-wifi-host-cmn/dp/wifi3.0/dp_main.c': [
            ('oneplus13_passthru_monitor_active', 'opairshare_passthru_monitor_active', 1)],
        'qcacld-3.0/core/wma/src/wma_data.c': [
            ('#include "wma_op13_pas_mgmt.h"', '#include "wma_opairshare_pas_mgmt.h"', 1)],
    }
    for suffix, replacements in integration.items():
        rel = PREFIX + suffix
        text = files.get(rel, (TREE / rel).read_bytes()).decode()
        for old, new, count in replacements:
            text = replace(text, old, new, count)
        files[rel] = text.encode()
    baseline = dict(files)
    hdd = PREFIX + 'qcacld-3.0/core/hdd/src/wlan_hdd_wondertap.c'
    text = files[hdd].decode()
    text = replace(text, 'static bool passthru_ht;', '''static bool passthru_fixed149_na20;
module_param(passthru_fixed149_na20, bool, 0644);
MODULE_PARM_DESC(passthru_fixed149_na20,
    "Default off: PAS149 with coherent 20MHz centers uses 5GHz HT20 PHY");
static bool passthru_ht;''')
    text = replace(text,
        'return (READ_ONCE(passthru_ht) ? 1 : 0) | (READ_ONCE(passthru_dry_run) ? 2 : 0);',
        '''return (READ_ONCE(passthru_ht) ? 1 : 0) |
        (READ_ONCE(passthru_dry_run) ? 2 : 0) |
        (READ_ONCE(passthru_fixed149_na20) ? 4 : 0);''')
    files[hdd] = text.encode()
    target = PREFIX + 'qca-wifi-host-cmn/target_if/mlme/vdev_mgr/src/target_if_vdev_mgr_tx_ops.c'
    text = files[target].decode()
    text = replace(text,
        'param->channel.phy_mode = (enum wlan_phymode)WMI_HOST_MODE_11NG_HT20;',
        '''param->channel.phy_mode = (enum wlan_phymode)WMI_HOST_MODE_11NG_HT20;
            if ((diag & 4) && param->channel.mhz == 5745 &&
                param->channel.cfreq1 == 5745 && !param->channel.cfreq2) {
                param->channel.phy_mode = (enum wlan_phymode)WMI_HOST_MODE_11NA_HT20;
                mlme_info("OPAIRSHARE_NA20: vdev=%u primary=%u center=%u",
                    param->vdev_id, param->channel.mhz, param->channel.cfreq1);
            }''')
    files[target] = text.encode()
    patches = []
    for rel in (hdd, target):
        patches.extend(difflib.unified_diff(baseline[rel].decode().splitlines(True),
                                           files[rel].decode().splitlines(True),
                                           fromfile='deployed/' + rel, tofile='candidate/' + rel))
    (work / 'candidate.patch').write_text(''.join(patches))
    originals = {rel: ((TREE / rel).read_bytes() if (TREE / rel).exists() else None)
                 for rel in files}
    record = dict(baseline_module_sha256='152b93ad51c093be8ea54154121c4070573277f1711bb7d85deeb132b750c7a4',
                  overlay_sha256=EXPECTED, candidate_default='off',
                  inputs={rel: sha(data) for rel, data in files.items()})
    backup = work / 'before'
    backup.mkdir()
    for i, (rel, data) in enumerate(originals.items()):
        if data is not None:
            (backup / str(i)).write_bytes(data)
    (backup / 'manifest.json').write_text(json.dumps(
        {rel: dict(index=i, sha256=sha(data) if data is not None else None)
         for i, (rel, data) in enumerate(originals.items())}, indent=2))
    try:
        for rel, data in files.items():
            path = TREE / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        start = time.monotonic()
        with (work / 'build.log').open('wb') as log:
            record['returncode'] = subprocess.run(
                ['bash', '/home/evermore/build-wlan-feature-check.sh'],
                stdout=log, stderr=subprocess.STDOUT).returncode
        record['seconds'] = time.monotonic() - start
        if record['returncode'] == 0:
            output = work / 'qca_cld3_peach_v2.ko'
            shutil.copyfile(TREE / 'kernel_platform/bazel-bin/vendor/qcom/opensource/wlan/qcacld-3.0/sun_perf_qca_cld_peach-v2/qca_cld3_peach_v2.ko', output)
            subprocess.run([str(TREE / 'kernel_platform/prebuilts/clang/host/linux-x86/clang-r510928/bin/llvm-strip'),
                            '--strip-debug', str(output)], check=True)
            record['output_sha256'] = sha(output.read_bytes())
            record['output_bytes'] = output.stat().st_size
    finally:
        conflicts = []
        for rel, data in originals.items():
            path = TREE / rel
            if not path.exists() or path.read_bytes() != files[rel]:
                conflicts.append(rel)
                continue
            if data is None:
                path.unlink()
            else:
                path.write_bytes(data)
        record['restoration_conflicts'] = conflicts
        record['build_tree_restored'] = not conflicts
        (work / 'build.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({k: v for k, v in record.items() if k != 'inputs'}, indent=2))
    return record.get('returncode', 1)


if __name__ == '__main__':
    raise SystemExit(main())
