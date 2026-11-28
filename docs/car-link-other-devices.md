# Car link on other TomToms

The car link (Live traffic, weather and fuel prices through an Android phone) was built and tested on two units:

| Unit | NavCore | Where the patched program runs | State |
|---|---|---|---|
| TomTom ONE v4 | 9.541 (build 3145439.2) | on the card (community system) | works: traffic, weather, fuel |
| TomTom GO 950 LIVE | 9.541 (build 3145439.2), model 37 | swapped in at start-up by a script on the internal disk | works: traffic, weather, fuel |

**We don't build patches for other devices or NavCore versions.** This page says what is the same everywhere, what
needs patching and what doesn't, so you can judge whether your unit can do it and port it yourself. Everything is at
your own risk; keep backups of anything you replace.

## Needs no patching

| What | Notes |
|---|---|
| The map | Never touched, and neither are map licences or activation. |
| The phone side | MapsToTom on Android 8 or newer: Car link tab, Start. It plays a Sony Ericsson K510i on Vodafone PT, answers the TomTom's phone check and runs the data link itself. |
| Pairing | The app answers the PIN (0000) itself. The ONE v4 pairs from its phone menu; the GO 950 starts pairing on its own. If you removed the pairing on one side only and the TomTom then says "pairing rejected", remove it on both sides and **restart the TomTom** before pairing again. |
| Discovery files | Two text files in the root of the TomTom's disk (below). They tell NavCore where its Live servers are. |
| Weather | Open-Meteo, no key. |
| Fuel prices | Install a fuel set for your map from MapsToTom (POIs tab, or by cable from a computer); the phone then sends that day's prices for the same set. Open price sources exist for Portugal, Spain, France, Italy, Austria, Croatia and Slovenia only; stations elsewhere on your map get no prices, without errors. |
| Traffic incidents | From TomTom's traffic service with your own free key from developer.tomtom.com (Car link tab). |

Discovery files (plain text, two lines each):

```
servicediscovery.txt     herculesdiscovery.txt
192.168.77.1:8080        192.168.77.1:8080
/proxy/directory.php     /services/directory.php
```

## Needs patching: the NavCore program

NavCore only uses a Bluetooth phone for data, traffic and Live services in certain cases, and its Live traffic code
needs fixes to work without TomTom's servers. The patch writes to fixed places in one build, so **each NavCore build
needs its own port**: see [Porting the patch](navcore-survey.md#porting-the-patch). 7.x and older don't have the Live
code at all.

- **ONE v4 and other units with the community system** (card program): MapsToTom's Settings tab applies
  `files/navcore/9541-carlink.json` to NavCore 9.541 and undoes it.
- **Units with a built-in modem** (GO x40/x50 LIVE and similar): the same changes plus more, because NavCore picks a
  different path when it finds a modem, and some features are tied to the model number. For the GO 550/750/950
  (model 37) on 9.541 this is [`patches/9541-go950-carlink.json`](patches/9541-go950-carlink.json); see the next section.
  Other models with a modem need their model number handled the same way (a port).

## Needs adding in MapsToTom: your map's traffic key

The TomTom only asks for traffic when the service list it got from the phone has its map's key. The key is in your
map folder's `traffic.dat`: line 2 is the key, line 3 the product code. MapsToTom knows these:

| Map | Key |
|---|---|
| Iberia | `NTadespt` |
| Western Europe (e.g. Europe Zone 4) | `NTweu` |

If your TomTom says **"Traffic not available for your region"**, your map's key is missing: open an issue with lines
2 and 3 of your `traffic.dat`, or add them to `MAP_TRAFFIC` in `LiveServer.kt` yourself.

### When the TomTom keeps an old service list

NavCore keeps the service list it fetched in its settings and doesn't fetch it again when the list changes on the
phone. Changing the address in `servicediscovery.txt` doesn't help either. It fetches again only when
`servicediscovery.txt` **appears or disappears** between two starts. So, after a MapsToTom update that adds your key:

1. Remove `servicediscovery.txt` from the TomTom (keep a copy) and start the TomTom once. Its data setup or Live
   services may fail this time; that's expected. Switch it off, or plug it into the computer.
2. Put `servicediscovery.txt` back and start the TomTom with the car link running. The phone's log shows
   `GET /proxy/directory.php`: that is the new list.

## GO 550/750/950 (model 37), NavCore 9.541: step by step

The program runs from the read-only system file (`ttsystem`), so it can't be patched on the disk. Instead, a start-up
script swaps in a patched copy in memory every time the unit starts. Nothing is flashed and `ttsystem` is not
changed. The stock system already runs `ttntools/ttntool.sh` from the internal disk before the navigation program.

1. **Get your unit's program.** `ttsystem` holds a boot loader, the kernel and a gzip-compressed cpio archive; the
   program is `bin/ttn` in that archive (for example, `binwalk -e ttsystem`). Check it is NavCore 9.541: 8,177,884
   bytes. Name your copy `ttn.orig`.
2. **Patch it** on a computer (Python 3):
   ```
   python3 -I tools/navcore/apply_patch.py docs/patches/9541-go950-carlink.json ttn.orig ttn
   md5sum ttn | cut -c1-32 > ttn.md5
   ```
   The tool checks every place first and writes nothing if your build differs. Checked: stock 9.541 (md5 `45bcc403…`)
   gives `bb25aac5…` (not yet run on a unit); our GO 950, whose program differs from stock only in data the patch
   doesn't touch, gives `2eb7ffbc…` (runs, Live works).
3. **Copy to the TomTom's internal disk** a folder `ttntools` with `ttntool.sh` (from `tools/navcore/go950/`), `ttn`,
   `ttn.md5` and `ttn.orig`, and the two discovery files in the root.
4. **Phone database.** Our unit's own `connect2internet.db` failed the data setup ("impossible to configure"); the one
   from the ONE v4, with MapsToTom's phone listed first, worked. That database isn't published yet; MapsToTom's
   patcher will install it in a later version.
5. **Start the unit** with the car link running, pair from the GO's phone menu, and set up data when it asks.

What the script does: before the navigation program starts, it checks `ttn` against `ttn.md5` and swaps it in. It
hides the built-in modem from the patched program, so NavCore takes the phone path, as on the ONE v4, which has no
modem. It writes one line per step to `ttntools/log.txt`.

Switches, as empty files in `ttntools/`:

| File | Effect |
|---|---|
| `off` | Use TomTom's own program (also made automatically after 3 failed starts in a row). |
| `keepmodem` | Don't hide the built-in modem. |
| `ttnlog` | Write the navigation program's own messages to `ttn.log` (diagnostics only). |

To undo for good, delete the `ttntools` folder. A damaged `ttn`, 3 quick restarts or a crash loop also fall back to
TomTom's own program. The script in this repository works out the original's checksum from your `ttn.orig`. On our unit
it ran with that checksum written in; this exact copy hasn't run on a unit yet.

What the GO patch changes, on top of the ONE v4 patch (addresses = file offset + 0x8000):

| Address | Change |
|---|---|
| `0x43b30` | Bluetooth phone data also for model 37 (the ONE v4 patch covers model 24 only) |
| `0x31c4d0` | No legal notice at start-up |
| `0x42334`, `0x423c4` | Phone-as-modem also with a built-in modem present, and for model 37 |
| `0x41ea8` | Built-in modem feature off |
| `0x428c8`, `0x48b74` | Traffic and Live services for model 37 without the built-in modem |
| `0x2ceb2c` | Model 37 uses the ONE v4's main menu, which has Weather. Model 37 only gets that menu while its built-in modem is on, so without this Weather disappears. |
