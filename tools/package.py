#!/usr/bin/env python3
"""Assemble OPAirDrop using current userspace and the verified reference kernel ABI."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import stat
import subprocess
import zipfile
from release import emit_metadata

ROOT = Path(__file__).resolve().parents[1]
LEGACY = 'op' + '13'
PROVIDER = 'oneplus' + '13_wonder_provider'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def files(path):
    return sorted(p for p in path.rglob('*') if p.is_file() and p.name != '.DS_Store')


def copy_tree(source, target):
    shutil.copytree(source, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns('.DS_Store'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, default=ROOT / 'local-inputs/reference-r27')
    args = parser.parse_args()
    reference = args.reference.resolve()
    props = dict(line.split('=', 1) for line in (ROOT / 'module/module.prop').read_text().splitlines() if '=' in line)
    apk_dir = ROOT / 'oshare/app/build/outputs/apk/release'
    apk_metadata = json.loads((apk_dir / 'output-metadata.json').read_text())
    elements = apk_metadata.get('elements', [])
    if (apk_metadata.get('variantName') != 'release'
            or apk_metadata.get('applicationId') != 'com.tlsu.oshareairdrop'
            or len(elements) != 1
            or elements[0].get('versionName') != props['version']
            or elements[0].get('versionCode') != int(props['versionCode'])
            or elements[0].get('outputFile') != 'app-release.apk'):
        raise SystemExit('Build the signed OShare release APK with the current module version before packaging')
    oshare_apk = apk_dir / 'app-release.apk'
    output = ROOT / 'build/package/module'
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    # Verify every reused binary against the archived release before assembly.
    manifest = {}
    for line in (reference / 'SHA256SUMS').read_text().splitlines():
        expected, name = line.split(None, 1)
        manifest[name] = expected
    reused = files(reference / 'payload/kernel') + files(reference / 'system') + [reference / 'payload/app-callback/base.apk']
    for path in reused:
        name = path.relative_to(reference).as_posix()
        if manifest.get(name) != digest(path):
            raise SystemExit(f'Reference hash mismatch: {name}')
    copy_tree(ROOT / 'module', output)
    copy_tree(reference / 'system', output / 'system')
    copy_tree(reference / 'payload/kernel', output / 'payload/kernel')
    copy_tree(reference / 'payload/app-callback', output / 'payload/app-callback')
    copy_tree(ROOT / 'webui/dist', output / 'webroot')
    for name in ('APP-SHA256SUMS', 'APP-CALLBACK-SHA256SUMS'):
        source = reference / name
        if digest(source) != manifest.get(name):
            raise SystemExit(f'Reference hash mismatch: {name}')
        shutil.copy2(source, output / name)
    native = output / 'payload/native'
    native.mkdir(parents=True, exist_ok=True)
    for name in ('libopairshare_capability.so', 'libopairshare_config.so', 'opairshare-socket-buffer', 'session-wifi.jar'):
        shutil.copy2(ROOT / 'build/native' / name, native / name)
    shutil.copy2(ROOT / 'build/lsposed/lsposed-setup.jar', native / 'lsposed-setup.jar')
    companion = output / 'companion/OShareAirDrop.apk'
    companion.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(oshare_apk, companion)

    # Retain the actual module names/sysfs names exported by the verified kernels.
    # New module paths, state paths, native helpers and environment names stay current.
    for path in (output / 'bin').glob('*.sh'):
        text = path.read_text().replace('opairshare_wonder_provider', PROVIDER)
        text = text.replace('opairshare_hw_probe', LEGACY + '_hw_probe')
        text = re.sub(r'\bopairshare_(bridge|channel|awdl|agg|tx)(?=_)', LEGACY + r'_\1', text)
        for prop in ('discovery', 'standby'):
            text = text.replace('debug.opairshare.' + prop, 'debug.' + LEGACY + '.' + prop)
        path.write_text(text)
    identity = json.loads((reference / 'kernel-identity.json').read_text())
    kernel_identity = output / 'bin/kernel-identity.sh'
    text = kernel_identity.read_text()
    for name in ('mac80211', 'qca_cld3_peach_v2', 'wonder', PROVIDER):
        text = text.replace(f'NOTE_{name}=BUILD_REQUIRED', f'NOTE_{name}=' + identity[name + '.ko']['build_id_note'])
    if 'BUILD_REQUIRED' in text:
        raise SystemExit('Unresolved kernel build identities')
    kernel_identity.write_text(text)

    # Check both original and patched APK/split manifests against the assembled files.
    app = output / 'system/system_ext/priv-app/MoseyApp'
    for name in ('APP-SHA256SUMS', 'APP-CALLBACK-SHA256SUMS'):
        for line in (output / name).read_text().splitlines():
            expected, apk = line.split()
            path = output / 'payload/app-callback/base.apk' if name.startswith('APP-CALLBACK') and apk == 'base.apk' else app / apk
            if digest(path) != expected:
                raise SystemExit(f'APK hash mismatch: {name}: {apk}')
    for name in ('LICENSE', 'LICENSING.md', 'THIRD_PARTY_NOTICES.md', 'CHANGELOG.md'):
        shutil.copy2(ROOT / name, output / name)
    copy_tree(ROOT / 'LICENSES', output / 'LICENSES')
    copy_tree(ROOT / 'third-party/unlock-cn-gms', output / 'third-party/unlock-cn-gms')
    (output / 'README.md').write_text(
        f'# OPAirDrop {props["version"]}\n\n{props["description"]}\n\n'
        '适配：PJZ110 / SDK37 / 6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k。\n\n'
        '需要 KernelSU 与 Magic Mount metamodule。安装时检测 LSPosed，自动安装并启用 OShare，'
        '配置 OShare 与 Mosey 的主用户作用域。安装后重启一次。恢复环境安装或 LSPosed 尚未初始化时，'
        '开机后自动重试；若开机后才完成激活，需要再次重启。无 LSPosed 时会跳过桥接安装。\n\n'
        '配置前的 LSPosed 数据库备份与安装日志保存在 /data/adb/opairshare/。\n\n'
        '支持 KernelSU 管理器的模块在线更新，更新源为 Weverses/OPAirDrop 的 GitHub 正式 Release。'
        '首次需要手动安装包含 updateJson 的本版本，之后可在管理器中检查与安装新版本。\n\n'
        '内置 Unlock CN GMS v3.8（fei-ke）：安装时复制本机匹配的权限配置，移除两项国行 GMS 限制，'
        '开机早期只读挂载配置副本。仍需在系统设置开启 Google 服务。安装器会停用旧的独立解锁模块。'
        '解锁日志：/data/adb/opairshare/gms-unlock.log。停用或卸载 OPAirDrop 并重启后恢复原配置。\n\n'
        '如果检测到早期不同 ID 的同项目模块，安装器会将其停用，重启后由本模块接管，避免重复加载驱动。\n\n'
        '本包使用仓库最新用户态源码和已校验的 r27 内核、Mosey 二进制，'
        '打包时映射其原有 ABI 名称；内核与 Mosey 补丁未重新编译。\n'
    )
    record = {'name': props['name'], 'version': props['version'], 'versionCode': int(props['versionCode']),
              'update_json': props['updateJson'],
              'oshare': {'build_type': 'release', 'version': elements[0]['versionName'],
                         'version_code': elements[0]['versionCode'], 'sha256': digest(oshare_apk)},
              'kernel_rebuilt': False, 'callback_rebuilt': False, 'kernel_abi': 'reference-r27',
              'unlock_cn_gms': json.loads((ROOT / 'third-party/unlock-cn-gms/provenance.json').read_text()),
              'reference_binaries': {p.relative_to(reference).as_posix(): digest(p) for p in reused},
              'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'source_dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())}
    (output / 'build-info.json').write_text(json.dumps(record, indent=2) + '\n')
    for path in files(output):
        executable = path.suffix == '.sh' or path.name in ('mosey_server', 'opairshare-socket-buffer')
        path.chmod(0o755 if executable else 0o644)
    sums = ''.join(f'{digest(p)}  {p.relative_to(output).as_posix()}\n' for p in files(output) if p.name not in ('SHA256SUMS', 'ARCHIVE-SHA256SUMS'))
    (output / 'SHA256SUMS').write_text(sums)
    subprocess.run(['sh', str(output / 'bin/verify.sh'), str(output)], check=True)
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    archive = dist / f'OPAirDrop-v{props["version"]}-PJZ110.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in files(output):
            info = zipfile.ZipInfo.from_file(path, path.relative_to(output).as_posix())
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | path.stat().st_mode & 0o777) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, path.read_bytes())
    emit_metadata(archive, dist)
    print(f'{archive} ({archive.stat().st_size:,} bytes)')
    print(digest(archive))


if __name__ == '__main__':
    main()
