#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
"""Build the eMMC variant of the D325f boot.img.

In the published boot.img (for a phone running from microSD) the device tree
node /soc/sdhci@f9824900 (SDC1, the eMMC slot) has status = "disabled", so the
SD card becomes mmcblk0; LineageOS has "ok" there.  This script sets it back to "ok" in every DTB of the QCDT
dt.img, keeps kernel and ramdisk byte-identical, recomputes the mkbootimg id and
pads the image to the same size.
Usage: make-emmc-bootimg.py boot-sd.img boot-emmc.img
"""
import hashlib, struct, sys

NODE, PROP, NEW = 'sdhci@f9824900', 'status', b'ok\0'

def set_prop(dtb):
    (magic, tot, o_st, o_str, o_rsv, ver, lc, cpu, sz_str, sz_st) = struct.unpack('>10I', dtb[:40])
    assert magic == 0xd00dfeed and o_rsv < o_st < o_str, 'unexpected FDT layout'
    def name(off):
        e = dtb.index(b'\0', o_str + off); return dtb[o_str + off:e].decode()
    p, path, hits = o_st, [], 0
    out = bytearray()
    while True:
        tok = struct.unpack('>I', dtb[p:p + 4])[0]
        if tok == 1:
            e = dtb.index(b'\0', p + 4); nxt = (e + 4) & ~3
            path.append(dtb[p + 4:e].decode()); out += dtb[p:nxt]; p = nxt
        elif tok == 2:
            path.pop(); out += dtb[p:p + 4]; p += 4
        elif tok == 3:
            ln, no = struct.unpack('>II', dtb[p + 4:p + 12]); nxt = (p + 12 + ln + 3) & ~3
            if path and path[-1] == NODE and name(no) == PROP:
                assert dtb[p + 12:p + 12 + ln] == b'disabled\0'
                v = NEW + b'\0' * (-len(NEW) % 4)
                out += struct.pack('>III', 3, len(NEW), no) + v; hits += 1
            else:
                out += dtb[p:nxt]
            p = nxt
        elif tok == 4:
            out += dtb[p:p + 4]; p += 4
        elif tok == 9:
            out += dtb[p:p + 4]; p += 4; break
        else:
            raise ValueError(tok)
    assert hits == 1, hits
    assert p == o_st + sz_st
    strings = dtb[o_str:o_str + sz_str]
    head_rsv = dtb[40:o_st]
    new_o_str = o_st + len(out)
    new = bytearray(struct.pack('>10I', magic, 0, o_st, new_o_str, o_rsv, ver, lc, cpu, sz_str, len(out)))
    new += head_rsv + out + strings
    struct.pack_into('>I', new, 4, len(new))
    return bytes(new)

def rebuild_qcdt(dt, page):
    magic, ver, n = struct.unpack('<4sII', dt[:12]); assert magic == b'QCDT' and ver == 2
    ents = [list(struct.unpack('<6I', dt[12 + 24 * i:36 + 24 * i])) for i in range(n)]
    blobs = {}
    for e in ents:
        key = (e[4], e[5])
        if key not in blobs:
            blob = set_prop(dt[e[4]:e[4] + e[5]])
            blobs[key] = blob + b'\0' * (-len(blob) % 1024)   # dtbTool pads entries to 1 KiB
    hdr = 12 + 24 * n + 4                  # table ends with a zero word
    off = (hdr + page - 1) // page * page
    place = {}
    for key in sorted(blobs):
        place[key] = off; off += (len(blobs[key]) + page - 1) // page * page
    out = bytearray(off)
    out[:12] = dt[:12]
    for i, e in enumerate(ents):
        k = (e[4], e[5])
        struct.pack_into('<6I', out, 12 + 24 * i, e[0], e[1], e[2], e[3], place[k], len(blobs[k]))
    for k, o in place.items(): out[o:o + len(blobs[k])] = blobs[k]
    return bytes(out)

def main(src, dst):
    b = open(src, 'rb').read()
    ks, ka, rs, ra, ss, sa, ta, ps, dts = struct.unpack('<9I', b[8:44])
    rnd = lambda x: (x + ps - 1) // ps * ps
    k0 = ps; r0 = k0 + rnd(ks); s0 = r0 + rnd(rs); d0 = s0 + rnd(ss)
    dt_new = rebuild_qcdt(b[d0:d0 + dts], ps)
    hdr = bytearray(b[:ps]); struct.pack_into('<I', hdr, 40, len(dt_new))
    h = hashlib.sha1()
    for blob in (b[k0:k0 + ks], b[r0:r0 + rs], b[s0:s0 + ss], dt_new):
        h.update(blob); h.update(struct.pack('<I', len(blob)))
    hdr[576:608] = h.digest() + b'\0' * 12
    img = bytes(hdr) + b[k0:d0] + dt_new + b'\0' * (rnd(len(dt_new)) - len(dt_new))
    assert len(img) <= len(b)
    img += b'\0' * (len(b) - len(img))
    open(dst, 'wb').write(img)

if __name__ == '__main__':
    main(*sys.argv[1:3])
