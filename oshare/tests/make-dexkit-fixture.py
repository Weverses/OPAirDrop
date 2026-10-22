#!/usr/bin/env python3
"""Build an offline-only renamed DEX fixture from an APK (requires apktool).

No vendor APK is checked in, signed, installed or distributed. The fixture is
for static resolution tests, not execution. Output must be a new directory.
"""
from pathlib import Path
import argparse
import json
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('output directory must not exist')
    args.output.mkdir(parents=True)
    decoded = args.output / 'decoded'
    subprocess.run(['apktool', 'd', '-r', str(args.apk), '-o', str(decoded)], check=True)
    targets = ('l9/b', 'v9/d', 'v9/a', 'com/oplus/oshare/ui/DeviceAdapter',
               'com/oplus/oshare/ui/ShareActivity', 'com/oplus/oshare/OShareClient$6',
               'com/oplus/oshare/ui/direct/DirectShareServer')
    methods, fields = {}, {}
    smali = list(decoded.glob('smali*/**/*.smali'))
    for path in smali:
        text = path.read_text()
        owner = re.search(r'^\.class .*?(L[^; ]+;)\s*$', text, re.M).group(1)
        if not any(owner == 'L' + target + ';' or owner.startswith('L' + target + '$') for target in targets):
            continue
        for name, signature in re.findall(r'^\.method .*? ([\w$]+)(\([^\n]+)', text, re.M):
            if re.fullmatch(r'[A-Za-z]\d*', name):
                methods[(owner, name + signature)] = 'renamedMethod' + str(len(methods)) + signature
        for name, signature in re.findall(r'^\.field .*? ([\w$]+):([^\s=]+)', text, re.M):
            fields[(owner, name + ':' + signature)] = 'renamedField' + str(len(fields)) + ':' + signature
    for path in smali:
        text = path.read_text()
        owner = re.search(r'^\.class .*?(L[^; ]+;)\s*$', text, re.M).group(1)
        text = re.sub(r'(L[^;\s()]+;)->([\w$]+\([^\s,]+)',
                      lambda m: m[1] + '->' + methods.get((m[1], m[2]), m[2]), text)
        text = re.sub(r'(L[^;\s()]+;)->([\w$]+:[^\s,]+)',
                      lambda m: m[1] + '->' + fields.get((m[1], m[2]), m[2]), text)
        text = re.sub(r'^(\.method .*? )([\w$]+\([^\n]+)',
                      lambda m: m[1] + methods.get((owner, m[2]), m[2]), text, flags=re.M)
        text = re.sub(r'^(\.field .*? )([\w$]+:[^\s=]+)',
                      lambda m: m[1] + fields.get((owner, m[2]), m[2]), text, flags=re.M)
        # DEX types and references change, string literals stay intact.
        for index, target in enumerate(targets):
            text = text.replace('L' + target + ';', f'Lfixture/Role{index};')
            text = text.replace('L' + target + '$', f'Lfixture/Role{index}$')
        path.write_text(text)
    subprocess.run(['apktool', 'b', str(decoded), '-o', str(args.output / 'renamed.apk')], check=True)
    decoy = decoded / 'smali/fixture/AdapterDecoy.smali'
    decoy.parent.mkdir(parents=True, exist_ok=True)
    decoy.write_text(""".class public Lfixture/AdapterDecoy;
.super Ljava/lang/Object;
.method public static marker()V
    .registers 1
    const-string v0, "empty list or wrong position"
    const-string v0, "device is null"
    return-void
.end method
""")
    subprocess.run(['apktool', 'b', str(decoded), '-o', str(args.output / 'ambiguous.apk')], check=True)
    (args.output / 'renames.json').write_text(json.dumps({
        'classes': len(targets), 'methods': len(methods), 'fields': len(fields),
        'purpose': 'static DexKit lookup under renamed symbols; not a runnable version compatibility claim',
    }, indent=2) + '\n')


if __name__ == '__main__':
    main()
