#!/usr/bin/env python3
"""Make files/navcore/9541-carlink.json from an original and a patched NavCore 9.541 PNDNavigator.

Usage: python3 -I make_patch.py original patched out.json
Only the patched spots (old and new bytes) are stored, never TomTom's file. The start-up legal notice patch is left out: the car link
doesn't need it. Map licence checks are not touched by any of these patches.
"""
import hashlib
import json
import sys

LEGAL_NOTICE_OFFSET = 0x31c4d3 - 0x8000

a, b = (open(p, "rb").read() for p in sys.argv[1:3])
assert len(a) == len(b) == 8177884
assert hashlib.md5(a).hexdigest() == "732f12d5ce5f7c4341ac149ef1705183"
out = bytearray(a)
ranges = []
i = 0
while i < len(a):
    if a[i] == b[i] or i == LEGAL_NOTICE_OFFSET:
        i += 1
        continue
    j = last = i
    while j < len(a) and j - last <= 4:
        if a[j] != b[j] and j != LEGAL_NOTICE_OFFSET:
            last = j
        j += 1
    ranges.append({"o": i, "a": a[i:last + 1].hex(), "b": b[i:last + 1].hex()})
    out[i:last + 1] = b[i:last + 1]
    i = last + 1
json.dump({"name": "NavCore 9.541 build 3145439.2", "size": len(a), "from": hashlib.md5(a).hexdigest(),
           "to": hashlib.md5(out).hexdigest(), "ranges": ranges}, open(sys.argv[3], "w"), separators=(",", ":"))
print("ranges", len(ranges), "bytes", sum(len(r["b"]) // 2 for r in ranges), "to", hashlib.md5(out).hexdigest())
