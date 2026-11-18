"""Writes the small Parquet files the Kotlin reader's tests read (ParquetTest.kt). Needs pyarrow."""
import sys, os, json
import pyarrow as pa, pyarrow.parquet as pq
out = sys.argv[1]
rows = []
for i in range(1000):
    rows.append({
        "name": None if i % 97 == 0 else f"Place {i} ção",
        "latitude": 38.0 + i / 1000, "longitude": -9.0 - i / 2000,
        "country": "PT" if i < 600 else "ES",
        "date_refreshed": f"{2012 + i % 14}-0{1 + i % 9}-15",
        "date_closed": "2023-01-01" if i % 50 == 0 else None,
        "fsq_category_labels": None if i % 31 == 0 else [] if i % 37 == 0 else
            (["Dining and Drinking > Restaurant > Portuguese Restaurant"] if i % 3 == 0 else
             ["Retail > Pharmacy", None] if i % 3 == 1 else ["Travel and Transportation > Fuel Station", "Retail"]),
    })
schema = pa.schema([("name", pa.string()), ("latitude", pa.float64()), ("longitude", pa.float64()), ("country", pa.string()),
                    ("date_refreshed", pa.string()), ("date_closed", pa.string()), ("fsq_category_labels", pa.list_(pa.string()))])
t = pa.Table.from_pylist(rows, schema=schema)
variants = {
    "v1-snappy-dict": dict(compression="snappy", use_dictionary=True, data_page_version="1.0"),
    "v2-none-plain": dict(compression="none", use_dictionary=False, data_page_version="2.0"),
    "v2-snappy-dict": dict(compression="snappy", use_dictionary=True, data_page_version="2.0"),
}
for k, opts in variants.items():
    pq.write_table(t, os.path.join(out, k + ".parquet"), row_group_size=400, data_page_size=512, **opts)
json.dump(rows, open(os.path.join(out, "expected.json"), "w"), ensure_ascii=False)

# Overture-shaped file: nested structs and lists, Zstandard, sorted by area into row groups.
ov = []
kinds = [["food_and_drink", "restaurant", "portuguese_restaurant"], ["food_and_drink", "casual_eatery", "cafe"],
         ["lodging", "campground"], ["shopping", "clothing_store"], None]
for i in range(900):
    lon = -9.5 + (i // 300) * 3 + (i % 300) / 1000  # three areas, one per row group
    lat = 38.0 + (i % 300) / 1000
    ov.append({
        "names": None if i % 101 == 0 else {"primary": f"Sítio {i}"},
        "taxonomy": {"hierarchy": kinds[i % 5]} if kinds[i % 5] else None,
        "addresses": [] if i % 43 == 0 else [{"country": "ES" if i >= 600 else "PT"}],
        "operating_status": "permanently_closed" if i % 47 == 0 else ("open" if i % 3 == 0 else None),
        "confidence": (i % 10) / 10,
        "bbox": {"xmin": lon, "xmax": lon, "ymin": lat, "ymax": lat},
    })
ovs = pa.schema([("names", pa.struct([("primary", pa.string())])), ("taxonomy", pa.struct([("hierarchy", pa.list_(pa.string()))])),
                 ("addresses", pa.list_(pa.struct([("country", pa.string())]))), ("operating_status", pa.string()),
                 ("confidence", pa.float64()), ("bbox", pa.struct([("xmin", pa.float64()), ("xmax", pa.float64()), ("ymin", pa.float64()), ("ymax", pa.float64())]))])
ot = pa.Table.from_pylist(ov, schema=ovs)
for k, v in {"overture-v1-zstd": "1.0", "overture-v2-zstd": "2.0"}.items():
    pq.write_table(ot, os.path.join(out, k + ".parquet"), row_group_size=300, data_page_size=1024, compression="zstd", data_page_version=v)
json.dump(ov, open(os.path.join(out, "overture-expected.json"), "w"), ensure_ascii=False)
