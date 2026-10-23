"""Core logic for gmaps2tomtom: parse Google Maps links, write TomTom ITN/OV2 files.

No third-party dependencies. Works on Linux, macOS and Windows.
"""
from __future__ import annotations

import json
import math
import os
import re
import string
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

USER_AGENT = "gmaps2tomtom/1.0 (personal route transfer tool)"

STOP, VIA, SHAPE = "stop", "via", "shape"


@dataclass
class Point:
    name: str
    lat: float
    lon: float
    kind: str = STOP  # stop: a destination; via: Google drag point; shape: added along the road

    def label(self) -> str:
        return f"{self.lat:.6f}, {self.lon:.6f}"


@dataclass
class ParseResult:
    points: list[Point] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


# ---------------------------------------------------------------- coordinates

_NUM = r"[-+]?\d{1,3}(?:\.\d+)?"
COORD_RE = re.compile(rf"^\s*\(?\s*({_NUM})\s*[,;\s]\s*({_NUM})\s*\)?\s*$")
DMS_RE = re.compile(
    r"(\d{1,3})\s*°\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*(?:\"|″|'')\s*([NSEW])", re.I
)


def parse_coords(text: str) -> tuple[float, float] | None:
    """Return (lat, lon) for '38.7, -9.1' style text or DMS like 38°42'05.0"N 9°08'21.6"W."""
    text = urllib.parse.unquote_plus(text).strip()
    m = COORD_RE.match(text)
    if m:
        lat, lon = float(m.group(1)), float(m.group(2))
        if -90 <= lat <= 90 and -180 <= lon <= 180:
            return lat, lon
        return None
    parts = DMS_RE.findall(text)
    if len(parts) == 2:
        vals = {}
        for d, mi, s, h in parts:
            v = int(d) + int(mi) / 60 + float(s) / 3600
            h = h.upper()
            if h in "SW":
                v = -v
            vals["lat" if h in "NS" else "lon"] = v
        if len(vals) == 2:
            return vals["lat"], vals["lon"]
    return None


# ---------------------------------------------------------------- google "data=" blob

def _parse_data_tokens(tokens: list[str], i: int, end: int) -> list:
    """Google's !-encoded protobuf: '4m3' = message holding the next 3 tokens."""
    items = []
    while i < end and i < len(tokens):
        m = re.match(r"(\d+)([a-zA-Z])(.*)", tokens[i])
        if not m:
            i += 1
            continue
        fnum, typ, val = int(m.group(1)), m.group(2), m.group(3)
        if typ == "m":
            n = int(val) if val.isdigit() else 0
            items.append((fnum, "m", _parse_data_tokens(tokens, i + 1, i + 1 + n)))
            i += 1 + n
        else:
            items.append((fnum, typ, val))
            i += 1
    return items


def parse_data_blob(data: str) -> list:
    data = urllib.parse.unquote(data)
    tokens = [t for t in data.split("!") if t]
    return _parse_data_tokens(tokens, 0, len(tokens))


def _latlon_of(node_children) -> tuple[float, float] | None:
    vals = {f: v for f, t, v in node_children if t == "d"}
    try:
        return float(vals[2]), float(vals[1])  # 1d = lon, 2d = lat
    except (KeyError, ValueError):
        return None


def _child(children, fnum):
    for f, t, v in children:
        if f == fnum and t == "m":
            return v
    return None


def _is_stop(children) -> bool:
    if not children:  # '!1m0' = "Your location"
        return True
    c2 = _child(children, 2)
    return (c2 is not None and _latlon_of(c2) is not None) or _child(children, 1) is not None


def _find_stop_list(tree):
    """Find the message whose repeated field 1 entries are route stops."""
    for f, t, v in tree:
        if t != "m":
            continue
        stops = [c for cf, ct, c in v if cf == 1 and ct == "m"]
        if stops and all(_is_stop(s) for s in stops) and any(_child(s, 2) for s in stops):
            return stops
        found = _find_stop_list(v)
        if found:
            return found
    return None


# ---------------------------------------------------------------- google URLs

GOOGLE_HOST_RE = re.compile(r"(^|\.)google\.[a-z.]+$|^maps\.google\.[a-z.]+$")
SHORT_HOSTS = {"maps.app.goo.gl", "goo.gl", "g.co"}


def is_google_maps_url(url: str) -> bool:
    p = urllib.parse.urlparse(url)
    return bool(GOOGLE_HOST_RE.search(p.hostname or "")) and (
        p.path.startswith("/maps") or (p.hostname or "").startswith("maps.")
    )


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def resolve_short_url(url: str, timeout: float = 10) -> str:
    """Follow maps.app.goo.gl redirects by hand, stopping at the first google maps URL.

    Stopping early avoids Google's cookie-consent page, which hides the real link.
    """
    opener = urllib.request.build_opener(_NoRedirect)
    for _ in range(6):
        if is_google_maps_url(url):
            return url
        host = urllib.parse.urlparse(url).hostname or ""
        if host.startswith("consent."):
            cont = urllib.parse.parse_qs(urllib.parse.urlparse(url).query).get("continue")
            if cont:
                return cont[0]
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            opener.open(req, timeout=timeout)
            return url
        except urllib.error.HTTPError as e:
            loc = e.headers.get("Location")
            if e.code in (301, 302, 303, 307, 308) and loc:
                url = urllib.parse.urljoin(url, loc)
                continue
            raise
    return url


def _clean_label(seg: str) -> str:
    return urllib.parse.unquote_plus(seg).strip()


def _split_path(path: str) -> list[str]:
    return [s for s in path.split("/")]


def parse_google_url(url: str) -> tuple[list[Point], list[str], list[str]]:
    """Return (points, unresolved_names, warnings). Unresolved names need geocoding."""
    p = urllib.parse.urlparse(url)
    qs = urllib.parse.parse_qs(p.query)
    path = p.path
    data = ""
    if "/data=" in path:
        path, data = path.split("/data=", 1)
    tree = parse_data_blob(data) if data else []
    segs = _split_path(path)
    points: list[Point] = []
    unresolved: list[str] = []
    warnings: list[str] = []

    def add_named(label: str, kind=STOP, coords=None):
        c = coords or parse_coords(label)
        if c:
            points.append(Point(label if not parse_coords(label) else _pretty(c), c[0], c[1], kind))
        elif label:
            points.append(Point(label, math.nan, math.nan, kind))
            unresolved.append(label)

    # 1. /maps/dir/A/B/C/@center/data=...
    if "dir" in segs:
        idx = segs.index("dir")
        labels = []
        for s in segs[idx + 1:]:
            if s.startswith("@"):
                break
            labels.append(_clean_label(s))
        while labels and labels[-1] == "":
            labels.pop()
        stops = _find_stop_list(tree) or []
        n = max(len(labels), len(stops))
        for i in range(n):
            label = labels[i] if i < len(labels) else ""
            coords = None
            vias = []
            if i < len(stops):
                c2 = _child(stops[i], 2)
                coords = _latlon_of(c2) if c2 else None
                for f, t, v in stops[i]:
                    if f == 3 and t == "m":
                        inner = _child(v, 1)
                        ll = _latlon_of(inner) if inner else None
                        if ll:
                            vias.append(ll)
            if not label and not coords:
                warnings.append(f"Stop {i + 1} is 'your location'; the TomTom will start from its GPS position.")
            else:
                if coords and not label:
                    label = _pretty(coords)
                add_named(label, STOP, coords)
            for ll in vias:
                n_via = sum(1 for q in points if q.kind == VIA) + 1
                points.append(Point(f"Via {n_via}", ll[0], ll[1], VIA))
    # 2. /maps/dir/?api=1&origin=..&destination=..&waypoints=a|b  and  ?saddr=&daddr=
    if not points and ("destination" in qs or "daddr" in qs):
        seq = []
        if "origin" in qs:
            seq.append(qs["origin"][0])
        if "saddr" in qs:
            seq.append(qs["saddr"][0])
        if "waypoints" in qs:
            seq += qs["waypoints"][0].split("|")
        if "destination" in qs:
            seq.append(qs["destination"][0])
        if "daddr" in qs:
            seq += re.split(r"\s+to:", qs["daddr"][0])
        for s in seq:
            add_named(s.strip())
    # 3. /maps/place/NAME/@.../data=...!3d<lat>!4d<lon>
    if not points and "place" in segs:
        name = _clean_label(segs[segs.index("place") + 1]) if len(segs) > segs.index("place") + 1 else ""
        m = re.search(r"!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)", urllib.parse.unquote(data))
        coords = (float(m.group(1)), float(m.group(2))) if m else parse_coords(name)
        if not coords:
            coords = _at_center(segs)
            if coords:
                warnings.append(f"'{name}': no exact pin in link, used the map centre.")
        if coords:
            points.append(Point(name if name and not parse_coords(name) else _pretty(coords), *coords, STOP))
        elif name:
            add_named(name)
    # 4. ?q=, ?query=, ?ll=, /maps/search/<text>
    if not points:
        for key in ("q", "query", "ll", "center"):
            if key in qs:
                add_named(qs[key][0].strip())
                break
    if not points and ("cid" in qs or "ftid" in qs):
        points.append(Point("Google place", math.nan, math.nan, STOP))
        unresolved.append("Google place")
    if not points and "search" in segs:
        i = segs.index("search")
        if len(segs) > i + 1 and not segs[i + 1].startswith("@"):
            add_named(_clean_label(segs[i + 1]))
    # 5. bare map view /maps/@lat,lon,zoom
    if not points:
        c = _at_center(segs)
        if c:
            points.append(Point(_pretty(c), *c, STOP))
            warnings.append("Link only had a map view; used the map centre.")
    return points, unresolved, warnings


def _at_center(segs) -> tuple[float, float] | None:
    for s in segs:
        if s.startswith("@"):
            parts = s[1:].split(",")
            if len(parts) >= 2:
                return parse_coords(parts[0] + "," + parts[1])
    return None


def _pretty(c) -> str:
    return f"{c[0]:.5f}, {c[1]:.5f}"


# ---------------------------------------------------------------- geocoding (OpenStreetMap)

_last_geocode = [0.0]


def geocode(name: str, timeout: float = 10) -> tuple[float, float] | None:
    """Look up a place name with OpenStreetMap Nominatim (max 1 request per second)."""
    wait = 1.1 - (time.time() - _last_geocode[0])
    if wait > 0:
        time.sleep(wait)
    _last_geocode[0] = time.time()
    url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode(
        {"q": name, "format": "json", "limit": 1}
    )
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        res = json.load(r)
    if res:
        return float(res[0]["lat"]), float(res[0]["lon"])
    return None


# ---------------------------------------------------------------- Google place lookup

# "Reject all" consent cookie, so Google serves the map page instead of the consent form.
_GOOGLE_HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
    "Cookie": "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg",
    "Accept-Language": "en",
}


def _google_get(url: str, timeout: float) -> str:
    req = urllib.request.Request(url, headers=_GOOGLE_HEADERS)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def _find_latlon(x):
    """First [null, null, lat, lon] in Google's place JSON."""
    if isinstance(x, list):
        if len(x) == 4 and x[0] is None and x[1] is None and all(isinstance(v, float) for v in x[2:]):
            return x[2], x[3]
        for v in x:
            r = _find_latlon(v)
            if r:
                return r
    return None


def google_place_lookup(url: str, timeout: float = 15) -> tuple[str, float, float] | None:
    """Get (name, lat, lon) for a Google Maps link that names a place but has no coordinates.

    Phone share links look like maps.google.com?q=<name, address>&ftid=<place id>.
    Google's map page for such a link embeds the request it uses to load the place
    details; that response holds the pin position.
    """
    p = urllib.parse.urlparse(url)
    qs = dict(urllib.parse.parse_qsl(p.query))
    keep = {k: v for k, v in qs.items() if k in ("q", "query", "ftid", "cid", "daddr")}
    if not keep:
        return None
    keep["hl"] = "en"
    page = _google_get("https://www.google.com/maps?" + urllib.parse.urlencode(keep), timeout)
    m = re.search(r'href="(/maps/preview/place\?[^"]+)"', page)
    if not m:
        return None
    import html as _html
    body = _google_get("https://www.google.com" + _html.unescape(m.group(1)), timeout)
    data = json.loads(body[body.index("\n") + 1:]) if body.startswith(")]}'") else json.loads(body)
    place = data[6] if isinstance(data, list) and len(data) > 6 and isinstance(data[6], list) else data
    ll = None
    if isinstance(place, list) and len(place) > 9:
        c9 = place[9]
        if isinstance(c9, list) and len(c9) == 4 and isinstance(c9[2], float):
            ll = c9[2], c9[3]
    ll = ll or _find_latlon(place)
    if not ll:
        return None
    name = place[11] if isinstance(place, list) and len(place) > 11 and isinstance(place[11], str) else ""
    return name, ll[0], ll[1]


def geocode_smart(text: str) -> tuple[float, float] | None:
    """OpenStreetMap lookup that retries without a leading business name.

    "Restaurante X, R. dos Carreirinhos 79, 2430-177 Marinha Grande, Portugal" fails as a whole;
    dropping the first part finds the street.
    """
    parts = [x.strip() for x in text.split(",") if x.strip()]
    tries = [", ".join(parts[i:]) for i in range(0, max(1, len(parts) - 1))]
    for q in tries[:3]:
        c = geocode(q)
        if c:
            return c
    return None


# ---------------------------------------------------------------- top-level input parsing

URL_RE = re.compile(r"https?://\S+")


def parse_input(text: str, network: bool = True) -> ParseResult:
    """Parse pasted text: one or more Google Maps links, short links, coordinates or addresses."""
    out = ParseResult()
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    for line in lines:
        urls = URL_RE.findall(line)
        if not urls:
            c = parse_coords(line)
            if c:
                out.points.append(Point(_pretty(c), *c, STOP))
            elif network:
                try:
                    c = geocode_smart(line)
                except Exception as e:  # noqa: BLE001
                    out.warnings.append(f"Could not look up '{line}': {e}")
                    continue
                if c:
                    out.points.append(Point(line, *c, STOP))
                else:
                    out.warnings.append(f"Address not found: '{line}'")
            else:
                out.warnings.append(f"Not understood (offline): '{line}'")
            continue
        for url in urls:
            url = url.rstrip(").,;'\"")
            host = urllib.parse.urlparse(url).hostname or ""
            if host in SHORT_HOSTS or host.endswith(".goo.gl"):
                if not network:
                    out.warnings.append(f"Short link needs internet: {url}")
                    continue
                try:
                    url = resolve_short_url(url)
                except Exception as e:  # noqa: BLE001
                    out.warnings.append(f"Could not open short link {url}: {e}")
                    continue
            if not is_google_maps_url(url):
                out.warnings.append(f"Not a Google Maps link: {url}")
                continue
            pts, unresolved, warns = parse_google_url(url)
            out.warnings += warns
            single = len(pts) == 1
            for pt in pts:
                if math.isnan(pt.lat):
                    c = None
                    if network and single:
                        try:
                            found = google_place_lookup(url)
                        except Exception as e:  # noqa: BLE001
                            found = None
                            out.warnings.append(f"Google lookup failed ({e}); trying OpenStreetMap.")
                        if found:
                            gname, lat, lon = found
                            c = (lat, lon)
                            if gname:
                                pt.name = gname
                    if not c and network and pt.name != "Google place":
                        try:
                            c = geocode_smart(pt.name)
                            if c:
                                out.warnings.append(f"'{pt.name}': position from OpenStreetMap address search, check it.")
                        except Exception as e:  # noqa: BLE001
                            out.warnings.append(f"Could not look up '{pt.name}': {e}")
                    if not c:
                        out.warnings.append(f"Skipped '{pt.name}': no coordinates in the link.")
                        continue
                    pt.lat, pt.lon = c
                out.points.append(pt)
            if not pts:
                out.warnings.append(f"No points found in link: {url[:80]}...")
    return out


# ---------------------------------------------------------------- shaping points (OSRM)

def _haversine(a, b) -> float:
    r = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def add_shaping_points(points: list[Point], total: int, timeout: float = 20) -> list[Point]:
    """Insert extra via points along the driving route so the TomTom follows the same roads.

    The road geometry comes from the public OSRM demo server (OpenStreetMap data).
    Existing points are kept; up to `total` points in all are returned.
    """
    base = [p for p in points if p.kind != SHAPE]
    if len(base) < 2 or total <= len(base):
        return base
    coords = ";".join(f"{p.lon:.6f},{p.lat:.6f}" for p in base)
    url = f"https://router.project-osrm.org/route/v1/driving/{coords}?overview=full&geometries=geojson"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        res = json.load(r)
    if res.get("code") != "Ok":
        raise RuntimeError(res.get("message", "routing failed"))
    line = [(lat, lon) for lon, lat in res["routes"][0]["geometry"]["coordinates"]]
    legs = res["routes"][0]["legs"]
    extra = total - len(base)
    leg_len = [max(l["distance"], 1.0) for l in legs]
    total_len = sum(leg_len)
    # split the polyline into legs by nearest vertex to each waypoint
    wp_idx = [0]
    j = 0
    for p in base[1:]:
        best, best_d = j, float("inf")
        for k in range(j, len(line)):
            d = _haversine(line[k], (p.lat, p.lon))
            if d < best_d:
                best, best_d = k, d
        wp_idx.append(best)
        j = best
    out = [base[0]]
    for li in range(len(base) - 1):
        seg = line[wp_idx[li]: wp_idx[li + 1] + 1]
        n = round(extra * leg_len[li] / total_len) if li < len(base) - 2 else extra - sum(
            round(extra * leg_len[k] / total_len) for k in range(len(base) - 2))
        n = max(0, n)
        if n and len(seg) > 2:
            cum = [0.0]
            for a, b in zip(seg, seg[1:]):
                cum.append(cum[-1] + _haversine(a, b))
            L = cum[-1]
            k = 0
            for s in range(1, n + 1):
                target = L * s / (n + 1)
                while k < len(cum) - 1 and cum[k + 1] < target:
                    k += 1
                lat, lon = seg[min(k + 1, len(seg) - 1)]
                out.append(Point(f"Shape {li + 1}.{s}", lat, lon, SHAPE))
        out.append(base[li + 1])
    return out


# ---------------------------------------------------------------- TomTom files

ITN_MAX = 48  # safe limit for older TomTom itineraries (newer models allow 255)


def _safe_name(name: str) -> str:
    return re.sub(r"[|\r\n]", " ", name).strip()[:60] or "Point"


def build_itn(points: list[Point], first_is_departure: bool = True) -> bytes:
    """TomTom itinerary: lon|lat|name|flag| with coordinates x100000.

    Flags: 4 departure (first line only), 2 destination/stopover, 0 pass-through waypoint.
    """
    lines = []
    for i, p in enumerate(points):
        if i == 0 and first_is_departure:
            flag = 4
        elif p.kind == STOP or i == len(points) - 1:
            flag = 2
        else:
            flag = 0
        lines.append(f"{round(p.lon * 100000)}|{round(p.lat * 100000)}|{_safe_name(p.name)}|{flag}|")
    return ("\r\n".join(lines) + "\r\n").encode("cp1252", errors="replace")


def read_itn(data: bytes) -> list[Point]:
    pts = []
    for line in data.decode("cp1252", errors="replace").splitlines():
        f = line.split("|")
        if len(f) >= 4 and f[0].lstrip("+-").isdigit():
            flag = int(f[3]) if f[3].strip().isdigit() else 2
            pts.append(Point(f[2], int(f[1]) / 100000, int(f[0]) / 100000, STOP if flag in (2, 3, 4) else VIA))
    return pts


def ov2_record(p: Point) -> bytes:
    name = _safe_name(p.name).encode("cp1252", errors="replace") + b"\0"
    return struct.pack("<BIii", 2, 13 + len(name), round(p.lon * 100000), round(p.lat * 100000)) + name


def read_ov2(data: bytes) -> list[Point]:
    pts, i = [], 0
    while i + 5 <= len(data):
        typ = data[i]
        size = struct.unpack_from("<I", data, i + 1)[0]
        if size < 5 or i + size > len(data):
            break
        if typ in (2, 3) and size >= 14:
            lon, lat = struct.unpack_from("<ii", data, i + 5)
            name = data[i + 13: i + size].split(b"\0")[0].decode("cp1252", errors="replace")
            pts.append(Point(name, lat / 100000, lon / 100000, STOP))
        elif typ == 1:  # skipper record: fixed 21 bytes
            size = 21
        i += size
    return pts


def poi_icon_bmp(size: int = 22) -> bytes:
    """A small red pin icon as a 24-bit BMP (TomTom shows it next to the POI category)."""
    row_bytes = (size * 3 + 3) & ~3
    px = bytearray()
    c = (size - 1) / 2
    for y in range(size - 1, -1, -1):  # BMP rows are bottom-up
        row = bytearray()
        for x in range(size):
            d = math.hypot(x - c, y - c)
            if d <= c - 1:
                row += bytes((40, 40, 220)) if d > 3 else bytes((255, 255, 255))  # BGR
            elif d <= c:
                row += bytes((20, 20, 120))
            else:
                row += bytes((255, 0, 255))  # magenta = transparent on TomTom
        px += row + bytes(row_bytes - len(row))
    header = struct.pack("<2sIHHI", b"BM", 54 + len(px), 0, 0, 54)
    info = struct.pack("<IiiHHIIiiII", 40, size, size, 1, 24, 0, len(px), 2835, 2835, 0, 0)
    return header + info + bytes(px)


# ---------------------------------------------------------------- device detection

def read_bif(root: str) -> dict:
    info = {}
    try:
        with open(os.path.join(root, "ttgo.bif"), encoding="utf-8-sig", errors="replace") as f:
            for line in f:
                if "=" in line:
                    k, v = line.split("=", 1)
                    info[k.strip()] = v.strip()
    except OSError:
        pass
    return info


def is_tomtom_root(path: str) -> bool:
    return os.path.isfile(os.path.join(path, "ttgo.bif")) or (
        os.path.isdir(os.path.join(path, "itn")) and os.path.isfile(os.path.join(path, "ttsystem"))
    )


def find_tomtom_roots() -> list[str]:
    cands = []
    if sys.platform.startswith("win"):
        cands = [f"{d}:\\" for d in string.ascii_uppercase]
    elif sys.platform == "darwin":
        cands = [os.path.join("/Volumes", d) for d in _ls("/Volumes")]
    else:
        user = os.environ.get("USER", "")
        for base in (f"/media/{user}", f"/run/media/{user}", "/media", "/mnt"):
            cands += [os.path.join(base, d) for d in _ls(base)]
    return [c for c in cands if _safe_isdir(c) and is_tomtom_root(c)]


def _ls(p):
    try:
        return sorted(os.listdir(p))
    except OSError:
        return []


def _safe_isdir(p):
    try:
        return os.path.isdir(p)
    except OSError:
        return False


def find_map_folder(root: str) -> str | None:
    """The active map folder (where POI .ov2 files go)."""
    info = read_bif(root)
    cur = info.get("CurrentMap")
    if cur and os.path.isdir(os.path.join(root, cur)):
        return os.path.join(root, cur)
    try:
        with open(os.path.join(root, "currentmap.dat"), "rb") as f:
            txt = f.read().decode("latin-1").strip("\0 \r\n")
        name = txt.replace("\\", "/").rstrip("/").split("/")[-1]
        if name and os.path.isdir(os.path.join(root, name)):
            return os.path.join(root, name)
    except OSError:
        pass
    for d in _ls(root):
        full = os.path.join(root, d)
        if _safe_isdir(full) and any(f.lower().endswith(".pna") for f in _ls(full)):
            return full
    return None


def safe_filename(name: str) -> str:
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", name).strip(" .")
    return name[:40] or "route"


def write_itn(root_or_dir: str, name: str, points: list[Point], first_is_departure=True) -> str:
    d = os.path.join(root_or_dir, "itn") if is_tomtom_root(root_or_dir) else root_or_dir
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, safe_filename(name) + ".itn")
    with open(path, "wb") as f:
        f.write(build_itn(points, first_is_departure))
        f.flush()
        os.fsync(f.fileno())
    return path


def write_ov2(map_dir: str, category: str, points: list[Point], append: bool = True) -> tuple[str, int]:
    """Add points to <map_dir>/<category>.ov2. Returns (path, number added).

    Points already in the file (same name, within about 1 m) are skipped,
    so saving the same list twice does not create duplicates.
    """
    path = os.path.join(map_dir, safe_filename(category) + ".ov2")
    existing = b""
    if append and os.path.isfile(path):
        with open(path, "rb") as f:
            existing = f.read()
    have = {(q.name, round(q.lat, 5), round(q.lon, 5)) for q in read_ov2(existing)}
    new = []
    for p in points:
        key = (_safe_name(p.name), round(p.lat, 5), round(p.lon, 5))
        if key not in have:
            have.add(key)
            new.append(p)
    if new or not existing:
        with open(path, "wb") as f:
            f.write(existing + b"".join(ov2_record(p) for p in new))
            f.flush()
            os.fsync(f.fileno())
    bmp = os.path.join(map_dir, safe_filename(category) + ".bmp")
    if not os.path.exists(bmp):
        with open(bmp, "wb") as f:
            f.write(poi_icon_bmp())
    return path, len(new)
