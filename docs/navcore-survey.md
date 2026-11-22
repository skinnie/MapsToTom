# NavCore survey

Read-only study of NavCore packages for the car link and a future "NavCore kitchen". Nothing from the packages was
run, and none of their files are in this repository. Some are official, most are community builds.

## Which NavCore fits the car link patch

The patch writes to 25 fixed places, so it only applies when all of them hold the expected code.

| NavCore | Program md5 | Program in memory | Patch sites that match | Status |
|---|---|---|---|---|
| 9.541 (ONE v4, tested on a real device) | `732f12d5` | 8.68 MB | 25/25 | **Works** |
| 9.541 DSA, treysis pack, TZ | `aa395648` | 8.68 MB | 25/25 | Fits, untested |
| 9.530 SE | `7d3f7140` | 8.68 MB | 25/25 | Fits, untested |
| 9.510 (Downunder, swap) | `b61c1d6f` | 8.68 MB | 25/25 | Fits, untested |
| 9.701 SE | `0904f48c` | 8.78 MB | 2/25 | Needs a port |
| 9.702 DSA WNRO | `109924d1` | 8.78 MB | 1/25 | Needs a port |
| 9.001 | `445c6272` | 7.63 MB | 0 of the code sites | Needs a port |
| 8.414 | `2a6c510c` | 6.80 MB | 1/25 | Needs a port, has the Live code |
| 7.900, 7.834, 6.52 | | 3.8 to 4.9 MB | 0 | No Live (Hercules) code |

So 9.510, 9.530 and 9.541 are one code family: the "9.5xx" NavCores. Others need a port, which is not a priority. "In memory" is the program's maximum size. Linux only loads the parts
it uses, so real use is lower, and this doesn't prove that an older NavCore is faster.

## Porting the patch

8.x and 9.x NavCores with the Live code can get the car link, but each build needs its own port, because the patch
writes to fixed addresses. 7.x and 6.52 don't have the Live code, so they can't.

To port it, find the same code in the other build (Ghidra helps), make the same changes, test it in an emulator and
then on a real TomTom. `tools/navcore/make_patch.py` shows how a patch file is made from an original and a patched
program. Addresses below are for 9.541 (address = file offset + 0x8000):

| Address | What the change does |
|---|---|
| `0x808c` | Makes the code segment writable, for the added code |
| `0x43b18` | Turns the Bluetooth phone data feature on for model x40 |
| `0x12e41c` | Makes the Bluetooth phone the default connection |
| `0x12df18` | Allows the Bluetooth phone for traffic (ROUTER2) |
| `0x138afc` | Lets any request use the open connection |
| `0x13346c`, `0x133480` | Request handler fixes |
| `0x49718` | Opens the traffic menu from its feature flag alone |
| `0x14fe20` | Traffic status no longer needs a held connection |
| `0x55790c` | Always arms the traffic provider |
| `0x559b80`, `0x5634fc`, `0x566df0`, `0x56771c` | Hooks into the added code: periodic fetch and session checks |
| `0x567028` | Simplifies the traffic request gate |
| `0x563ed4` | One unknown incident no longer drops the whole reply |
| `0x78c334`, `0x78c484` | A virtual Stop no longer deletes the traffic provider |
| `0x768c00` to `0x7692c0` | The added code and its data, in unused space |

Don't change anything to do with map licences or activation.

## How community systems set the device model

Community `ttsystem` images read `Config/setconfig.txt` (or the same words as single files) at each full start, in
`/etc/rc`. This is what "putting it as the right device" does: NavCore turns features on and off by model id.

| `model:` | Model id | Shown as |
|---|---|---|
| `ori` | the real one | Original |
| `xls` | 16 | XLS |
| `rider` | 17 | Rider |
| `x20` | 18 | x20 |
| `x40` | 24 | x40 (also the default for an unknown name) |
| `x30` | 25 | x30 |
| `x40light` | 34 | ONE IQ Routes Edition |
| `x40light2` | 35 | XL IQ Routes Edition |
| `x40light3` | 36 | XL LIVE IQ Routes Edition |
| `x50` | 37 | x50 |
| `start` | 39 | Start |
| `start2` | 43 | Start 2 |

With no `model`, the script picks 34 for some screen types and 24 for the rest. `modelid:<n>` overrides `model`.

Other words: `bton`, `btoff`, `ttsoff`, `ttson`, `fmon`, `fmoff`, `fixtmc`, `fixpna`, `delgns`, `delmap`, `silent`,
`usesetting`, `cleanflash`, `runcheck`, `lighton`, `remoteon`, `noqfixdel`, `sdid:`, `tmcnum:`, `btpin:`,
`changedtext:`. The script matches words with `grep`, so a word inside another line counts too.

The start-up log is written to `text/config.txt` (`Current Emulation: ...`), which tells you what a device really used.

The system also removes programs the hardware can't use at start: `gltt` on GL2, `glgps` on GL1, and `sirfreset` and
`clmapp` on GL units.

## GPS programs

Every 8.414 to 9.702 package here carries the same GL1 program (`gltt` `645d173e`). GL2 `glgps` comes in several
builds: `3880d288` (the fixed 2.16.201 in 9.510 Downunder, 9.541, 9.702), `c67c1924` (9.510 swap, 9.530 SE, 9.701 SE)
and `a442414b` (8.414).

## Bluetooth file transfer

`bin/obexftpd` is only in 9.510 Downunder and 9.701 SE. MapsToTom's Bluetooth delivery therefore uses its own small
helper instead.

## Kitchen

In the app now: what the card runs (NavCore, system, GPS program, model, what the last start used), choosing the
device model, and a checked full backup and restore.

Profiles were measured, not guessed: NavCore 9.541 ran in the emulator with a ONE v4's hardware flags and every model id
from 1 to 70, and the `Features` line it wrote to `ttgo.bif` was compared. With `bton`, id 24 matches the real device's
list exactly. Only ids 24 (x40) and 36 (`x40light3`) have `LiveServices`. Id 36 is the closest to the original ONE (id 15):
it adds Live and local search and drops photos, but it also has no `PhoneFeatures`. It hasn't been tried on a real device.
NavCore's `overridesettings.ini` isn't opened in the first 40 s of a start, and no sample or documentation was found.

Ideas for later:

- Other `setconfig` switches, each with a clear reason. `cleanflash`, `delgns` and `delmap` delete data, so they need care.
- Recommend the lightest NavCore that supports the device, map and wanted features.
- Never touch map licences or activation.

## Not read

The "Universal NavCore Installer" holds 9.510, Rider and Truck builds in archives with their own password, so they
were left closed.
