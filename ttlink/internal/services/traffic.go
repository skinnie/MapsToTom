package services

// Incident model + Hercules traffic reply builder (traffic.py; schema: docs/traffic_schema.md).
// Coordinates on the wire are integers in 1e-5 degree (x = longitude, y = latitude).

import (
	"encoding/json"
	"fmt"
	"os"
)

const scale = 100000

// BBox is (x1, y1, x2, y2) in wire units (1e-5 degree, x = longitude), any corner order.
type BBox [4]int64

// Source returns the incidents in a bbox; bb == nil means "no area" (all, or none for area-bound sources).
// A Source must be safe for concurrent use.
type Source func(bb *BBox) []Incident

// Incident is one traffic event.
type Incident struct {
	ID        int64   // low 24 bits are used by the device
	Lat, Lon  float64 // degrees
	Category  int     // 1..10, maps straight to an icon (docs/traffic_schema.md section 8)
	Direction bool    // true -> +1, false -> -1
	Speed     *int    // tag 16: <0 percent of normal, >=0 speed in 1/100 km/h; 0 = closed; nil = none
	Text      string  // < 60 bytes
	Removed   bool    // send {10: id, 22: true}

	// Set by the TomTom parser and read by Portland's selection. Ranked false means: group = ID, tier 2, length 0.
	Ranked   bool
	Group    int64
	Tier     int
	Delay    float64
	Length10 int64 // this row's share of the incident length, 10 m units
}

func (i *Incident) group() int64 {
	if i.Ranked {
		return i.Group
	}
	return i.ID
}

func (i *Incident) tier() int {
	if i.Ranked {
		return i.Tier
	}
	return 2
}

func (i *Incident) point() string {
	return fmt.Sprintf(">%dx%d", pyRound(i.Lon*scale), pyRound(i.Lat*scale))
}

func clampCategory(c int) int64 { return int64(max(1, min(10, c))) }

func (i *Incident) toStruct() *Struct {
	if i.Removed {
		return NewStruct(10, i.ID&0xFFFFFF, 22, true)
	}
	s := NewStruct(10, i.ID&0xFFFFFF, 12, i.Direction, 13, i.point(), 14, clampCategory(i.Category))
	if i.Speed != nil {
		s.Items = append(s.Items, Item{16, int64(*i.Speed)})
	}
	// no tag 23 text: it makes the device treat the incident as a TTLR blob (task T32) and drop it
	return s
}

// InBBox reports whether the incident's wire position lies in bb (nil = everywhere).
func InBBox(i *Incident, bb *BBox) bool {
	if bb == nil {
		return true
	}
	x, y := pyRound(i.Lon*scale), pyRound(i.Lat*scale)
	return min(bb[0], bb[2]) <= x && x <= max(bb[0], bb[2]) && min(bb[1], bb[3]) <= y && y <= max(bb[1], bb[3])
}

func trafficPayload(slot any, incs []Incident) *Struct {
	l := make([]any, len(incs))
	for k := range incs {
		l[k] = incs[k].toStruct()
	}
	return NewStruct(5, slot, 0x10, NewStruct(2, l))
}

// fileIncident is one entry of an incidents.json file (traffic.Incident fields).
type fileIncident struct {
	ID        int64    `json:"id"`
	Lat       float64  `json:"lat"`
	Lon       float64  `json:"lon"`
	Category  *float64 `json:"category"`
	Direction *bool    `json:"direction"`
	Speed     *float64 `json:"speed"`
	Text      string   `json:"text"`
	Removed   bool     `json:"removed"`
}

// FileSource reads a JSON list of incident dicts on every fetch, so the file can be edited live.
// A bad file is logged and gives no incidents (traffic.py raises instead).
func FileSource(path string, log func(...any)) Source {
	return func(bb *BBox) []Incident {
		data, err := os.ReadFile(path)
		var list []fileIncident
		if err == nil {
			err = json.Unmarshal(data, &list)
		}
		if err != nil {
			if log != nil {
				log("incidents file:", err)
			}
			return nil
		}
		var out []Incident
		for _, d := range list {
			i := Incident{ID: d.ID, Lat: d.Lat, Lon: d.Lon, Category: 3, Direction: true, Text: d.Text, Removed: d.Removed}
			if d.Category != nil {
				i.Category = int(*d.Category)
			}
			if d.Direction != nil {
				i.Direction = *d.Direction
			}
			if d.Speed != nil {
				s := int(*d.Speed)
				i.Speed = &s
			}
			if InBBox(&i, bb) {
				out = append(out, i)
			}
		}
		return out
	}
}

// Combine concatenates sources.
func Combine(srcs ...Source) Source {
	return func(bb *BBox) []Incident {
		var out []Incident
		for _, s := range srcs {
			out = append(out, s(bb)...)
		}
		return out
	}
}

func intPtr(v int) *int { return &v }
