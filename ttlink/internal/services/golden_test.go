package services

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// goldenDir: the anonymised request/reply pairs of go950-navcore-9541/server/make_goldens.py, shared with the Kotlin LiveServerTest.
const goldenDir = "../../../composeApp/src/desktopTest/resources/goldens"

// goldenSource is make_goldens.py's src: the fixed TomTom tile, filtered on the unrounded 1e-5 degree position.
func goldenSource(t *testing.T) Source {
	data, err := os.ReadFile(filepath.Join(goldenDir, "inputs/tomtom_lisboa_tile.json"))
	if err != nil {
		t.Fatal(err)
	}
	tile, err := ParseTomTom(data)
	if err != nil {
		t.Fatal(err)
	}
	return func(bb *BBox) []Incident {
		var out []Incident
		for _, i := range tile {
			x, y := i.Lon*1e5, i.Lat*1e5
			if bb == nil || (float64(min(bb[0], bb[2])) <= x && x <= float64(max(bb[0], bb[2])) &&
				float64(min(bb[1], bb[3])) <= y && y <= float64(max(bb[1], bb[3]))) {
				out = append(out, i)
			}
		}
		return out
	}
}

func goldenHandler(t *testing.T) *server {
	meteo, err := os.ReadFile(filepath.Join(goldenDir, "inputs/openmeteo_sample.json"))
	if err != nil {
		t.Fatal(err)
	}
	return New(Config{
		Base:     "http://192.168.77.1:8080",
		Router:   "192.168.77.1:9000",
		LogDir:   t.TempDir(),
		FuelGlob: filepath.Join(goldenDir, "inputs/fuel.prices.json"),
		Source:   goldenSource(t),
		Weather:  func(lat, lon float64) ([]byte, error) { return meteo, nil },
		Photon: func(string, float64, float64, int64, string) ([]byte, error) {
			t.Fatal("no network in tests")
			return nil, nil
		},
	}).(*server)
}

func TestGoldens(t *testing.T) {
	var index struct {
		Pairs []struct {
			File, Method, Path string
		} `json:"pairs"`
	}
	data, err := os.ReadFile(filepath.Join(goldenDir, "index.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &index); err != nil {
		t.Fatal(err)
	}
	if len(index.Pairs) != 22 {
		t.Fatalf("%d pairs, want 22", len(index.Pairs))
	}
	h := goldenHandler(t) // one handler: the Hercules POSTs are one login session, Portland remembers sent rows
	for _, p := range index.Pairs {
		req, err := os.ReadFile(filepath.Join(goldenDir, p.File+".req"))
		if err != nil {
			t.Fatal(err)
		}
		want, err := os.ReadFile(filepath.Join(goldenDir, p.File+".resp"))
		if err != nil {
			t.Fatal(err)
		}
		line, body, _ := bytes.Cut(req, []byte("\n"))
		method, target, _ := bytes.Cut(line, []byte(" "))
		r := httptest.NewRequest(string(method), string(target), bytes.NewReader(body))
		r.Header.Set("User-Agent", "ONE:9.541:320x240")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, r)
		got := rec.Body.Bytes()
		if rec.Code != 200 || !bytes.Equal(got, want) {
			t.Errorf("%s: status %d, %d bytes, want %d bytes\n got %q\nwant %q", p.File, rec.Code, len(got), len(want),
				clip(got), clip(want))
		} else {
			t.Logf("%s: ok (%d bytes)", p.File, len(got))
		}
	}
}

func clip(b []byte) []byte {
	if len(b) > 300 {
		return b[:300]
	}
	return b
}
