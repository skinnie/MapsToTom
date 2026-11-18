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
