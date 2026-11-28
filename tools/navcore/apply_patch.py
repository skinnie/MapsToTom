#!/usr/bin/env python3
"""Apply a MapsToTom NavCore patch file (docs/patches/*.json, files/navcore/*.json) to your own program file.

Usage: python3 -I apply_patch.py patch.json original out
Every range's old bytes must match, or nothing is written: a different build would get the wrong code overwritten.
The patch files hold only the changed spots, never TomTom's program.
"""
import hashlib
import json
import sys

patch = json.load(open(sys.argv[1]))
data = bytearray(open(sys.argv[2], "rb").read())
if len(data) != patch["size"]:
    sys.exit(f"wrong size {len(data)}, the patch is for {patch['size']} bytes ({patch['name']})")
bad = [r["o"] for r in patch["ranges"] if data[r["o"]:r["o"] + len(r["a"]) // 2].hex() != r["a"]]
if bad:
    sys.exit(f"{len(bad)} of {len(patch['ranges'])} places don't match (first at file offset {bad[0]:#x}): not this build, nothing written")
for r in patch["ranges"]:
    data[r["o"]:r["o"] + len(r["b"]) // 2] = bytes.fromhex(r["b"])
open(sys.argv[3], "wb").write(data)
print("patched", len(patch["ranges"]), "places, md5", hashlib.md5(data).hexdigest())
