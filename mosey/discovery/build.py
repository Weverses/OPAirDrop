#!/usr/bin/env python3
"""Build an opt-in discovery candidate on top of the exact r23 callback APK."""
from pathlib import Path
import argparse, hashlib, json, subprocess, zipfile, difflib, os

HERE=Path(__file__).resolve().parent
SDK=Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
def sha(data): return hashlib.sha256(data).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--decoded',type=Path,required=True)
    p.add_argument('--apk',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    p.add_argument('--base-sha256',required=True,help='SHA256 from the rebuilt callback build.json')
    p.add_argument('--transfers',action='store_true')
    p.add_argument('--standby',action='store_true')
    a=p.parse_args();assert sha(a.apk.read_bytes())==a.base_sha256
    a.out.mkdir(parents=True,exist_ok=False)
    file=a.decoded/'smali_classes2/byt.smali';before=file.read_text()
    start=before.index('.method public final i(Ljava/util/List;Lbyb;ZLdqd;)Ljava/lang/Object;')
    end=before.index('.end method',start)
    body=before[start:end]
    anchor='    :goto_8\n    move-object v12, v0\n'
    assert body.count(anchor)==1
    replacement=anchor+'''
    # OPAIRSHARE: discovery on verified SCC149 retains autojoin and STA association.
    iget-object v0, v1, Lbyt;->j:Landroid/net/wifi/WifiManager;
    invoke-static {v0, v2}, Ldev/evermore/opairshare/DiscoveryPolicy;->keepWifi(Landroid/net/wifi/WifiManager;Ljava/util/List;)Z
    move-result v0
    if-eqz v0, :opairshare_original_wifi_policy
    goto/16 :goto_b
    :opairshare_original_wifi_policy
'''
    body=body.replace(anchor,replacement)
    after=before[:start]+body+before[end:];file.write_text(after)
    (a.out/'byt.patch').write_text(''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile='r23/byt.smali',tofile='stage66/byt.smali')))
    changes={'byt.smali':{'before':sha(before.encode()),'after':sha(after.encode())}}
    def patch_method(name,signature,transform):
        path=a.decoded/'smali_classes2'/name
        old=path.read_text();begin=old.index('.method '+signature+'\n');finish=old.index('.end method',begin)
        new=old[:begin]+transform(old[begin:finish])+old[finish:]
        assert new!=old
        path.write_text(new)
        diff=''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='r23/'+name,tofile='stage66/'+name))
        diffpath=a.out/(name+'.patch')
        diffpath.write_text((diffpath.read_text() if diffpath.exists() else '')+diff)
        changes[name]={'before':changes.get(name,{}).get('before',sha(old.encode())),'after':sha(new.encode())}
    def once(s,old,new):
        assert s.count(old)==1,old
        return s.replace(old,new,1)
    if a.transfers:
        patch_method('cen.smali','public final declared-synchronized a(Lcom/google/android/gms/nearby/sharing/ShareTarget;)I',
            lambda s:once(s,'    if-eqz v2, :cond_1\n','''    if-eqz v2, :cond_1
    # Validation has found the real pending receive request.
    iget-object v2, p0, Lcen;->f:Lbyt;
    iget-object v2, v2, Lbyt;->j:Landroid/net/wifi/WifiManager;
    invoke-static {v2, p1}, Ldev/evermore/opairshare/DiscoveryPolicy;->beginTransfer(Landroid/net/wifi/WifiManager;Ljava/lang/Object;)V
'''))
        def send(s):
            anchor='    invoke-interface {v3, p1, p2, v0}, Lccf;->x(Ljava/lang/String;Lcom/google/android/gms/nearby/sharing/ShareTarget;Ldsc;)I'
            s=once(s,anchor,'''    iget-object v1, p0, Lcad;->g:Lbyt;
    iget-object v1, v1, Lbyt;->j:Landroid/net/wifi/WifiManager;
    invoke-static {v1, p2}, Ldev/evermore/opairshare/DiscoveryPolicy;->beginTransfer(Landroid/net/wifi/WifiManager;Ljava/lang/Object;)V
'''+anchor)
            return once(s,'    move-result p0\n','''    move-result p0
    invoke-static {p2, p0}, Ldev/evermore/opairshare/DiscoveryPolicy;->sendResult(Ljava/lang/Object;I)V
''')
        patch_method('cad.smali','public final d(Ljava/lang/String;Lcom/google/android/gms/nearby/sharing/ShareTarget;Laqa;)I',send)
        def update(s):
            return once(s,'    .locals 1\n','''    .locals 2
    iget-object v0, p1, Larc;->a:Lcom/google/android/gms/nearby/sharing/ShareTarget;
    iget-object v1, p1, Larc;->b:Lcom/google/android/gms/nearby/sharing/TransferMetadata;
    invoke-static {v0, v1}, Ldev/evermore/opairshare/DiscoveryPolicy;->onMetadata(Ljava/lang/Object;Ljava/lang/Object;)V
''')
        patch_method('aqr.smali','public final e(Larc;)V',update)
    if a.standby:
        def options(s):
            anchor='    .locals 6\n'
            return once(s,anchor,anchor+'''
    invoke-static {p1}, Ldev/evermore/opairshare/DiscoveryPolicy;->advertisingOptions(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object p1
    check-cast p1, Lapj;
''')
        patch_method('cad.smali','public final f(Lapj;Laqa;)I',options)
        patch_method('cad.smali','public final j(Lapj;)I',options)
        patch_method('cad.smali','public final h()I',lambda s:once(s,'    .locals 6\n','''    .locals 6
    invoke-static {}, Ldev/evermore/opairshare/DiscoveryPolicy;->retainAdvertising()Z
    move-result v0
    if-eqz v0, :opairshare_original_stop_advertising
    const/4 v0, 0x0
    return v0
    :opairshare_original_stop_advertising
'''))
    classes=a.out/'classes';classes.mkdir();dex=a.out/'dex';dex.mkdir()
    api=SDK/'platforms/android-36/android.jar';bt=SDK/'build-tools/36.0.0'
    subprocess.run(['javac','-source','17','-target','17','-cp',str(api),'-d',str(classes),str(HERE/'DiscoveryPolicy.java')],check=True)
    subprocess.run([str(bt/'d8'),'--lib',str(api),'--min-api','31','--output',str(dex),*[str(x) for x in classes.rglob('*.class')]],check=True)
    rebuilt=a.out/'rebuilt.apk'
    with (a.out/'apktool.log').open('w') as log:
        subprocess.run(['apktool','b',str(a.decoded),'-o',str(rebuilt)],stdout=log,stderr=subprocess.STDOUT,check=True)
    candidate=a.out/'base.apk'
    with zipfile.ZipFile(a.apk) as source,zipfile.ZipFile(rebuilt) as built,zipfile.ZipFile(candidate,'w') as target:
        assert 'classes4.dex' not in source.namelist()
        for entry in source.infolist():
            target.writestr(entry,built.read('classes2.dex') if entry.filename=='classes2.dex' else source.read(entry.filename))
        target.writestr('classes4.dex',(dex/'classes.dex').read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(a.apk) as source,zipfile.ZipFile(candidate) as target:
        assert set(target.namelist())==set(source.namelist())|{'classes4.dex'}
        for name in source.namelist():
            if name!='classes2.dex': assert source.read(name)==target.read(name),name
    manifest={'input_sha256':a.base_sha256,'candidate_sha256':sha(candidate.read_bytes()),'byt_input_sha256':sha(before.encode()),
              'byt_output_sha256':sha(after.encode()),'policy_java_sha256':sha((HERE/'DiscoveryPolicy.java').read_bytes()),
              'scope':'default-off same149 discovery; validated Accept/explicit Send leases and terminal Binder updates' if a.transfers else 'default-off same149 discovery only',
              'changes':changes,
              'standby_property_opt_in':a.standby,
              'unsigned_experiment':True,'installed':False}
    (a.out/'build.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest,indent=2))
if __name__=='__main__':main()
