package services

// Fuel prices (mockserver.py /fuelprices + fuel.fpu0): per-type price lists in the station order of the <name>.ov2 on
// the card. Every <name>.prices.json found is served, picked by the set id the device sends (p3), so one server
// covers several maps/sets.

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type fuelSet struct {
	name   any // the "name" field as JSON decoded it
	types  map[string]any
	hasTyp bool
}

type fuelFile struct {
	mtime time.Time
	size  int64
	setid string
	set   *fuelSet
	ok    bool
}

type fuelStore struct {
	glob  string
	mu    sync.Mutex
	files map[string]fuelFile // parsed files, reused while mtime and size stay the same
}

// pyStrJSON is Python's str() of a decoded JSON value.
func pyStrJSON(v any) string {
	switch x := v.(type) {
	case nil:
		return "None"
	case bool:
		if x {
			return "True"
		}
		return "False"
	case string:
		return x
	case json.Number:
		if n, err := x.Int64(); err == nil && !strings.ContainsAny(string(x), ".eE") {
			return strconv.FormatInt(n, 10)
		}
		if !strings.ContainsAny(string(x), ".eE") {
			return string(x) // big int
		}
		f, _ := x.Float64()
		return pyFloatRepr(f)
	}
	b, _ := json.Marshal(v)
	return string(b)
}

// sets returns {set id: data}; the newest file wins when two share a set id.
func (fs *fuelStore) sets() map[string]*fuelSet {
	out := map[string]*fuelSet{}
	if fs.glob == "" {
		return out
	}
	pat := fs.glob
	if st, err := os.Stat(pat); err == nil && st.IsDir() {
		pat = filepath.Join(pat, "*.prices.json")
	}
	names, _ := filepath.Glob(pat)
	type ent struct {
		path string
		f    fuelFile
	}
	var ents []ent
	fs.mu.Lock()
	defer fs.mu.Unlock()
	if fs.files == nil {
		fs.files = map[string]fuelFile{}
	}
	for _, p := range names {
		if strings.HasPrefix(filepath.Base(p), ".") { // Python's glob skips hidden files
			continue
		}
		st, err := os.Stat(p)
		if err != nil {
			continue
		}
		f, ok := fs.files[p]
		if !ok || !f.mtime.Equal(st.ModTime()) || f.size != st.Size() {
			f = fuelFile{mtime: st.ModTime(), size: st.Size()}
			f.setid, f.set, f.ok = loadFuel(p)
			fs.files[p] = f
		}
		ents = append(ents, ent{p, f})
	}
	sort.SliceStable(ents, func(a, b int) bool { return ents[a].f.mtime.Before(ents[b].f.mtime) })
	for _, e := range ents {
		if e.f.ok {
			out[e.f.setid] = e.f.set
		}
	}
	return out
}

func loadFuel(path string) (string, *fuelSet, bool) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", nil, false
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	var d map[string]any
	if dec.Decode(&d) != nil {
		return "", nil, false
	}
	setid := any(json.Number("1"))
	if v, ok := d["setid"]; ok {
		setid = v
	}
	s := &fuelSet{name: d["name"]}
	s.types, s.hasTyp = d["types"].(map[string]any)
	return pyStrJSON(setid), s, true
}

var errFuel = errors.New("bad price list")

// fpu0 is the FPU0 update blob (docs/fuelprices_schema.md 3.4): 0x14-byte header (type at 0x0c), then per station
// index 0xfc + u16 absolute price, or 0x00 = no price.
func fpu0(fueltype int64, prices []any) ([]byte, error) {
	out := make([]byte, 0x14, 0x14+3*len(prices))
	binary.LittleEndian.PutUint32(out, 0x46505530)
	out[0x0c] = byte(fueltype)
	if fueltype < 0 || fueltype > 255 {
		return nil, errFuel
	}
	for _, p := range prices {
		if !truthyNum(p) {
			out = append(out, 0)
			continue
		}
		n, ok := p.(json.Number)
		if !ok {
			return nil, errFuel
		}
		v, err := n.Int64()
		if err != nil || v < 0 {
			return nil, errFuel
		}
		out = append(out, 0xfc, 0, 0)
		binary.LittleEndian.PutUint16(out[len(out)-2:], uint16(min(0xffff, v)))
	}
	return out, nil
}

// truthyNum is Python truthiness of a JSON value decoded with UseNumber.
func truthyNum(v any) bool {
	if n, ok := v.(json.Number); ok {
		f, err := n.Float64()
		return err != nil || f != 0
	}
	return truthy(v)
}
