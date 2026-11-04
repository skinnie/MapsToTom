// Package services is the GO 950 / ONE v4 Live replacement server (port of server/mockserver.py and its modules):
// service discovery, Hercules (traffic over hermes), Portland traffic, weather, fuel prices, account/logon stubs.
//
//	GET  /services/directory.php   -> Hercules base URL (plain text)
//	GET  /proxy/directory.php?u=.. -> ServiceDiscovery reply (XMsg) with the discovery blob
//	POST /h204                     -> Hercules (login / subscribe / traffic incidents)
//	GET  anything else             -> logged, XMsg "no content"
//
// Every request can be appended to <LogDir>/requests.jsonl so a first run on a device shows exactly what it sends.
package services

import (
	"bufio"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Config configures the services. Zero values give mockserver.py's defaults where it has them.
type Config struct {
	Base   string // this server as the device reaches it, e.g. "http://192.168.77.1:8080" (mockserver --base)
	Router string // ROUTER2/ROUTER "host:port" (not a URL) (mockserver --router)

	LogDir string // requests.jsonl + portland.jsonl, and the Hercules session restore; "" = none

	// FuelGlob is a glob of <name>.prices.json files (fuel.py output), or a directory holding them.
	// "" = no sets (every fuel request gets 600, wrong set).
	FuelGlob string

	// Traffic sources, combined in this order: IncidentsFile (JSON list, re-read on each fetch), France DATEX II,
	// TomTom (TomTomKey != ""). Source replaces all three when set.
	IncidentsFile string
	FranceDATEX2  bool
	TomTomKey     string
	Source        Source

	Weather WeatherFetch // nil = Open-Meteo
	Photon  PhotonFetch  // local search; nil = photon.komoot.io

	HTTPClient *http.Client // for the live sources; nil = a client with a 30 s timeout (Go's own TLS)
	Log        func(...any) // console lines; nil = silent
}

type server struct {
	cfg   Config
	blob  string
	herc  *hercules
	port  *portland
	fuel  *fuelStore
	log   func(...any)
	logMu sync.Mutex
}

// apnTestMagic is TEST_BIN's reply: the 27-byte magic at ttn VA 0x77203c.
var apnTestMagic = []byte("A9PxN7O3Kv-*26_`.?/e~=XxYq@")

// Subscription rows for the AccountInfo reply (docs/captured_requests.md). Columns in the order FUN_00120994
// consumes them: 0 product name (table at FUN_0014adc4), 1 start secs, 2 expiry secs, 3/4/5 flags (4,2,1), 6 text,
// 7 text, 8 flag (8), 9 int, 10 text, 11 flag (0x20), 12 flag (0x40)
var accountProducts = []string{"SPD_DD", "SPD_DDS", "SPD_GD", "SPD_MD", "SPD_PD", "SPD_CD", "SPD_NV", "SPD_VD", "SPD_RT",
	"SPD_CU", "SPD_SD", "ROADCONDITIONS", "FUELPRICES", "LOCALSEARCH", "TRACK", "DYNSC", "BUDDY", "MISC", "NAVTRAFFIC",
	"WEATHER2",
	// service 0x11 (traffic) has no fixed product key (FUN_0014adc4: [this+4]->vtbl[0x118]()), probably taken from the
	// map's traffic.dat (Iberia: NAVTRAFFIC / NTadespt / 301E17F32843842C10C07); rows for each line
	"NTadespt", "301E17F32843842C10C07"}

var trafficProducts = map[string]bool{"NAVTRAFFIC": true, "NTadespt": true, "301E17F32843842C10C07": true}

func accountRows() [][][]byte {
	const start, expiry = 1262304000, 2147000000
	var rows [][][]byte
	for _, p := range accountProducts {
		// column 9 = row type (+0x19c): FUN_001221c4 skips types 0/1 and accepts type 2 as a valid subscription at
		// once; traffic (svc 0x11) is checked against these rows on the v4, weather goes through its activation status
		typ := "0"
		if trafficProducts[p] {
			typ = "2"
		}
		cols := []string{p, fmt.Sprint(start), fmt.Sprint(expiry), "1", "1", "1", "live", "live", "1", typ, "", "1", "1"}
		row := make([][]byte, len(cols))
		for i, c := range cols {
			row[i] = []byte(c)
		}
		rows = append(rows, row)
	}
	return rows
}

// New returns the HTTP handler of all services.
func New(cfg Config) http.Handler {
	if cfg.Base == "" {
		cfg.Base = "http://127.0.0.1:8080"
	}
	if cfg.Router == "" {
		cfg.Router = "127.0.0.1:9000"
	}
	logf := cfg.Log
	if logf == nil {
		logf = func(...any) {}
	}
	client := cfg.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 30 * time.Second}
	}
	src := cfg.Source
	if src == nil {
		var srcs []Source
		if cfg.IncidentsFile != "" {
			if _, err := os.Stat(cfg.IncidentsFile); err == nil {
				srcs = append(srcs, FileSource(cfg.IncidentsFile, logf))
			}
		}
		if cfg.FranceDATEX2 {
			srcs = append(srcs, FrDATEX2(client, logf))
		}
		if cfg.TomTomKey != "" {
			srcs = append(srcs, TomTom(cfg.TomTomKey, client, logf))
		}
		src = Combine(srcs...)
	}
	if cfg.Weather == nil {
		cfg.Weather = OpenMeteo(client)
	}
	if cfg.Photon == nil {
		cfg.Photon = Photon(client)
	}
	s := &server{cfg: cfg, blob: discoveryBlob(cfg.Base, cfg.Router), herc: newHercules(src, cfg.Photon, logf),
		port: newPortland(src, logf), fuel: &fuelStore{glob: cfg.FuelGlob}, log: logf}
	if cfg.LogDir != "" {
		if err := os.MkdirAll(cfg.LogDir, 0o755); err != nil {
			logf("services: log dir:", err)
		}
		s.restoreSessions()
	}
	return s
}

// restoreSessions: session keys live only in memory, so rebuild them from the last logged Hercules login (kind 3)
// of each device; a server restart then does not leave a still-logged-in device with undecryptable requests.
func (s *server) restoreSessions() {
	f, err := os.Open(filepath.Join(s.cfg.LogDir, "requests.jsonl"))
	if err != nil {
		return
	}
	defer f.Close()
	last := map[string]string{}
	var order []string
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 64<<20)
	for sc.Scan() {
		var r struct {
			Kind    string `json:"kind"`
			Raw     string `json:"raw"`
			Decoded any    `json:"decoded"`
		}
		if json.Unmarshal(sc.Bytes(), &r) != nil || r.Kind != "POST" || r.Raw == "" {
			continue
		}
		dec, ok := r.Decoded.(map[string]any)
		if !ok {
			continue
		}
		items, ok := dec["struct"].([]any)
		if !ok || len(items) < 2 {
			continue
		}
		first, ok1 := items[0].([]any)
		second, ok2 := items[1].([]any)
		if !ok1 || !ok2 || len(first) != 2 || len(second) < 2 || first[0] != 1.0 || first[1] != 3.0 {
			continue
		}
		k, _ := json.Marshal(second[1])
		if _, seen := last[string(k)]; !seen {
			order = append(order, string(k))
		}
		last[string(k)] = r.Raw
	}
	for _, k := range order {
		raw, err := hex.DecodeString(last[k])
		if err == nil {
			_, err = s.herc.handleBody(raw)
		}
		if err != nil {
			s.log("could not restore session for", k, err)
		} else {
			s.log("restored Hercules session for", k)
		}
	}
}

func (s *server) appendLog(name string, rec jobj) {
	if s.cfg.LogDir == "" {
		return
	}
	s.logMu.Lock()
	defer s.logMu.Unlock()
	f, err := os.OpenFile(filepath.Join(s.cfg.LogDir, name), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		s.log("services log:", err)
		return
	}
	defer f.Close()
	io.WriteString(f, jsonDumps(rec)+"\n")
}

func unixNow() float64 { return float64(time.Now().UnixNano()) / 1e9 }

// logReq writes one requests.jsonl record: t, kind, path, client, headers, then the extras.
func (s *server) logReq(kind string, r *http.Request, path string, extra jobj) {
	s.log(kind, path)
	if s.cfg.LogDir == "" {
		return
	}
	client, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		client = r.RemoteAddr
	}
	hdr := jobj{{"Host", r.Host}}
	keys := make([]string, 0, len(r.Header))
	for k := range r.Header {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		v := r.Header[k]
		hdr = append(hdr, jkv{k, latin1(v[len(v)-1])})
	}
	rec := jobj{{"t", unixNow()}, {"kind", kind}, {"path", path}, {"client", client}, {"headers", hdr}}
	s.appendLog("requests.jsonl", append(rec, extra...))
}

func send(w http.ResponseWriter, code int, body []byte, ctype string) {
	h := w.Header()
	h.Set("Server", "GO950Live/0.2")
	h.Set("Content-Type", ctype)
	h.Set("Content-Length", fmt.Sprint(len(body)))
	h.Set("Connection", "close")
	w.WriteHeader(code)
	w.Write(body)
}

// abort drops the connection without a reply, as the Python server does when its handler raises.
func abort() { panic(http.ErrAbortHandler) }

func pyListRepr(xs []string) string {
	parts := make([]string, len(xs))
	for i, x := range xs {
		parts[i] = reprStr(x)
	}
	return "[" + strings.Join(parts, ", ") + "]"
}

func (s *server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := latin1(r.RequestURI)
	switch r.Method {
	case "GET":
		s.get(w, r, path)
	case "POST":
		s.post(w, r, path)
	default: // BaseHTTPRequestHandler without a do_<METHOD>
		body := fmt.Sprintf("<!DOCTYPE HTML>\n<html lang=\"en\">\n    <head>\n        <meta charset=\"utf-8\">\n"+
			"        <title>Error response</title>\n    </head>\n    <body>\n        <h1>Error response</h1>\n"+
			"        <p>Error code: 501</p>\n        <p>Message: Unsupported method (&#x27;%s&#x27;).</p>\n"+
			"        <p>Error code explanation: 501 - Server does not support this operation.</p>\n    </body>\n</html>\n",
			strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace(r.Method))
		send(w, 501, []byte(body), "text/html;charset=utf-8")
	}
}

func (s *server) get(w http.ResponseWriter, r *http.Request, path string) {
	s.logReq("GET", r, path, nil)
	p, query, _ := strings.Cut(path, "?")
	xr := func(args [][]byte, code int) { send(w, 200, xmsgReply(args, code), "text/plain") }
	switch p {
	case "/services/directory.php":
		send(w, 200, []byte(s.cfg.Base+"/h204"), "text/plain")
	case "/proxy/directory.php":
		xr([][]byte{argStr([]byte(s.blob))}, 0)
	case "/portland": // a=pr m=gpid -> 0204 no content; a=nt m=gtmo|gstuo|gtuo|gtd2 -> incident table
		req, err := parsePortland(query)
		if err != nil {
			abort()
		}
		s.appendLog("portland.jsonl", jobj{{"t", unixNow()}, {"q", req.qJSON()}})
		out, err := s.port.handle(req)
		if err != nil {
			abort()
		}
		send(w, 200, out, "text/plain")
	case "/weather":
		send(w, 200, weatherReply(query, s.cfg.Weather, s.log), "text/plain")
	case "/logon": // LOGON reply: arg0 buffer (session key, <= 0x51 bytes), optional arg1 integer (docs: FUN_0039d218)
		key := make([]byte, 32)
		for i := range key {
			key[i] = byte(i)
		}
		xr([][]byte{argBuf(key), argInt(0)}, 0)
	case "/account2": // AccountInfo (a=psa m=ai3): verifier 0x11fbb4 wants err 0 + one table with >10 columns; rows parsed by FUN_00120994
		xr([][]byte{argTable(accountRows())}, 0)
	case "/svcact", "/prodact": // activation reply (docs/traffic_schema.md 6.4): 4 strings + int status 200
		live := argStr([]byte("live"))
		xr([][]byte{live, live, live, live, argInt(200)}, 0)
	case "/fuelprices": // a=fp m=gfl2 t=LII: p1 area, p2 fuel type, p3 'd'-header set id (docs/fuelprices_schema.md 3)
		s.fuelPrices(w, r, path, query)
	case "/apn": // APN_LOG (CBrowseAPN, ?setuptype=auto|semi*|manual or ?data=<log>): body must start with "APNOK"
		send(w, 200, []byte("APNOK"), "text/plain") // raw text: verified on the v4 2026-10-30 (the XMsg-wrapped string was rejected)
	case "/apn_test": // TEST_BIN: 1 buffer arg starting with the 27-byte magic at VA 0x77203c
		xr([][]byte{argBuf(apnTestMagic)}, 0) // XMsg buffer: verified on the v4 2026-10-30
	default: // unknown service: XMsg "no content"
		xr(nil, 204)
	}
}

func (s *server) fuelPrices(w http.ResponseWriter, r *http.Request, path, query string) {
	q := parseQS(query, false)
	p3, has3 := qget(q, "p3")
	p2, has2 := qget(q, "p2")
	a, hasA := qget(q, "a")
	sets := s.fuel.sets()
	data, ok := sets[p3]
	if !ok { // the card has a set this server does not know
		ids := make([]string, 0, len(sets))
		for k := range sets {
			ids = append(ids, k)
		}
		sort.Strings(ids)
		got := "None"
		if has3 {
			got = p3
		}
		s.logReq("fuel-reply", r, path, jobj{{"result", fmt.Sprintf("600 set id %s, server has %s", got, pyListRepr(ids))}})
		send(w, 200, xmsgReply(nil, 600), "text/plain") // StrFuelPricesFileWrongVersion
		return
	}
	if !data.hasTyp {
		abort()
	}
	pv := data.types[p2]
	prices, isList := pv.([]any)
	if hasA && a == "fp" && !isList && truthyNum(pv) { // any() of a non-list
		abort()
	}
	anyPrice := false
	priced := 0
	for _, x := range prices {
		if truthyNum(x) {
			anyPrice = true
			priced++
		}
	}
	if !(hasA && a == "fp") || !anyPrice {
		got := "None"
		if has2 {
			got = p2
		}
		s.logReq("fuel-reply", r, path, jobj{{"result", fmt.Sprintf("204 type %s not available", got)}})
		send(w, 200, xmsgReply(nil, 204), "text/plain") // StrFuelTypeNotAvailable
		return
	}
	ftype, ok := pyInt(p2)
	if !ok {
		abort()
	}
	blob, err := fpu0(ftype, prices)
	if err != nil {
		abort()
	}
	s.logReq("fuel-reply", r, path, jobj{{"set", data.name}, {"stations", len(prices)}, {"priced", priced}, {"type", ftype}})
	send(w, 200, xmsgReply([][]byte{argStr([]byte("OK")), argBuf(blob)}, 0), "text/plain")
}

const maxBody = 4 << 20

func (s *server) post(w http.ResponseWriter, r *http.Request, path string) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, maxBody))
	head := body
	if len(head) > 4096 {
		head = head[:4096]
	}
	p, _, _ := strings.Cut(path, "?")
	if p != "/h204" {
		s.logReq("POST-other", r, path, jobj{{"raw", hex.EncodeToString(head)}})
		send(w, 200, xmsgReply(nil, 204), "text/plain")
		return
	}
	msg, err := UnpackBody(body)
	var out []byte
	if err == nil {
		s.logReq("POST", r, path, jobj{{"decoded", show(msg)}, {"raw", hex.EncodeToString(body)}})
		out, err = s.herc.handleBody(body)
	}
	if err != nil {
		s.logReq("POST-error", r, path, jobj{{"error", err.Error()}, {"raw", hex.EncodeToString(head)}})
		out, _ = PackBody(errorReply(9, "bad request"))
	}
	send(w, 200, out, "application/octet-stream")
}
