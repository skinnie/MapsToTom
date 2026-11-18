"""Country -> {file: [row groups]} for one Foursquare OS Places release on Source.coop, from parquet footer stats."""
import sys, json, re, urllib.request, urllib.parse, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pyarrow.parquet as pq
from fsqindex_http import HttpFile
REL = sys.argv[1]; BASE = "https://data.source.coop/fused/"
keys, token = [], None
while True:
    x = urllib.request.urlopen(BASE + "?list-type=2&prefix=" + REL + ("&continuation-token=" + urllib.parse.quote(token) if token else "")).read().decode()
    keys += re.findall(r"<Key>([^<]*\.parquet)</Key>", x)
    m = re.search(r"<NextContinuationToken>([^<]*)<", x)
    if not m: break
    token = m.group(1)
# Stats give only the first and last code of a row group, so mixed row groups have their country column read
# (a few hundred bytes each) to list exactly which countries they hold.
out = {}
nostats = mixed = 0
for k in sorted(keys, key=lambda k: int(re.search(r"(\d+)\.parquet$", k).group(1))):
    f = pq.ParquetFile(HttpFile(BASE + k)); md = f.metadata; name = k.rsplit("/", 1)[1]
    ci = [md.schema.column(i).path for i in range(md.num_columns)].index("country")
    for i in range(md.num_row_groups):
        s = md.row_group(i).column(ci).statistics
        if s is not None and s.has_min_max and s.min == s.max: codes = {s.min}
        else:
            if s is None or not s.has_min_max: nostats += 1
            mixed += 1
            codes = {c for c in f.read_row_group(i, columns=["country"]).column(0).to_pylist() if c}
        for c in codes: out.setdefault(c, {}).setdefault(name, []).append(i)
    print(k, md.num_row_groups, file=sys.stderr)
print("row groups without stats:", nostats, "mixed:", mixed, file=sys.stderr)
json.dump({"release": REL.split("/")[1], "base": BASE + REL, "countries": out}, sys.stdout, separators=(",", ":"))
