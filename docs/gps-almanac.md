# GPS almanac in the GL1 GPS program

Status: **experimental, under test on one TomTom ONE v4. Not offered in the app yet** (`ALMANAC_FEATURE`).

## What it is

The GL1 GPS program (`bin/gltt`, Global Locate GLL 185.5001.041, md5 `645d173e…`) has a table of satellite orbits
built in. It uses the table to predict the sky when it has nothing newer. The table is from week 1428 (2007), so most
of its orbits are out of date.

It doesn't need a patched NavCore. Which GPS program each NavCore carries:

| NavCore | GL1 program | Almanac table |
|---|---|---|
| 8.414, 9.001, 9.510, 9.541, community 9.702 | GLL 185.5001.041 (`645d173e…`, identical file) | `0xd38c0`, supported |
| 7.900 | GLL 185.5.039 (`8dc36f3f…`) | `0xd37f8`, same layout and week 404, not supported yet |
| Toyota 7.834 | GLL 185.5.039 (`0fae16ee…`) | `0xd37f8`, same layout and week 404, not supported yet |
| 6.52 | none | none |

This applies to GL1 only. GL2 units run `glgps`, and SiRF units keep their almanac in the GPS chip's own firmware.
A NavCore package carrying `gltt` doesn't mean TomTom offered that version to GL1 units.

## The table

File offset `0xd38c0`, 30 records of 88 bytes, little-endian:

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | PRN |
| 4 | double | eccentricity |
| 12 | double | time of applicability (s) |
| 20 | double | inclination (rad) |
| 28 | double | rate of right ascension (rad/s) |
| 36 | double | sqrt(A) |
| 44 | double | right ascension (rad) |
| 52 | double | argument of perigee (rad) |
| 60 | double | mean anomaly (rad) |
| 68 | double | af0 (s) |
| 76 | double | af1 (s/s) |
| 84 | u32 | 10-bit week |

The right ascension field was confirmed from the data: only that field groups into GPS's six orbital planes.

## The patch

MapsToTom fills the table from CelesTrak's public YUMA almanac and keeps the original as `bin/gltt.mapstotom-orig`.
Only bytes inside the table change. The table has 30 places, so PRNs 15 and 32 don't fit.

## Evidence so far

- The real `gltt` loads the patched table under qemu without errors and lists all 30 PRNs. The emulator has no
  satellites, so this says nothing about fix times.
- The jump from 4 to 9+ satellites on the ONE v4 (2026-10-30) came from shifting QuickGPSFix's dates, **not** from
  this patch. QuickGPSFix may already hide most of the almanac's benefit.

## Device test

First run on the ONE v4 (2026-11-21, one 40-minute stationary session, valid QuickGPSFix):

| | Before (42 runs with a fix, old table) | New almanac (1 run) |
|---|---|---|
| First fix | median 47 s (1 to 1172 s) | 47.8 s |
| Satellites used | median 7.25 | 7.6 (max 9) |
| HDOP | median 1.86 | 1.6 |

**Result: it runs correctly and did no harm, but it shows no measurable gain.** One run can't show one, the earlier
runs were in other places and conditions, and a valid QuickGPSFix does the almanac's job. The case where it should
matter is a cold start **without** a valid QuickGPSFix, which hasn't been tested. It stays hidden until it has.
