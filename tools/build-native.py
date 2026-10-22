#!/usr/bin/env python3
"""Build renamed ARM64 helpers locally; no install or device operations."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME'))
    args = parser.parse_args()
    if not args.sdk or not args.ndk:
        parser.error('Set --sdk and --ndk, or ANDROID_HOME and ANDROID_NDK_HOME')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}.get(platform.system())
    if not host:
        parser.error('This helper supports macOS or Linux')
    clang = args.ndk / 'toolchains/llvm/prebuilt' / host / 'bin/aarch64-linux-android31-clang'
    api = args.sdk / 'platforms/android-36/android.jar'
    d8 = args.sdk / 'build-tools/36.0.0/d8'
    for required in (clang, api, d8):
        if not required.is_file():
            parser.error(f'Missing toolchain input: {required}')
    out = ROOT / 'build/native'
    out.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'native'
    common = [str(clang), '-O2', '-Wall', '-Wextra', '-Werror']
    for name, libs in [('capability', ['-lbinder_ndk', '-ldl']), ('config', ['-llog', '-ldl']),
                       ('channel', ['-llog', '-ldl'])]:
        subprocess.run(common + ['-std=c11', '-shared', '-fPIC', str(source / (name + '.c')),
                       '-Wl,--version-script=' + str(source / (name + '.map')), '-Wl,--no-undefined',
                       '-o', str(out / ('libopairshare_' + name + '.so'))] + libs, check=True)
    subprocess.run(common + [str(source / 'socket-buffer.c'), '-o', str(out / 'opairshare-socket-buffer')], check=True)
    # Only this generated class/dex workspace is refreshed on rebuild.
    classes = out / 'classes'
    dex = out / 'dex'
    for folder in (classes, dex):
        if folder.exists():
            shutil.rmtree(folder)
        folder.mkdir()
    subprocess.run(['javac', '-source', '17', '-target', '17', '-cp', str(api), '-d', str(classes),
                    str(source / 'OPAirShareSessionWifi.java')], check=True)
    subprocess.run([str(d8), '--lib', str(api), '--min-api', '31', '--output', str(dex),
                    *map(str, classes.rglob('*.class'))], check=True)
    subprocess.run(['jar', 'cf', str(out / 'session-wifi.jar'), '-C', str(dex), 'classes.dex'], check=True)
    files = [out / name for name in ('libopairshare_capability.so', 'libopairshare_config.so',
                                    'libopairshare_channel.so',
                                    'opairshare-socket-buffer', 'session-wifi.jar')]
    record = {p.name: {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'bytes': p.stat().st_size}
              for p in files}
    (out / 'build.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
