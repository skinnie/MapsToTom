# TT Link

TT Link turns an old rooted Android phone into the Bluetooth "phone" that a TomTom ONE v4 or GO 950 (NavCore 9.541)
uses to go online. The phone answers the TomTom's dial-up call and carries the replacement Live services itself:
traffic, weather and fuel prices. You don't need a PC or a home server.

TT Link is the option for **old phones (Android 4.0-4.1)**. On Android 8 and newer, Tomtomaps (this repo's main app)
already does the same job without root: its car link plays the TomTom's phone and has the same services built in.

| your spare phone | use |
|---|---|
| Android 8 or newer | Tomtomaps' car link (no root) |
| Android 4.0-4.1, rooted | TT Link |
| Android 4.2-7.x | neither yet |

**Status (2026-11-03):** works on the PC; not yet tried on a phone.
- The services give the same bytes as the Python server for all 22 recorded requests from the ONE v4. These are
  the same anonymised recordings Tomtomaps' Kotlin services are tested against
  (`composeApp/src/desktopTest/resources/goldens`), so the two versions can't drift apart.
- A full run on the PC worked: AT dial, then PPP, then HTTP.
- Not yet tried: the Bluetooth part on Android 4.0.

## How it works

```
TomTom --Bluetooth (Dial-up Networking)--> TT Link on the old phone
           AT commands   -> answered like a Sony Ericsson K510i on Vodafone PT (a phone the TomTom knows)
           ATD*99# dial  -> CONNECT, then PPP: phone = 192.168.77.1, TomTom = 192.168.77.2
           192.168.77.1:8080 -> built-in services (logon, account, traffic, weather, fuel, search)
           anything else      -> straight to the internet through the phone's mobile data or Wi-Fi
built-in services --HTTPS--> TomTom Traffic API, Open-Meteo (weather), Photon (search)
```

The program carries its own HTTPS library because Android 4.0's built-in one is too old for today's servers.

## What you need

- **A phone with Android 4.0 or 4.1, rooted.**
  - Android 4.2 and newer use a different Bluetooth system (Bluedroid), which TT Link doesn't support.
  - The root app must allow `su` (SuperSU or Superuser).
- **Internet on that phone:** a SIM with data, or Wi-Fi such as your main phone's hotspot. Data use is small: a few KB
  for traffic, under 2 KB for weather, about 40 KB for a fuel update.
- **A TomTom traffic key** (free at developer.tomtom.com). Without it the traffic list stays empty; weather and fuel
  still work.
- **A TomTom card set up for Live** (once, by cable), as in `go950-navcore-9541/docs/SETUP.md` sections 2 and 3:
  - the patched `PNDNavigator`;
  - `servicediscovery.txt` and `herculesdiscovery.txt` pointing at `192.168.77.1:8080`;
  - `model:x40` and `bton` in `Config/setconfig.txt`.

## Install and first use

1. Install `out/ttlink.apk` with `adb install out/ttlink.apk`, or copy it to the phone and open it there. You need
   "Unknown sources" turned on in the security settings.
2. Open **TT Link**. Paste the TomTom key and press **Save**.
3. Turn on Bluetooth and press **Start**. The root app asks once; allow it, and tick "remember".
4. Press **Visible for pairing**. On the TomTom, open the phone settings, search, and pick the phone. Use PIN `0000`
   on both sides and choose it for data.
5. Use a Live service on the TomTom, or plan a route. The app should say *TomTom online*, and the log shows each
   request.

Tick **Start at boot** to have it start by itself whenever the phone starts.

## The screen

| part | what it does |
|---|---|
| Running / Stopped | whether the background program is running |
| second line | Bluetooth on/off; Waiting, Connected, Online or Disconnected for the TomTom |
| Start / Stop | starts or stops the program, as root |
| Visible for pairing | makes the phone visible to Bluetooth searches for 5 minutes |
| TomTom traffic key + Save | written to `ttlink.conf`; press Stop and Start for a new key to take effect |
| Start at boot | starts the program after the phone boots |
| Log | the newest lines of `ttlink.log` |

## Why it needs root

Root is used for three things.

1. **Offering "Dial-up Networking" over Bluetooth.**
   - The TomTom only connects to a phone that offers the Dial-up Networking service. It finds that service in the
     phone's Bluetooth service list (the SDP list).
   - TT Link adds this entry by talking directly to Android 4.0's Bluetooth service, through its local socket. That
     socket belongs to the system's Bluetooth user, so ordinary apps can't use it.
   - TT Link then listens on a raw Bluetooth serial channel (RFCOMM) for the TomTom.
2. **Staying alive in the background.**
   - The car link has to keep running for hours with the screen off.
   - Android closes background apps, and their programs with them, whenever it wants memory.
   - Started through `su`, the program runs on its own and survives that. Only **Stop**, or turning the phone off,
     ends it.
3. **Showing up as a "phone"** (only for devices that don't already: tablets). The `-phone-class` option sets the
   Bluetooth device type through a raw Bluetooth socket. A real phone already reports itself as a phone and doesn't
   need it.

**What root is not used for:**
- TT Link doesn't change system files and installs nothing outside its own folder (`/data/data/org.ttlink/files`).
- It reads no other app's data.
- Uninstalling the app removes everything.
- It only connects to the TomTom and to the services listed above.

**Without root:** that's what Tomtomaps does on Android 8 and newer. It offers the service through Android's own
Bluetooth interface for apps and stays alive as a foreground service. It can't help on Android 4.0: Tomtomaps doesn't
run there, and 4.0's built-in HTTPS is too old for today's servers. A rootless TT Link for 4.0 may be possible the same
way, with the Bluetooth part in Java handing the stream to this program, but that is untested.

## Files on the phone (`/data/data/org.ttlink/files`)

| file | content |
|---|---|
| `ttlink.conf` | `tomtom_key=...`; optionally `france=1` (French Bison Futé events) |
| `ttlink.log`, `ttlink.log.old` | program log; rotated at 512 KB |
| `ttlink.pid` | process number, used by Stop |
| `logs/requests.jsonl`, `logs/portland.jsonl` | every request the TomTom made. These also let a logged-in TomTom keep its session after a restart. |
| `*.prices.json` | fuel price sets (below) |

**Fuel prices:** make the set on a PC with `go950-navcore-9541/server/fuel.py` (see its `docs/SETUP.md` section 5). Then copy the
`.prices.json` into the app folder as root:
```
adb push Fuel_iberia.prices.json /sdcard/
adb shell su -c "cp /sdcard/Fuel_iberia.prices.json /data/data/org.ttlink/files/ && chmod 644 /data/data/org.ttlink/files/*.prices.json"
```
The matching `.ov2`/`.fpf` go on the TomTom card as before.

## When something is off

- **Log says "no BlueZ SDP socket found":** the phone's Bluetooth service keeps its socket somewhere unexpected.
  - Find it with `adb shell su -c "cat /proc/net/unix" | grep -i sdp`.
  - Start the program by hand with `-sdp <path>` (below).
- **Log says "SDP register ... error":** the Bluetooth service refused the entry. Send me the log line.
- **The TomTom doesn't find the phone:** press **Visible for pairing** again; it lasts 5 minutes.
- **"Root access failed":** the root app denied `su`. Allow TT Link in the root app.
- **The TomTom connects but traffic is empty:** check the key. The log says "no TomTom key" when there isn't one.

**Running it by hand** (shows everything on the screen):
```
adb shell
su
/data/data/org.ttlink/lib/libttlink.so -dir /data/data/org.ttlink/files [-sdp PATH] [-channel N] [-phone-class]
```
`-upstream HOST` sends the TomTom's service requests to the Python server on another computer instead of the built-in
services.

## Building (on the PC)

```
cd gmaps2tomtom/ttlink
. ./env.sh                 # Go 1.23 from dev-toolchain (newer Go needs Linux 3.2; these phones run 3.0)
go test ./...              # AT, PPP and the 22 recorded replies (shared with the Kotlin tests)
android/build-apk.sh       # -> out/ttlink.apk (no Gradle: javac, d8, aapt, zipalign, apksigner)
                           # signing key android/ttlink-debug.keystore is kept out of git; a new key means
                           # uninstalling the old app before installing
go run ./cmd/ttlink -tcp 127.0.0.1:7778 -dir /tmp/tt   # test mode: the TomTom side over TCP instead of Bluetooth
```

| folder | content |
|---|---|
| `cmd/ttlink` | the program: Bluetooth or test-mode listener, AT, then PPP |
| `internal/at` | AT answers (from `go950-navcore-9541/server/fakedun.py`) |
| `internal/ppp` | PPP endpoint with a TCP/DNS relay (from `server/userppp.py`) |
| `internal/bt` | RFCOMM listener, the DUN service entry, the phone class |
| `internal/services` | the Live services (from `server/mockserver.py` and its modules) |
| `android` | the TT Link app (plain Java, Android 4.0+) and its build script |
