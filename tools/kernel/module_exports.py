#!/usr/bin/env python3
"""exported symbol -> CRC from an AArch64 .ko (CONFIG_MODVERSIONS).

__ksymtab entries are {int value_off; int name_off; int ns_off;} (PREL32, left
unapplied in a .ko). The name field's relocation points into __ksymtab_strings
with an addend that is the string offset -- the symbol name is NOT the symbol
of the relocation.
"""
import struct, sys, json

def load(path):
    d = open(path, 'rb').read()
    e_shoff, = struct.unpack_from('<Q', d, 0x28)
    ent, num, shstr = struct.unpack_from('<HHH', d, 0x3a)
    o, s = struct.unpack_from('<QQ', d, e_shoff + shstr*ent + 0x18)
    st = d[o:o+s]
    secs = []
    for i in range(num):
        p = e_shoff + i*ent
        nm, typ = struct.unpack_from('<II', d, p)
        foff, fsz = struct.unpack_from('<QQ', d, p + 0x18)
        link, = struct.unpack_from('<I', d, p + 0x28)
        secs.append(dict(idx=i, name=st[nm:st.index(b'\0', nm)].decode(),
                         type=typ, off=foff, size=fsz, link=link))
    return d, secs

def exports(path):
    d, secs = load(path)
    # symbol table
    ss = next(s for s in secs if s['type'] == 2)
    t = secs[ss['link']]
    nmtab = d[t['off']:t['off']+t['size']]
    syms = []
    for i in range(ss['size'] // 24):
        p = ss['off'] + i*24
        st_name, st_info, st_other, st_shndx = struct.unpack_from('<IBBH', d, p)
        syms.append((nmtab[st_name:nmtab.index(b'\0', st_name)].decode('utf-8','replace'), st_shndx))
    res = {}
    for ktab, ctab in (("__ksymtab", "__kcrctab"), ("__ksymtab_gpl", "__kcrctab_gpl")):
        k = next((s for s in secs if s['name'] == ktab), None)
        c = next((s for s in secs if s['name'] == ctab), None)
        if not k or not c:
            continue
        rel = {}
        for r in secs:
            if r['name'].startswith('.rela_' + ktab + '+'):
                for i in range(r['size'] // 24):
                    p = r['off'] + i*24
                    ro, ri, ra = struct.unpack_from('<QQq', d, p)
                    rel[ro] = (ri >> 32, ra)
        for i in range(k['size'] // 12):
            nf = i*12 + 4
            if nf not in rel:
                continue
            symidx, addend = rel[nf]
            _, shndx = syms[symidx]
            sec = secs[shndx]
            base = sec['off'] + addend
            name = d[base:d.index(b'\0', base)].decode('utf-8', 'replace')
            crc, = struct.unpack_from('<I', d, c['off'] + i*4)
            res[name] = "0x%08x" % crc
    return res

if __name__ == "__main__":
    if sys.argv[1] == "--dump":
        print(json.dumps(exports(sys.argv[2]), indent=1, sort_keys=True)); sys.exit(0)
    a, b = exports(sys.argv[1]), exports(sys.argv[2])
    print(f"{sys.argv[1].split('/')[-1]}: {len(a)} exports    {sys.argv[2].split('/')[-1]}: {len(b)} exports")
    diff = sorted(k for k in set(a) & set(b) if a[k] != b[k])
    print(f"共同符号 CRC 不同: {len(diff)}")
    for k in diff[:8]: print("   ", k, a[k], "vs", b[k])
