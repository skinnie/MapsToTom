"""Country -> a few boxes [west, south, east, north] covering its land, from Natural Earth admin-0 (public domain).
Used to pick which parts of area-sorted place files to download. Usage: country_boxes.py ne_10m_admin_0_countries.geojson > country-boxes.json"""
import sys, json, math
MAX_BOXES, PAD = 12, 0.05
def parts(geom):
    polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
    for p in polys:
        xs = [c[0] for c in p[0]]; ys = [c[1] for c in p[0]]
        yield [min(xs) - PAD, min(ys) - PAD, max(xs) + PAD, max(ys) + PAD]
def touch(a, b, gap): return a[0] - gap <= b[2] and b[0] - gap <= a[2] and a[1] - gap <= b[3] and b[1] - gap <= a[3]
def union(a, b): return [min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3])]
def area(a): return (a[2] - a[0]) * (a[3] - a[1])
def merge(boxes):
    boxes = list(boxes)
    changed = True
    while changed:  # join boxes that overlap or nearly touch
        changed = False
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                if touch(boxes[i], boxes[j], 0.5):
                    boxes[i] = union(boxes[i], boxes.pop(j)); changed = True; break
            if changed: break
    while len(boxes) > MAX_BOXES:  # then the pair whose union adds the least area
        best = min(((i, j) for i in range(len(boxes)) for j in range(i + 1, len(boxes))),
                   key=lambda ij: area(union(boxes[ij[0]], boxes[ij[1]])) - area(boxes[ij[0]]) - area(boxes[ij[1]]))
        i, j = best; boxes[i] = union(boxes[i], boxes.pop(j))
    return [[math.floor(b[0] * 100) / 100, math.floor(b[1] * 100) / 100, math.ceil(b[2] * 100) / 100, math.ceil(b[3] * 100) / 100] for b in boxes]
out = {}
for f in json.load(open(sys.argv[1]))["features"]:
    p = f["properties"]; code = p.get("ISO_A2_EH") if p.get("ISO_A2") in (None, "-99") else p["ISO_A2"]
    if not code or code == "-99" or f["geometry"] is None: continue
    out.setdefault(code, []).extend(parts(f["geometry"]))
json.dump({c: merge(b) for c, b in sorted(out.items())}, sys.stdout, separators=(",", ":"))
