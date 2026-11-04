package services

// Live traffic sources (sources.py).
//   TomTom:   TomTom Traffic Incident Details v5 (any country, free developer key).
//   FrDATEX2: France, Bison Futé national-network events (DATEX II, open, no key).
// Category numbers follow the device icon table (docs/traffic_schema.md section 8).

import (
	"bytes"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"hash/crc32"
	"io"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
)

// closedSpeed is the tag 16 speed for closures: 0 (closed) and negative percents are NOT counted in the device's
// route time (emu2 C1b vs C2), a small positive speed (1/100 km/h) is.
const closedSpeed = 300

// ttCat maps TomTom iconCategory -> device category (icon table: docs/traffic_schema.md section 8).
var ttCat = map[int]int{0: 3, 1: 4, 2: 2, 3: 3, 4: 1, 5: 5, 6: 6, 7: 7, 8: 8, 9: 9, 10: 10, 11: 1, 14: 3}

// Route matching on the device (Portland LD, emu2 NOTES round 23): a '>XxY' row is snapped to the nearest road and
// only delays a planned route when that road is a route segment; the device computes the delay of a row over the
// WHOLE segment it snaps to from col5 (speed, 1/100 km/h, must be > 0). col7 (length, 10 m units, = Hercules tag 17)
// did not change the summary for point rows (P6b); it is sent as the row's share of the incident length.
// A long jam is sent as several rows along its geometry so that any part of a route can match it.
const splitM = 500 // one row per started 500 m of incident geometry

// motorway class (PT/ES): A1, IC19, IP7, E01, AP-7, ...; major adds national roads EN125, N8, N-II.
// Longest first, as the Python regex alternation is built.
var (
	motorwayPrefix = []string{"IC", "IP", "AP", "AG", "AU", "A", "E"}
	majorPrefix    = []string{"IC", "IP", "AP", "AG", "AU", "EN", "A", "E", "N"}
)

// refKmh is the assumed free-flow speed (major road / other) to turn a TomTom delay into an average speed.
func refKmh(major bool) float64 {
	if major {
		return 90
	}
	return 45
}

func crc24(s string) int64 { return int64(crc32.ChecksumIEEE([]byte(s)) & 0xFFFFFF) }

func isWord(r rune) bool { return r == '_' || unicode.IsLetter(r) || unicode.IsNumber(r) }

// isMajor: a road number of that class, the prefix directly followed by a number (A1, IC19, AP7, EN125, N8) or,
// for N, a Roman numeral (N-II). Municipal roads (EM588, CM1185) only share the first letter and are not major.
func isMajor(roads []string, prefix []string) bool {
	for _, r := range roads {
		s := strings.NewReplacer("-", "", " ", "").Replace(strings.ToUpper(r))
		for _, p := range prefix {
			if !strings.HasPrefix(s, p) {
				continue
			}
			rest := []rune(s[len(p):])
			if len(rest) > 0 && unicode.IsDigit(rest[0]) {
				return true
			}
			if strings.HasSuffix(p, "N") { // (?<=N)[IVX]+\b
				k := 0
				for k < len(rest) && (rest[k] == 'I' || rest[k] == 'V' || rest[k] == 'X') {
					k++
				}
				if k > 0 && (k == len(rest) || !isWord(rest[k])) {
					return true
				}
			}
		}
	}
	return false
}

// incidentLine is _line(): vertices [lon, lat] of the geometry (the longest part of a MultiLineString).
func incidentLine(typ string, raw json.RawMessage) ([][]float64, error) {
	var top []json.RawMessage
	if err := json.Unmarshal(raw, &top); err != nil {
		return nil, err
	}
	if len(top) == 0 {
		return nil, fmt.Errorf("empty coordinates")
	}
	first := bytes.TrimSpace(top[0])
	if typ == "Point" || len(first) == 0 || first[0] != '[' {
		var pt []float64
		if err := json.Unmarshal(raw, &pt); err != nil {
			return nil, err
		}
		return [][]float64{pt}, nil
	}
	var probe []json.RawMessage
	if err := json.Unmarshal(first, &probe); err != nil {
		return nil, err
	}
	if len(probe) > 0 && bytes.HasPrefix(bytes.TrimSpace(probe[0]), []byte("[")) {
		var multi [][][]float64
		if err := json.Unmarshal(raw, &multi); err != nil {
			return nil, err
		}
		best := multi[0]
		for _, l := range multi[1:] {
			if len(l) > len(best) {
				best = l
			}
		}
		return best, nil
	}
	var line [][]float64
	err := json.Unmarshal(raw, &line)
	return line, err
}

// pointsAlong returns (length_m, vertices): the vertex nearest the centre of every `step` metres share.
func pointsAlong(c [][]float64, step float64) (float64, [][]float64) {
	if len(c) < 2 {
		return 0, [][]float64{c[0]}
	}
	k := float64(crCos(c[0][1]*degToRad) * 111195)
	cum := []float64{0}
	for j := 1; j < len(c); j++ {
		a, b := c[j-1], c[j]
		cum = append(cum, cum[j-1]+crHypot(float64((b[0]-a[0])*k), float64((b[1]-a[1])*111195)))
	}
	total := cum[len(cum)-1]
	n := max(1, int(math.Ceil(total/step)))
	var idx []int
	for j := 0; j < n; j++ {
		m := float64(total*(float64(j)+0.5)) / float64(n)
		best := 0
		for i := 1; i < len(c); i++ {
			if math.Abs(cum[i]-m) < math.Abs(cum[best]-m) {
				best = i
			}
		}
		dup := false
		for _, x := range idx {
			dup = dup || x == best
		}
		if !dup {
			idx = append(idx, best)
		}
	}
	out := make([][]float64, len(idx))
	for j, i := range idx {
		out[j] = c[i]
	}
	return total, out
}

type ttDoc struct {
	Incidents []struct {
		Properties struct {
			ID               string   `json:"id"`
			IconCategory     *float64 `json:"iconCategory"`
			MagnitudeOfDelay *float64 `json:"magnitudeOfDelay"`
			Delay            *float64 `json:"delay"`
			From             *string  `json:"from"`
			Length           *float64 `json:"length"`
			RoadNumbers      []string `json:"roadNumbers"`
			Events           []struct {
				Description *string `json:"description"`
			} `json:"events"`
		} `json:"properties"`
		Geometry struct {
			Type        string          `json:"type"`
			Coordinates json.RawMessage `json:"coordinates"`
		} `json:"geometry"`
	} `json:"incidents"`
}

// ParseTomTom turns an incidentDetails reply into incidents (one row per started 500 m of geometry), with the
// tier used by Portland's selection: 0 = closures/jams on motorway-class roads, 1 = other main roads, jams or
// anything with a delay, 2 = the rest.
func ParseTomTom(data []byte) ([]Incident, error) {
	var doc ttDoc
	if err := json.Unmarshal(data, &doc); err != nil {
		return nil, err
	}
	var out []Incident
	for _, f := range doc.Incidents {
		p := f.Properties
		ev := ""
		if len(p.Events) > 0 && p.Events[0].Description != nil {
			ev = *p.Events[0].Description
		}
		if p.From != nil && *p.From != "" {
			if ev != "" {
				ev += " " + *p.From
			} else {
				ev = *p.From
			}
		}
		delay := 0.0
		if p.Delay != nil {
			delay = *p.Delay
		}
		icon := 0.0
		if p.IconCategory != nil {
			icon = *p.IconCategory
		}
		magIs := func(v float64) bool { return p.MagnitudeOfDelay != nil && *p.MagnitudeOfDelay == v }
		major := isMajor(p.RoadNumbers, majorPrefix)
		line, err := incidentLine(f.Geometry.Type, f.Geometry.Coordinates)
		if err != nil {
			return nil, err
		}
		length, pts := pointsAlong(line, splitM)
		if p.Length != nil && *p.Length != 0 {
			length = *p.Length
		}
		closed := icon == 8 || magIs(4)
		if closed && !major {
			// a closure on the route makes the device ask 'Estrada fechada ... evitar?' and adds no delay (emu2 round
			// 23); TomTom lists many small city-street closures that would pop that prompt on most urban routes
			continue
		}
		var speed *int
		switch {
		case closed:
			speed = intPtr(closedSpeed)
		case delay != 0 && length > 0: // average speed that gives TomTom's delay over the length
			ref := refKmh(major) / 3.6
			v := float64(length/float64(length/ref+delay)) * 3.6
			m := refKmh(major) - 5
			if v < m {
				m = v
			}
			x := 3.0
			if m > x {
				x = m
			}
			speed = intPtr(int(x * 100))
		case magIs(3):
			speed = intPtr(500)
		case magIs(2):
			speed = intPtr(1500)
		case magIs(1):
			speed = intPtr(3000)
		case delay != 0:
			speed = intPtr(1500)
		}
		mag := 0.0
		if p.MagnitudeOfDelay != nil {
			mag = *p.MagnitudeOfDelay
		}
		motorway := isMajor(p.RoadNumbers, motorwayPrefix)
		tier := 2
		if motorway && (closed || icon == 6 || mag >= 2) {
			tier = 0
		} else if major || icon == 6 || delay != 0 {
			tier = 1
		}
		base := crc24(p.ID)
		var share int64
		if length != 0 {
			share = pyRound(length / float64(len(pts)) / 10)
		}
		cat := 3
		if icon == math.Trunc(icon) {
			if c, ok := ttCat[int(icon)]; ok {
				cat = c
			}
		}
		dl := delay
		if closed {
			dl = 1e6
		}
		for k, pt := range pts {
			id := base
			if k > 0 {
				id = crc24(fmt.Sprintf("%s#%d", p.ID, k))
			}
			out = append(out, Incident{ID: id, Lat: pt[1], Lon: pt[0], Category: cat, Direction: true, Speed: speed,
				Text: truncRunes(ev, 59), Ranked: true, Group: base, Tier: tier, Delay: dl, Length10: share})
		}
	}
	return out, nil
}

const ttFields = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,delay,from," +
	"length,roadNumbers,events{description}}}}"

type tileKey struct{ x, y int }

type tileVal struct {
	t   time.Time
	val []Incident
}

// TomTom returns a source for any bbox. The API allows 10,000 km2 per request, so the bbox is split into grid-aligned
// 1 x 1 degree tiles (about 9,000-9,800 km2 at Iberian/French latitudes), each cached for 10 minutes. Grid alignment
// lets consecutive device fetches (the area moves with the car) reuse tiles; at most 30 tiles per fetch (nearest the
// bbox centre). The device must get its answer quickly: stale tiles are served while they refresh in the background,
// and missing tiles are waited for at most 5 s in total (the rest arrives with the next device refresh).
func TomTom(key string, client *http.Client, log func(...any)) Source {
	const ttl, maxTiles, wait = 600 * time.Second, 30, 5 * time.Second
	var mu sync.Mutex
	cache := map[tileKey]tileVal{}
	busy := map[tileKey]chan struct{}{}
	sem := make(chan struct{}, 8)

	fetch := func(k tileKey, done chan struct{}) {
		sem <- struct{}{}
		defer func() {
			<-sem
			mu.Lock()
			delete(busy, k)
			mu.Unlock()
			close(done)
		}()
		q := url.Values{"key": {key}, "bbox": {fmt.Sprintf("%f,%f,%f,%f", float64(k.x), float64(k.y), float64(k.x+1), float64(k.y+1))},
			"fields": {ttFields}, "language": {"en-GB"}, "timeFilter": {"present"}}
		val, err := func() ([]Incident, error) {
			resp, err := client.Get("https://api.tomtom.com/traffic/services/5/incidentDetails?" + q.Encode())
			if err != nil {
				return nil, err
			}
			defer resp.Body.Close()
			body, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
			if err != nil {
				return nil, err
			}
			if resp.StatusCode != 200 {
				return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
			}
			return ParseTomTom(body)
		}()
		if err != nil {
			log(fmt.Sprintf("tomtom tile (%d, %d) failed: %v", k.x, k.y, redactKey(err, key)))
			return
		}
		mu.Lock()
		cache[k] = tileVal{time.Now(), val}
		mu.Unlock()
	}

	// ensure starts a fetch for tile k if it is missing or stale and not already running; it returns the fetch's
	// done channel, or nil when the cached tile is fresh
	ensure := func(k tileKey) chan struct{} {
		mu.Lock()
		defer mu.Unlock()
		if hit, ok := cache[k]; ok && time.Since(hit.t) <= ttl {
			return nil
		}
		if _, ok := busy[k]; !ok {
			done := make(chan struct{})
			busy[k] = done
			go fetch(k, done)
		}
		return busy[k]
	}

	return func(bb *BBox) []Incident {
		if bb == nil {
			return nil
		}
		x1, y1, x2, y2 := float64(bb[0])/scale, float64(bb[1])/scale, float64(bb[2])/scale, float64(bb[3])/scale
		w, s, e, n := math.Min(x1, x2), math.Min(y1, y2), math.Max(x1, x2), math.Max(y1, y2)
		var tiles []tileKey
		for tx := int(math.Floor(w)); tx < int(math.Ceil(e)); tx++ {
			for ty := int(math.Floor(s)); ty < int(math.Ceil(n)); ty++ {
				tiles = append(tiles, tileKey{tx, ty})
			}
		}
		cx, cy := (w+e)/2, (s+n)/2
		d := func(t tileKey) float64 {
			dx, dy := float64(t.x)+.5-cx, float64(t.y)+.5-cy
			return float64(dx*dx) + float64(dy*dy)
		}
		sort.SliceStable(tiles, func(a, b int) bool { return d(tiles[a]) < d(tiles[b]) })
		if len(tiles) > maxTiles {
			tiles = tiles[:maxTiles]
		}
		var missing []chan struct{}
		for _, k := range tiles {
			if f := ensure(k); f != nil {
				mu.Lock()
				_, have := cache[k]
				mu.Unlock()
				if !have {
					missing = append(missing, f)
				}
			}
		}
		if len(missing) > 0 {
			timer := time.NewTimer(wait)
		wait:
			for _, f := range missing {
				select {
				case <-f:
				case <-timer.C:
					break wait
				}
			}
			timer.Stop()
		}
		var out []Incident
		seen := map[int64]bool{}
		mu.Lock()
		var results [][]Incident
		for _, k := range tiles {
			if v, ok := cache[k]; ok {
				results = append(results, v.val)
			}
		}
		mu.Unlock()
		for _, res := range results {
			for _, i := range res {
				if !seen[i.ID] && w <= i.Lon && i.Lon <= e && s <= i.Lat && i.Lat <= n {
					seen[i.ID] = true
					out = append(out, i)
				}
			}
		}
		return out
	}
}

// redactKey keeps the developer key out of logged URL errors.
func redactKey(err error, key string) string {
	if key == "" {
		return err.Error()
	}
	return strings.ReplaceAll(err.Error(), key, "***")
}

// cached serves fetch() for ttl, keeps the last good data on a failure and retries 30 s later.
func cached(fetch func() ([]Incident, error), ttl time.Duration, log func(...any)) func() []Incident {
	var mu sync.Mutex
	var t time.Time
	var val []Incident
	return func() []Incident {
		mu.Lock()
		defer mu.Unlock()
		if time.Since(t) > ttl {
			v, err := fetch()
			if err != nil {
				log("source fetch failed:", err)
				t = time.Now().Add(-ttl + 30*time.Second)
			} else {
				val, t = v, time.Now()
			}
		}
		return val
	}
}

// FrURL is Bison Futé's DATEX II feed of the French national road network.
const FrURL = "http://tipi.bison-fute.gouv.fr/bison-fute-ouvert/publicationsDIR/Evenementiel-DIR/grt/RRN/content.xml"

type frCat struct {
	cat   int
	label string
}

// DATEX II situationRecord xsi:type -> (category, text)
var frCategory = map[string]frCat{
	"AbnormalTraffic":                   {6, "Bouchon"},
	"Accident":                          {4, "Accident"},
	"VehicleObstruction":                {3, "Vehicule"},
	"ConstructionWorks":                 {9, "Travaux"},
	"MaintenanceWorks":                  {9, "Travaux"},
	"RoadOrCarriagewayOrLaneManagement": {7, "Voie fermee"},
	"ReroutingManagement":               {3, "Deviation"},
	"SpeedManagement":                   {3, "Vitesse"},
	"WeatherRelatedRoadConditions":      {5, "Meteo"},
	"EnvironmentalObstruction":          {3, "Obstacle"},
	"AnimalPresenceObstruction":         {3, "Animal"},
	"GeneralObstruction":                {3, "Obstacle"},
	"InfrastructureDamageObstruction":   {3, "Route abimee"},
}

const xsiNS = "http://www.w3.org/2001/XMLSchema-instance"

type xnode struct {
	XMLName  xml.Name
	Attrs    []xml.Attr `xml:",any,attr"`
	Children []xnode    `xml:",any"`
	Text     string     `xml:",chardata"`
}

func (n *xnode) attr(space, local string) string {
	for _, a := range n.Attrs {
		if a.Name.Space == space && a.Name.Local == local {
			return a.Value
		}
	}
	return ""
}

// iter walks n and its descendants in document order (ElementTree's iter()).
func (n *xnode) iter(f func(*xnode) bool) bool {
	if !f(n) {
		return false
	}
	for k := range n.Children {
		if !n.Children[k].iter(f) {
			return false
		}
	}
	return true
}

func localName(s string) string {
	if i := strings.LastIndex(s, "}"); i >= 0 {
		s = s[i+1:]
	}
	if i := strings.LastIndex(s, ":"); i >= 0 {
		s = s[i+1:]
	}
	return s
}

// ParseDATEX2 reads the situation records of a DATEX II publication.
func ParseDATEX2(data []byte) ([]Incident, error) {
	var root xnode
	if err := xml.Unmarshal(data, &root); err != nil {
		return nil, err
	}
	var out []Incident
	var perr error
	root.iter(func(rec *xnode) bool {
		if rec.XMLName.Local != "situationRecord" {
			return true
		}
		fc, ok := frCategory[localName(rec.attr(xsiNS, "type"))]
		if !ok {
			return true
		}
		var lat, lon float64
		found := false
		rec.iter(func(el *xnode) bool {
			if el.XMLName.Local != "pointCoordinates" {
				return true
			}
			kids := map[string]string{}
			for _, k := range el.Children {
				kids[k.XMLName.Local] = k.Text
			}
			var e1, e2 error
			lat, e1 = strconv.ParseFloat(strings.TrimSpace(kids["latitude"]), 64)
			lon, e2 = strconv.ParseFloat(strings.TrimSpace(kids["longitude"]), 64)
			if e1 != nil || e2 != nil {
				perr = fmt.Errorf("bad pointCoordinates")
			}
			found = true
			return false
		})
		if perr != nil {
			return false
		}
		if !found {
			return true
		}
		comment := ""
		rec.iter(func(g *xnode) bool {
			if g.XMLName.Local != "generalPublicComment" {
				return true
			}
			var vals, ctype []string
			g.iter(func(v *xnode) bool {
				if v.XMLName.Local == "value" && v.Text != "" {
					vals = append(vals, v.Text)
				}
				return true
			})
			for _, c := range g.Children {
				if c.XMLName.Local == "commentType" {
					ctype = append(ctype, c.Text)
				}
			}
			if len(vals) > 0 && len(ctype) == 1 && ctype[0] == "description" {
				comment = vals[0]
				return false
			}
			return true
		})
		text := fc.label
		if comment != "" {
			text += " " + comment
		}
		i := Incident{ID: crc24(rec.attr("", "id")), Lat: lat, Lon: lon, Category: fc.cat, Direction: true,
			Text: truncRunes(text, 59)}
		if fc.cat == 6 {
			i.Speed = intPtr(1500)
		}
		out = append(out, i)
		return true
	})
	return out, perr
}

// FrDATEX2 is the French national-network source, refreshed every 2 minutes.
func FrDATEX2(client *http.Client, log func(...any)) Source {
	get := cached(func() ([]Incident, error) {
		resp, err := client.Get(FrURL)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
		if err != nil {
			return nil, err
		}
		return ParseDATEX2(body)
	}, 120*time.Second, log)
	return func(bb *BBox) []Incident {
		var out []Incident
		for _, i := range get() {
			if InBBox(&i, bb) {
				out = append(out, i)
			}
		}
		return out
	}
}
