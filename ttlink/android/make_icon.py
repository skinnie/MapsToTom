"""Draws res/drawable/icon.png (96x96): a blue rounded square with a white T and signal arcs. No PIL needed."""
import math, struct, sys, zlib
N = 96
def px(x, y):
    cx, cy, r = x - 47.5, y - 47.5, 14
    if max(abs(cx) - (48 - r), 0) ** 2 + max(abs(cy) - (48 - r), 0) ** 2 > r * r:
        return (0, 0, 0, 0)
    white = (255, 255, 255, 255)
    if 22 <= y < 32 and 18 <= x < 60 or 32 <= y < 76 and 34 <= x < 44:   # the T
        return white
    d = math.hypot(x - 60, y - 70)                                           # two arcs up-right of the T
    if -0.2 < math.atan2(70 - y, x - 60) < 1.75 and (24 <= d < 30 or 36 <= d < 42) and x > 50:
        return white
    return (30, 110, 200, 255)
raw = b"".join(b"\0" + b"".join(bytes(px(x, y)) for x in range(N)) for y in range(N))
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
