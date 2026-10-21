#!/usr/bin/env python3
"""Build the Mosey60715 per-transfer callback experiment; never installs it."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

BASE_SHA = '7ab2deaea6c9c2bdd5ba0f935d6143c115f5ceb11cb8b63066496ad27a28da49'
HERE = Path(__file__).resolve().parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


def once(text, before, after):
    if text.count(before) != 1:
        raise ValueError('Patch anchor differs: ' + before[:100])
    return text.replace(before, after, 1)


def method(text, signature, transform):
    start = text.index('.method ' + signature + '\n')
    end = text.index('.end method', start) + len('.end method')
    return text[:start] + transform(text[start:end]) + text[end:]


def patch_tree(decoded, output):
    smali = decoded / 'smali_classes2'
    hashes = json.loads((HERE / 'smali-inputs.json').read_text())
    original = {}
    for name, digest in hashes.items():
        data = (smali / (name + '.smali')).read_bytes()
        if sha(data) != digest:
            raise ValueError('Unexpected original smali: ' + name)
        original[name] = data.decode()
    patched = dict(original)

    # All four existing callers (capture, collector, terminal reconstruction)
    # now take the original per-transfer branch, independent of Phenotype.
    patched['dly'] = method(original['dly'], 'public static g()Z', lambda _: '''.method public static g()Z
    .locals 1
    # OPAIRSHARE stage60: use the original per-transfer callback implementation.
    const/4 v0, 0x1
    return v0
.end method''')
    def marker(text):
        text = once(text, '    .locals 1', '    .locals 2')
        return once(text, '    return-void', '''    const-string v0, "OPAIRSHARECallback"
    const-string v1, "stage60-r2 per-transfer callback loaded"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    return-void''')
    patched['dly'] = method(patched['dly'], 'static constructor <clinit>()V', marker)

    patched['ccu'] += '''

# The callback belongs to this transfer, including after advertising stops.
.method public final opairshareTransferCallback()Ldsc;
    .locals 1
    iget-object v0, p0, Lccu;->h:Ldsc;
    return-object v0
.end method
'''
    patched['cee'] = once(original['cee'], '.field d:Lbuw;',
                         '.field d:Lbuw;\n\n.field opairshareCallback:Ldsc;')

    def terminal(text):
        # Preserve callback before v1 is repurposed for TransferMetadata.
        text = once(text, '    iget-object v1, v1, Lccu;->f:Lcom/google/android/gms/nearby/sharing/TransferMetadata;',
                    '''    invoke-virtual {v1}, Lccu;->opairshareTransferCallback()Ldsc;
    move-result-object v7
    move-object/from16 v17, v7
    iget-object v1, v1, Lccu;->f:Lcom/google/android/gms/nearby/sharing/TransferMetadata;''')
        start = text.index('    invoke-static {}, Ldly;->g()Z')
        end = text.index('    :goto_3', start) + len('    :goto_3')
        # The original reread of cen.n here loses the callback at completion.
        text = text[:start] + '    # v17 retains the original transfer callback.' + text[end:]
        return text

    def report(text):
        # Save across the retry coroutine suspension, before consuming p1.
        text = once(text, '    iget-object p1, p1, Lccu;->c:Lcct;', '''    invoke-virtual {p1}, Lccu;->opairshareTransferCallback()Ldsc;
    move-result-object v2
    iput-object v2, v0, Lcee;->opairshareCallback:Ldsc;
    iget-object p1, p1, Lccu;->c:Lcct;''')
        # Load while v0 is still the continuation; later logging reuses v0.
        text = once(text, '    :cond_7\n', '''    :cond_7
    iget-object p0, v0, Lcee;->opairshareCallback:Ldsc;
''')
        return once(text, '    iget-object p0, p0, Lcen;->n:Ldsc;',
                    '    # p0 is the callback saved before suspension.')

    patched['cen'] = method(original['cen'],
        'public final k(Lcdb;Ljava/util/Map;Ldqd;)Ljava/lang/Object;', terminal)
    patched['cen'] = method(patched['cen'],
        'public final l(Lccu;Lbuw;Ldqd;)Ljava/lang/Object;', report)

    changes = []
    for name in original:
        if original[name] == patched[name]:
            continue
        (smali / (name + '.smali')).write_text(patched[name])
        diff = ''.join(difflib.unified_diff(original[name].splitlines(True),
                         patched[name].splitlines(True),
                         fromfile='original/' + name + '.smali',
                         tofile='patched/' + name + '.smali'))
        (output / (name + '.patch')).write_text(diff)
        changes.append(dict(file=name + '.smali', before=sha(original[name].encode()),
                            after=sha(patched[name].encode())))
    # Stop/shutdown, transfer jobs, progress rate, statuses and GMS are untouched.
    for sig in ('public static synthetic i(Lcen;ZLdqd;)Ljava/lang/Object;',
                'public static synthetic m(Lcen;Ldqd;)Ljava/lang/Object;'):
        def extract(t):
            start = t.index('.method ' + sig)
            return t[start:t.index('.end method', start)]
        assert extract(original['cen']) == extract(patched['cen'])
    assert patched['ecj'] == original['ecj']
    assert patched['cdw'] == original['cdw']
    assert patched['cbf'] == original['cbf']
    return changes


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--apk', type=Path, required=True)
    ap.add_argument('--decoded', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    if sha(args.apk.read_bytes()) != BASE_SHA:
        raise SystemExit('Only the verified original Mosey60715 APK is supported')
    args.output.mkdir(parents=True, exist_ok=False)
    changes = patch_tree(args.decoded, args.output)
    rebuilt = args.output / 'apktool-rebuilt.apk'
    with (args.output / 'build.log').open('w') as log:
        subprocess.run(['apktool', 'b', str(args.decoded), '-o', str(rebuilt)],
                       check=True, stdout=log, stderr=subprocess.STDOUT)
    candidate = args.output / 'Mosey60715-stage60-callback-unsigned.apk'
    removed = []
    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(rebuilt) as built, \
            zipfile.ZipFile(candidate, 'w') as dst:
        for entry in src.infolist():
            if re.fullmatch(r'META-INF/[^/]+\.(?:SF|RSA|DSA|EC|MF)', entry.filename, re.I):
                removed.append(entry.filename)
                continue
            data = built.read('classes2.dex') if entry.filename == 'classes2.dex' else src.read(entry.filename)
            dst.writestr(entry, data)
    unchanged = []
    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(candidate) as dst:
        assert dst.testzip() is None
        for name in dst.namelist():
            if name != 'classes2.dex':
                assert src.read(name) == dst.read(name), name
                unchanged.append(name)
        dex_sha = sha(dst.read('classes2.dex'))
    record = dict(input_apk_sha256=BASE_SHA, candidate=str(candidate.resolve()),
                  candidate_sha256=sha(candidate.read_bytes()), classes2_sha256=dex_sha,
                  changes=changes, unchanged_zip_members=unchanged,
                  removed_signature_members=removed, original_signature_valid=False,
                  watchdog_changed=False, installed=False,
                  runtime_verification='pending')
    (args.output / 'build.json').write_text(json.dumps(record, indent=2)+'\n')
    print(json.dumps({k:v for k,v in record.items() if k != 'unchanged_zip_members'}, indent=2))


if __name__ == '__main__':
    main()
