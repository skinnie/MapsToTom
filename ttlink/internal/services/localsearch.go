package services

// LOCALSEARCH (Hercules slot 4, localsearch.py): Photon (OSM) lookups, reply shape per docs/localsearch_schema.md.

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"
)

const localSearchSlot = 4

// PhotonFetch returns Photon's GeoJSON reply for a query near (lat, lon).
type PhotonFetch func(query string, lat, lon float64, limit int64, lang string) ([]byte, error)

// Photon queries photon.komoot.io.
func Photon(client *http.Client) PhotonFetch {
	return func(query string, lat, lon float64, limit int64, lang string) ([]byte, error) {
		switch lang {
		case "en", "de", "fr", "it":
		default:
			lang = "en"
		}
		q := "q=" + url.QueryEscape(query) + "&lat=" + fmt.Sprintf("%.5f", lat) + "&lon=" + fmt.Sprintf("%.5f", lon) +
			"&limit=" + fmt.Sprint(limit) + "&lang=" + lang
		req, err := http.NewRequest("GET", "https://photon.komoot.io/api/?"+q, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("User-Agent", "go950-live-replacement/0.1")
		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			return nil, fmt.Errorf("photon: HTTP %d", resp.StatusCode)
		}
		return io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	}
}

func lsResult(name, addr string, lat, lon float64) *Struct {
	return NewStruct(1, name, 2, addr, 3, pyRound(lat*1e5), 4, pyRound(lon*1e5), 6, 0, 7, 0, 8, "")
}

func searchReply(results []any, total int) *Struct {
	data := NewStruct(0, int64(total), 1, int64(len(results)), 2, "", 3, "", 4, results)
	return NewStruct(1, 0x10, 6, NewStruct(5, localSearchSlot, 0x10, data))
}

type photonDoc struct {
	Features []struct {
		Properties map[string]any `json:"properties"`
		Geometry   struct {
			Coordinates []float64 `json:"coordinates"`
		} `json:"geometry"`
	} `json:"features"`
}

func joinNonEmpty(sep string, xs ...string) string {
	var out []string
	for _, x := range xs {
		if x != "" {
			out = append(out, x)
		}
	}
	return strings.Join(out, sep)
}

func resultsFromPhoton(data []byte) ([]any, error) {
	var doc photonDoc
	if err := json.Unmarshal(data, &doc); err != nil {
		return nil, err
	}
	var out []any
	for _, f := range doc.Features {
		if len(f.Geometry.Coordinates) < 2 {
			return nil, errors.New("bad coordinates")
		}
		lon, lat := f.Geometry.Coordinates[0], f.Geometry.Coordinates[1]
		p := func(k string) string { s, _ := f.Properties[k].(string); return s }
		street := joinNonEmpty(" ", p("street"), p("housenumber"))
		addr := joinNonEmpty(", ", street, joinNonEmpty(" ", p("postcode"), p("city")), p("country"))
		name := p("name")
		if name == "" {
			name = street
		}
		if name == "" {
			name = addr
		}
		out = append(out, lsResult(name, addr, lat, lon))
	}
	return out, nil
}

// pyIntOf is Python int(v) for a decoded Hermes value.
func pyIntOf(v any) (int64, bool) {
	switch x := v.(type) {
	case int64:
		return x, true
	case bool:
		if x {
			return 1, true
		}
		return 0, true
	case string:
		return pyInt(x)
	case []byte:
		return pyInt(string(x))
	}
	return 0, false
}

// pyTruthy is Python truthiness of a decoded Hermes value.
func pyTruthy(v any) bool {
	switch x := v.(type) {
	case nil:
		return false
	case bool:
		return x
	case int64:
		return x != 0
	case BigInt:
		return x.Mag != 0
	case string:
		return x != ""
	case []byte:
		return len(x) > 0
	case []any:
		return len(x) > 0
	case *Struct:
		return true
	}
	return true
}

// pyEq is Python v == n for a decoded value and an int (True == 1).
func pyEq(v any, n int64) bool {
	switch x := v.(type) {
	case int64:
		return x == n
	case bool:
		return (x && n == 1) || (!x && n == 0)
	}
	return false
}

func orDefault(v any, def any) any {
	if pyTruthy(v) {
		return v
	}
	return def
}

// pySlice is Python's l[start:stop] bounds.
func pySlice(n, start, stop int64) (int64, int64) {
	clamp := func(i int64) int64 {
		if i < 0 {
			i += n
			if i < 0 {
				i = 0
			}
		}
		if i > n {
			i = n
		}
		return i
	}
	a, b := clamp(start), clamp(stop)
	if b < a {
		b = a
	}
	return a, b
}

func pyStrOf(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	if b, ok := v.([]byte); ok {
		return "b" + reprBytes(b)
	}
	return pyRepr(v)
}

// localSearch answers a search; args is payload tag 0x10 of the client's request.
func localSearch(args any, fetch PhotonFetch, log func(...any)) *Struct {
	s, ok := args.(*Struct)
	if !ok || !pyEq(s.Get(0), 1) {
		// details (mode 2) not decoded: reply with the shape the client tolerates as "no result"
		return NewStruct(1, 0x10, 6, NewStruct(5, localSearchSlot, 0x10, NewStruct(0, 0)))
	}
	reply, err := func() (*Struct, error) {
		n, ok1 := pyIntOf(orDefault(s.Get(5), int64(10)))
		off, ok2 := pyIntOf(orDefault(s.Get(6), int64(0)))
		ai, ok3 := pyIntOf(orDefault(s.Get(7), int64(0)))
		bi, ok4 := pyIntOf(orDefault(s.Get(8), int64(0)))
		if !(ok1 && ok2 && ok3 && ok4) {
			return nil, errors.New("bad search arguments")
		}
		// tag 7/8 order (lat/lon) is inferred; swap if the value is out of latitude range
		a, b := float64(ai)/1e5, float64(bi)/1e5
		lat, lon := a, b
		if math.Abs(a) > 90 {
			lat, lon = b, a
		}
		data, err := fetch(pyStrOf(orDefault(s.Get(2), "")), lat, lon, n+off, pyStrOf(orDefault(s.Get(4), "en")))
		if err != nil {
			return nil, err
		}
		res, err := resultsFromPhoton(data)
		if err != nil {
			return nil, err
		}
		x, y := pySlice(int64(len(res)), off, off+n)
		return searchReply(res[x:y], len(res)), nil
	}()
	if err != nil {
		log("localsearch failed:", err)
		return searchReply(nil, 0)
	}
	return reply
}
