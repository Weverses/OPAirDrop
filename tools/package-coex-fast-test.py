#!/usr/bin/env python3
"""Package the captured on-device coexistence stack with fast on every band.

This experimental packager uses a device snapshot, not reference-r27: the
deployed WLAN module and discovery APK implement additional concurrency modes.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import stat
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '1.0.17-coex-fast-test1'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def payload_files(root):
    return sorted(p for p in root.rglob('*') if p.is_file() and p.name != '.DS_Store')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--bridge', type=Path, required=True,
                        help='Exact currently installed OShare bridge APK')
    parser.add_argument('--installer-base', type=Path, required=True)
    args = parser.parse_args()
    work = ROOT / 'build/coex-fast-test'
    captured = work / 'verified-snapshot'
    output = work / 'module'
    for directory in (captured, output):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)

    with zipfile.ZipFile(args.installer_base) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate installer ZIP entries')
        for line in archive.read('SHA256SUMS').decode().splitlines():
            expected, name = line.split(None, 1)
            if digest(archive.read(name)) != expected:
                raise ValueError(f'Installer hash mismatch: {name}')
        archive.extractall(output)

    with tarfile.open(args.snapshot) as archive:
        names = [m.name.rstrip('/') for m in archive.getmembers()]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate snapshot paths')
        archive.extractall(captured, filter='data')
    manifest = dict(reversed(line.split(None, 1)) for line in
                    (captured / 'SHA256SUMS').read_text().splitlines())
    snapshot_hashes = {}
    for path in payload_files(captured):
        name = path.relative_to(captured).as_posix()
        actual = digest(path.read_bytes())
        if name != 'SHA256SUMS' and manifest.get(name) != actual:
            raise ValueError(f'Device snapshot hash mismatch: {name}')
        snapshot_hashes[name] = actual
    omitted = []
    for name in manifest:
        if name in snapshot_hashes:
            continue
        # These are generated on this particular phone at install time. They
        # must be rebuilt by setup-gms.sh, never distributed to another install.
        if name in ('README.md', 'customize.sh') or name.startswith('gms-unlock/'):
            omitted.append(name)
        else:
            raise ValueError(f'Snapshot omitted required payload: {name}')

    for path in payload_files(captured):
        name = path.relative_to(captured)
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    # Keep the bridge already loaded by the user, rather than downgrading to
    # the older companion left in the installed KSU module directory.
    bridge_data = args.bridge.read_bytes()
    with zipfile.ZipFile(args.bridge) as archive:
        if 'AndroidManifest.xml' not in archive.namelist() or 'classes.dex' not in archive.namelist():
            raise ValueError('Invalid bridge APK')
    (output / 'companion/OShareAirDrop.apk').write_bytes(bridge_data)

    coex = output / 'bin/coex-session.sh'
    text = coex.read_text()
    pattern = (r'# Separate-channel operation.*?\n'
               r'cross_band_profile\(\) \{.*?\n\}\n')
    text, count = re.subn(pattern,
        '# Every band inherits the selected TX profile, exactly as same149.\n'
        '# This test installer selects fast before the next cold stack start.\n',
        text, count=1, flags=re.S)
    if count != 1 or text.count('[ "$class" = same149 ] || cross_band_profile') != 1:
        raise ValueError('Unexpected deployed coexistence profile implementation')
    text = text.replace('[ "$class" = same149 ] || cross_band_profile',
                        '# Keep the selected fast profile across channels/bands.')
    coex.write_text(text)
    if 'auto:cross5g|auto:band24|auto:band6) policy=auto;mode=3;;' not in text:
        raise ValueError('Missing deployed automatic coexistence policy')

    # Check all module payloads against the build identities expected by boot.
    for line in (output / 'bin/kernel-identity.sh').read_text().splitlines():
        if line.startswith('NOTE_'):
            key, note = line.split('=', 1)
            if bytes.fromhex(note) not in (output / 'payload/kernel' / (key[5:] + '.ko')).read_bytes():
                raise ValueError(f'Kernel identity mismatch: {key}')
    for filename in ('APP-SHA256SUMS', 'APP-CALLBACK-SHA256SUMS'):
        for line in (output / filename).read_text().splitlines():
            expected, name = line.split(None, 1)
            path = output / 'system/system_ext/priv-app/MoseyApp' / name
            if filename == 'APP-CALLBACK-SHA256SUMS' and name == 'base.apk':
                path = output / 'payload/app-callback/base.apk'
            if digest(path.read_bytes()) != expected:
                raise ValueError(f'APK manifest mismatch: {filename}: {name}')

    props = (output / 'module.prop').read_text()
    props = re.sub(r'^name=.*$', 'name=OPAirDrop 跨信道 Fast 测试版', props, flags=re.M)
    props = re.sub(r'^version=.*$', f'version={VERSION}', props, flags=re.M)
    props = re.sub(r'^versionCode=.*$', 'versionCode=10017', props, flags=re.M)
    props = re.sub(r'^description=.*$',
                   'description=PJZ110 测试：2.4/5/6GHz 自动共存 + 全频段 Fast；刷入后重启测试',
                   props, flags=re.M)
    props = re.sub(r'^updateJson=.*\n?', '', props, flags=re.M)
    (output / 'module.prop').write_text(props)
    installer = output / 'customize.sh'
    text = installer.read_text()
    anchor = '# Preserve explicit compatibility profiles. Otherwise select the tested fast profile.'
    if text.count(anchor) != 1:
        raise ValueError('Unexpected installer profile defaults')
    text = text.replace(anchor,
        '# Explicit test-package choice: auto coexistence and full fast.\n'
        'rm -f /data/adb/opairshare/coex-off /data/adb/opairshare/coex-same149-only \\\n'
        '    /data/adb/opairshare/turbo-off /data/adb/opairshare/turbo-vht /data/adb/opairshare/turbo-on\n'
        'touch /data/adb/opairshare/turbo-fast\n' + anchor)
    text = text.replace('AirDrop receives in background on Wi-Fi channel40/149, independently of OShare visibility',
                        'TEST: automatic 2.4/5/6GHz coexistence; fast retained across channels; reboot required')
    installer.write_text(text)
    readme = f'''# OPAirDrop {VERSION}

PJZ110 / SDK37 / kernel 6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k。
需要 KernelSU + 元模块 + LSPosed。刷入后重启，由用户进行实机测试。

基于手机当前部署的共存驱动、Mosey 补丁、恢复逻辑及已安装 OShare 桥接重新装配。
安装时选择共存开启、scope=auto、profile=fast；覆盖旧的对应设置。
STA 按当前实现接受 2.4/5/6GHz，AWDL 固定信道 149。
所有频段均保留与同信道相同的 fast：VHT MCS8、NSS2、A-MSDU mode2、max5、7935字节、collect_us=1000。
跨信道会话准备不再应用 HT 单流降速配置，重连和重建会话后继续保留所选 profile。
用户后续手动修改 profile/关闭共存仍按原设置逻辑执行。

此包用于完整 fast 的冷启动测试，不能视为已修复此前的发现丢失或连接超时。
不含设备运行状态、网络配置、日志或临时 fast-test 参数快照。
原厂分区、固件不写入；与正式版使用相同模块 ID，安装后替换现有模块。
保留原版安装包，以便刷回。测试包未配置正式 Release 自动更新源。
'''
    (output / 'README.md').write_text(readme)
    (output / 'CHANGELOG.md').write_text(
        f'# {VERSION}\n\n- 基于实机自动共存栈制作，跨信道不再降为 HT 单流。\n'
        '- 安装选择 auto + fast，所有频段沿用同信道完整 fast 配置。\n'
        '- 保留已安装 OShare 桥接、驱动身份、Mosey 自动策略和会话恢复。\n'
        '- 实验包，稳定性和吞吐待刷入后验证。\n')
    info = {
        'version': VERSION, 'versionCode': 10017, 'experimental': True,
        'kernel_rebuilt': False, 'callback_rebuilt': False,
        'base': 'captured deployed PJZ110 auto-coexistence stack',
        'snapshot_sha256': digest(args.snapshot.read_bytes()),
        'installer_base_sha256': digest(args.installer_base.read_bytes()),
        'bridge_sha256': digest(bridge_data),
        'bridge_version': '1.0.15 (retained active device bridge)',
        'deployed_files_before_changes': snapshot_hashes,
        'omitted_install_generated_files': sorted(omitted),
        'profile': {'phy': 3, 'mcs': 8, 'nss': 2, 'amsdu': 2,
                    'amsdu_max': 5, 'amsdu_bytes': 7935, 'collect_us': 1000},
        'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'deployed_source_differs_from_workspace': True,
    }
    (output / 'build-info.json').write_text(json.dumps(info, indent=2, ensure_ascii=False) + '\n')
    for path in payload_files(output):
        executable = path.suffix == '.sh' or path.name in ('mosey_server', 'opairshare-socket-buffer')
        path.chmod(0o755 if executable else 0o644)
        if path.suffix == '.sh':
            subprocess.run(['sh', '-n', str(path)], check=True)
    (output / 'SHA256SUMS').write_text(''.join(
        f'{digest(p.read_bytes())}  {p.relative_to(output).as_posix()}\n'
        for p in payload_files(output) if p.name not in ('SHA256SUMS', 'ARCHIVE-SHA256SUMS')))
    subprocess.run(['sh', str(output / 'bin/verify.sh'), str(output)], check=True)
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    archive_path = dist / f'OPAirDrop-v{VERSION}-PJZ110.zip'
    with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in payload_files(output):
            info = zipfile.ZipInfo.from_file(path, path.relative_to(output).as_posix())
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | (path.stat().st_mode & 0o777)) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
    checksum = digest(archive_path.read_bytes())
    archive_path.with_suffix('.zip.sha256').write_text(f'{checksum}  {archive_path.name}\n')
    print(f'{archive_path} ({archive_path.stat().st_size:,} bytes)\nSHA256 {checksum}')


if __name__ == '__main__':
    main()
