# MapsToTom

Send places and routes from Google Maps to an old TomTom (ONE, XL, GO and
other models that show up as a USB drive with a `ttgo.bif` file).
Made for, and tested against, the file layout of a TomTom ONE v4 running NavCore 9.541.

It runs on Linux, macOS and Windows with plain Python 3.8+ and Tk.
It needs no extra packages.

## Run it

```bash
python3 gmaps2tomtom.py
```

- **Linux:** if Tk is missing, install it with `sudo apt install python3-tk`.
- **macOS:** use the Python from python.org, which includes Tk.
- **Windows:** use the Python from python.org, then double-click `gmaps2tomtom.py`.

## Use it

1. Plug in the TomTom and wait for it to appear as a drive. The app finds it
   by itself, or you can press **Detect** or **Browse…**.
2. In Google Maps, make a route or open a place, then press **Share → Copy link**.
   With **Watch clipboard** ticked, the link is added as soon as you copy it.
   You can also paste it into the box and press **Add**.
3. Edit the list if you need to. You can reorder, rename by double-clicking,
   switch a point between Stop and Via, or remove it.
4. Save it to the TomTom:
   - **Save route (Itinerary)** writes `itn/<name>.itn`. On the TomTom, go to
     *Itinerary planning → Options → Load itinerary*.
   - **Save as POIs** adds the stops to the POI category named next to the
     button, "Google Maps" by default, in the active map folder, with a small
     icon. Points already in that category are skipped. On the TomTom, go to *Navigate to → Point of Interest*.
     From there you can save a POI as a Favourite.
   - **Export .itn file…** saves the itinerary anywhere, for example to copy later.
5. Eject the TomTom safely before unplugging it.

## What it understands

| Input | Example |
|---|---|
| Google Maps short link | `https://maps.app.goo.gl/…` |
| Phone share link without coordinates | `maps.app.goo.gl/…?g_st=…`, which opens `maps.google.com?q=<name, address>&ftid=…` |
| Place ID link | `https://maps.google.com/?cid=…` |
| Directions link, with stops and dragged via points | `https://www.google.com/maps/dir/Lisbon/Sintra/…/data=…` |
| Place link | `https://www.google.com/maps/place/Torre+de+Belém/…` |
| API, `q=` and old-style links | `…/maps/dir/?api=1&origin=…&destination=…`, `maps.google.com/?q=38.7,-9.1` |
| Coordinates | `38.6916, -9.2160` or `38°41'29.8"N 9°12'57.6"W` |
| Address or place name | `Praça do Comércio, Lisboa`, looked up on OpenStreetMap |

## Routes: how close to Google's route?

Google links contain only your stops and any points you dragged the route
through. They don't contain the road geometry. The TomTom calculates its own
route between those points, with its own map.

If the TomTom picks different roads, tick **Follow the same roads**. The app
then asks the public OSRM router, which uses OpenStreetMap data, for the road
shape. It adds pass-through points along that shape, up to the total you set.
More points keep the TomTom closer to the route, but the itinerary gets longer
to load. Older TomToms may not accept more than 48 points, so the app warns
above that.

## Online services

- **Google short links:** the app follows the redirect to read the full link.
- **Google place details:** used only for links that name a place but carry no
  coordinates, such as links shared from the phone app. The app loads the place
  the way the Google Maps page does, with cookie consent set to "reject all".
- **OpenStreetMap Nominatim:** used for typed addresses, and as a fallback when
  Google's place details give no answer. It retries without a leading business
  name, and sends at most one request per second. These positions can be a
  street's length away from Google's pin, so the app says to check them.
- **OSRM demo server:** used only when **Follow the same roads** is ticked.

Everything else works offline.

## File formats

- **`.itn` itinerary:** one line per point, `lon|lat|name|flag|`, with coordinates
  multiplied by 100000 and Windows-1252 text. The flag is 4 for the departure,
  2 for a stop or destination, and 0 for a pass-through point.
- **`.ov2` POI file:** binary type 2 records with the record length, longitude and
  latitude multiplied by 100000, and a zero-terminated name. New points are
  appended to an existing file.

## Make a standalone program

Build on each operating system you want a binary for, because PyInstaller
does not cross-compile.

```bash
pip install pyinstaller
pyinstaller --onefile --windowed --name gmaps2tomtom gmaps2tomtom.py
```

The program ends up in `dist/`.

## Tests

```bash
python3 -m unittest -v test_core
```
