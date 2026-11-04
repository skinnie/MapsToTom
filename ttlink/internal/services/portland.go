package services

// Portland (HD/LD traffic) service (portland.py): XMsg replies for
// GET /portland?...&a=nt&m=<gtmo|gstuo|gtuo|gtd2>&r=2&t=<types>&p1..pN&z=&x=  (docs/plus_traffic.md section 4b).
//
// Decoded from the ttn code (FUN_00565b20 request, FUN_00565948 reply handler, FUN_00565510 incident table,
// FUN_00565820 details table). Verified in the emulator (emu2 NOTES round 23, Portland LD provider = what the v4
// builds over a BT phone):
//   - a=nt m=gtuo t=IILBS (A, B, area, bool, region): the 3-argument reply (int A, int B, table of 8-column rows) is
//     accepted, the incidents are drawn and count in the route summary when the '>XxY' point snaps to a route road.
//     col2 '+' or '0' count, '-' did not; col5 must be > 0; category 8 (closure) on the route makes the device ask
//     'Estrada fechada ... evitar?' and adds no delay; each row delays the whole road segment it snaps to, col7
//     (length, 10 m, = Hercules tag 17) did not change that for point rows. 150 rows are accepted.
//     The real v4 (patch_pnd --refresh 120) asks again every ~4 min with p1 = the previous reply's counter.
//   - a=sv m=son t=IL: any OK reply (err 0, no arguments); the content is not read (FUN_0013d2e8).
//   - a=pr m=gpid t=S (p1 = phone APN): 0204 keeps the push channel off.

import (
	"bytes"
	"errors"
	"fmt"
	"hash/crc32"
	"math"
	"sort"
	"strings"
	"sync"
)

var (
	// defaultBBox is the rect the device itself puts in the Hercules fetch (lon/lat in 1e-5 degree)
	defaultBBox = BBox{-1072332, 3726000, -656052, 4050000}
	ntOps       = map[string]string{"gtmo": "BS", "gstuo": "IIBS", "gtuo": "IIBS", "gtd2": "IISS"}
)

const (
	portlandMax = 150  // emu2 round 23: a 150-row table is accepted; more not tested
	noSpeed     = 3000 // column 5 is mandatory: 30 km/h when the incident has no speed
)

// incidentRow: 8 columns (FUN_00565510): kind flag, id, direction, location, category, signed speed, two ints.
func incidentRow(i *Incident) [][]byte {
	speed := noSpeed
	if i.Speed != nil {
		speed = *i.Speed
	}
	dir := "-"
	if i.Direction {
		dir = "+"
	}
	// col6 = Hercules tag 15 (extent), col7 = tag 17: length of this row's part of a split incident in 10 m units
	return [][]byte{[]byte("1"), []byte(fmt.Sprint(i.ID & 0xFFFFFF)), []byte(dir), []byte(i.point()),
		[]byte(fmt.Sprint(clampCategory(i.Category))), []byte(fmt.Sprint(speed)), []byte("0"), []byte(fmt.Sprint(i.Length10))}
}

// detailRow: 6 columns (FUN_00565820): kind flag, id, four strings (roles not named in the code: text, then empty).
func detailRow(i *Incident) [][]byte {
	var txt []byte
	for _, r := range i.Text { // encode("ascii", "replace")
		switch {
		case r == '\t' || r == '\n':
			txt = append(txt, ' ')
		case r < 0x80:
			txt = append(txt, byte(r))
		default:
			txt = append(txt, '?')
		}
	}
	if len(txt) > 59 {
		txt = txt[:59]
	}
	return [][]byte{[]byte("1"), []byte(fmt.Sprint(i.ID & 0xFFFFFF)), txt, {}, {}, {}}
}

type portlandReq struct {
	a, m, t    string
	hasA, hasM bool
	p          []any // int64, bool, string, or BBox for an 'L' area
	q          []qpair
}

var errPy = errors.New("request the Python server would fail on")

// parsePortland: 'u=..&a=nt&m=gtuo&r=2&t=IIBS&p1=3&...' -> a, m, t, typed values.
func parsePortland(query string) (*portlandReq, error) {
	q := parseQS(query, true)
	r := &portlandReq{q: q}
	r.a, r.hasA = qget(q, "a")
	r.m, r.hasM = qget(q, "m")
	r.t, _ = qget(q, "t")
	i := 0
	for _, letter := range r.t {
		i++
		raw, _ := qget(q, fmt.Sprintf("p%d", i))
		switch letter {
		case 'L': // area 'x1xy1xx2xy2' (lon/lat 1e-5 degree), seen on the v4 2026-10-30
			parts := strings.Split(raw, "x")
			vals := make([]int64, 0, len(parts))
			for _, s := range parts {
				n, ok := pyInt(s)
				if !ok {
					vals = nil
					break
				}
				vals = append(vals, n)
			}
			switch {
			case vals == nil:
				r.p = append(r.p, raw)
			case len(vals) == 4:
				r.p = append(r.p, BBox{vals[0], vals[1], vals[2], vals[3]})
			default:
				r.p = append(r.p, vals) // a tuple, but not an area
			}
		case 'I':
			if raw == "" {
				r.p = append(r.p, int64(0))
				continue
			}
			n, ok := pyInt(raw)
			if !ok {
				return nil, errPy
			}
			r.p = append(r.p, n)
		case 'B':
			r.p = append(r.p, raw == "1" || raw == "true" || raw == "True")
		default:
			r.p = append(r.p, raw)
		}
	}
	return r, nil
}

func (r *portlandReq) qJSON() jobj {
	o := make(jobj, len(r.q))
	for k, p := range r.q {
		o[k] = jkv{p.k, p.v}
	}
	return o
}

type portland struct {
	source Source
	log    func(...any)
	mu     sync.Mutex
	sent   map[int64]Incident // id (24 bit) -> incident of the last area replies: gtd2 carries no area
}

func newPortland(src Source, log func(...any)) *portland {
	return &portland{source: src, log: log, sent: map[int64]Incident{}}
}

// incidents: at most portlandMax rows, by importance (tier), then nearest to the area centre (the device centres its
// area on the car). All rows of one split incident (same group) are kept together. The old 10x10 spread kept none of
// the 6 incidents on a real route into Lisboa (emu2 round 23).
func (pl *portland) incidents(bb *BBox) []Incident {
	if bb == nil {
		b := defaultBBox
		bb = &b
	}
	var incs []Incident
	for _, i := range pl.source(bb) {
		if !i.Removed {
			incs = append(incs, i)
		}
	}
	cx, cy := float64(bb[0]+bb[2])/2e5, float64(bb[1]+bb[3])/2e5
	var order []int64
	groups := map[int64][]Incident{}
	for _, i := range incs {
		g := i.group()
		if _, ok := groups[g]; !ok {
			order = append(order, g)
		}
		groups[g] = append(groups[g], i)
	}
	type gk struct {
		rows []Incident
		tier int
		d    float64
	}
	keyed := make([]gk, len(order))
	for k, g := range order {
		rows := groups[g]
		d := math.Inf(1)
		for _, r := range rows {
			dx, dy := r.Lon-cx, r.Lat-cy
			if v := float64(dx*dx) + float64(dy*dy); v < d {
				d = v
			}
		}
		keyed[k] = gk{rows, rows[0].tier(), d}
	}
	sort.SliceStable(keyed, func(a, b int) bool {
		if keyed[a].tier != keyed[b].tier {
			return keyed[a].tier < keyed[b].tier
		}
		return keyed[a].d < keyed[b].d
	})
	var out []Incident
	for _, g := range keyed {
		if len(out)+len(g.rows) <= portlandMax {
			out = append(out, g.rows...)
		}
	}
	return out
}

// handle answers one /portland query; an error means the Python server would have failed with no reply.
func (pl *portland) handle(r *portlandReq) ([]byte, error) {
	if r.hasA && r.a == "pr" { // a=pr m=gpid: accepted without retry as "no content"
		return xmsgReply(nil, 204), nil
	}
	if r.hasA && r.a == "sv" { // a=sv m=son t=IL (int, area): answered with a plain OK
		pl.log("portland sv", r.m, r.p)
		return xmsgReply(nil, 0), nil
	}
	if _, ok := ntOps[r.m]; !(r.hasA && r.a == "nt" && r.hasM && ok) {
		pl.log("portland: unexpected request", r.q)
		return xmsgReply(nil, 204), nil
	}
	// the v4 sends t=IILBS (A, B, area, bool, region 'PT'); older reading: IIBS without the area
	var area *BBox
	for _, v := range r.p {
		if b, ok := v.(BBox); ok {
			area = &b
			break
		}
	}
	if r.m == "gtd2" { // details of one incident: p3 = id (decimal string)
		if len(r.p) < 3 {
			return nil, errPy
		}
		var want int64
		switch v := r.p[2].(type) {
		case int64:
			want = v
		case bool:
			if v {
				want = 1
			}
		case string:
			if v != "" {
				n, ok := pyInt(v)
				if !ok {
					return nil, errPy
				}
				want = n
			}
		default:
			return nil, errPy
		}
		want &= 0xFFFFFF
		// rows already sent first: gtd2 has no area, a fresh fetch of the default box costs up to 20 TomTom tiles
		pl.mu.Lock()
		hit, found := pl.sent[want]
		pl.mu.Unlock()
		if !found {
			for _, i := range pl.incidents(area) {
				if i.ID&0xFFFFFF == want {
					hit, found = i, true
					break
				}
			}
		}
		pl.log("portland gtd2 id", want, map[bool]string{true: "found", false: "NOT found"}[found])
		var rows [][][]byte
		if found {
			rows = append(rows, detailRow(&hit))
		}
		return xmsgReply([][]byte{argTable(rows)}, 0), nil
	}
	incs := pl.incidents(area)
	pl.log("portland", r.m, r.p, len(incs))
	pl.mu.Lock()
	for _, i := range incs {
		pl.sent[i.ID&0xFFFFFF] = i
	}
	pl.mu.Unlock()
	ids := make([][]byte, len(incs))
	rows := make([][][]byte, len(incs))
	for k := range incs {
		ids[k] = []byte(fmt.Sprint(incs[k].ID & 0xFFFFFF))
		rows[k] = incidentRow(&incs[k])
	}
	// changes with the incident set
	counter := int64(crc32.ChecksumIEEE(bytes.Join(ids, []byte(",")))%1000000000) + 1
	return xmsgReply([][]byte{argInt(counter), argInt(int64(len(incs))), argTable(rows)}, 0), nil
}
