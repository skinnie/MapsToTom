package services

// WEATHER2 service (weather.py): XMsg reply for a=w2&m=gwl (docs/weather_schema.md), data from Open-Meteo.
// Icon codes and several column meanings are inferred, not confirmed on a device.

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// WeatherFetch returns the Open-Meteo daily forecast JSON for a position in degrees.
type WeatherFetch func(lat, lon float64) ([]byte, error)

// wmo maps a WMO weather code to the 6-char icon code (2-char sky + 4-char precipitation).
var wmo = map[float64]string{0: "so____", 1: "ms____", 2: "wb____", 3: "bd____", 45: "nb____", 48: "nb____",
	51: "bwr1__", 53: "bwr1__", 55: "bwr1__", 56: "bwgr1_", 57: "bwgr1_",
	61: "bwr1__", 63: "bwr2__", 65: "bwr3__", 66: "bwgr2_", 67: "bwgr2_",
	71: "bws1__", 73: "bws2__", 75: "bws3__", 77: "bws1__",
	80: "bwsr1_", 81: "bwsr2_", 82: "bwsr3_", 85: "bwsns1", 86: "bwsns2",
	95: "bwg1__", 96: "bwg2__", 99: "bwg3__"}

var (
	reP1   = regexp.MustCompile(`^(-?[0-9]+)x(-?[0-9]+)$`)
	reDate = regexp.MustCompile(`^([0-9]{4})-(1[0-2]|0[1-9]|[1-9])-(3[01]|[12][0-9]|0[1-9]|[1-9]| [1-9])$`)
)

var errWeather = errors.New("bad Open-Meteo data")

// timegmDate is calendar.timegm(time.strptime(day, "%Y-%m-%d")).
func timegmDate(v any) (int64, error) {
	s, ok := v.(string)
	if !ok {
		return 0, errWeather
	}
	m := reDate.FindStringSubmatch(s)
	if m == nil {
		return 0, errWeather
	}
	y, _ := strconv.Atoi(m[1])
	mo, _ := strconv.Atoi(m[2])
	d, _ := strconv.Atoi(strings.TrimSpace(m[3]))
	t := time.Date(y, time.Month(mo), d, 0, 0, 0, 0, time.UTC)
	if t.Day() != d || y < 1 {
		return 0, errWeather
	}
	return t.Unix(), nil
}

func truthy(v any) bool {
	switch x := v.(type) {
	case nil:
		return false
	case bool:
		return x
	case float64:
		return x != 0
	case string:
		return x != ""
	case []any:
		return len(x) > 0
	case map[string]any:
		return len(x) > 0
	}
	return true
}

func num(v any) (float64, bool, error) { // value, present, error
	switch x := v.(type) {
	case nil:
		return 0, false, nil
	case float64:
		return x, true, nil
	case bool:
		if x {
			return 1, true, nil
		}
		return 0, true, nil
	}
	return 0, false, errWeather
}

// numOr is _num(): int(round(v)), or the default for None.
func numOr(v any, def int64) (int64, error) {
	f, ok, err := num(v)
	if err != nil || !ok {
		return def, err
	}
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return 0, errWeather
	}
	return pyRound(f), nil
}

func rowsFromOpenMeteo(data []byte) ([][][]byte, error) {
	var d map[string]any
	if err := json.Unmarshal(data, &d); err != nil {
		return nil, err
	}
	dl, ok := d["daily"].(map[string]any)
	if !ok {
		return nil, errWeather
	}
	days, ok := dl["time"].([]any)
	if !ok {
		return nil, errWeather
	}
	if len(days) > 5 {
		days = days[:5]
	}
	var rows [][][]byte
	for i, day := range days {
		ts, err := timegmDate(day)
		if err != nil {
			return nil, err
		}
		var gerr error
		g := func(k string) any {
			v := dl[k]
			if !truthy(v) {
				return nil
			}
			l, ok := v.([]any)
			if !ok || i >= len(l) {
				gerr = errWeather
				return nil
			}
			return l[i]
		}
		var errs []error
		n := func(k string, def int64) int64 {
			v, err := numOr(g(k), def)
			errs = append(errs, err)
			return v
		}
		tmax, tmin := n("temperature_2m_max", -999), n("temperature_2m_min", -999)
		icon := "bd____"
		if f, ok, _ := num(g("weather_code")); ok {
			if s, ok := wmo[f]; ok {
				icon = s
			}
		}
		wind := max(n("wind_speed_10m_max", 0), 0)
		wdir := pyMod(n("wind_direction_10m_dominant", 0), 361)
		pp := n("precipitation_probability_max", 0)
		uv := min(max(n("uv_index_max", 0), 0), 16)
		hum := ""
		if v := g("relative_humidity_2m_mean"); v != nil {
			hum = fmt.Sprint(n("relative_humidity_2m_mean", -1))
		}
		vis := ""
		if v := g("visibility_mean"); v != nil {
			f, _, err := num(v)
			if err != nil || math.IsNaN(f) || math.IsInf(f, 0) {
				return nil, errWeather
			}
			vis = fmt.Sprint(int64(f / 1000))
		}
		if gerr != nil {
			return nil, gerr
		}
		for _, e := range errs {
			if e != nil {
				return nil, e
			}
		}
		cols := []string{fmt.Sprint(ts), fmt.Sprint(tmax), fmt.Sprint(tmin), icon, fmt.Sprint(wind), fmt.Sprint(wdir), "0",
			fmt.Sprint(pp), fmt.Sprint(uv), "-1", "0", "", "0", hum, vis}
		row := make([][]byte, len(cols))
		for k, c := range cols {
			row[k] = []byte(c)
		}
		rows = append(rows, row)
	}
	return rows, nil
}

// OpenMeteo fetches the 5-day daily forecast for weather replies.
func OpenMeteo(client *http.Client) WeatherFetch {
	return func(lat, lon float64) ([]byte, error) {
		q := "latitude=" + fmt.Sprintf("%.4f", lat) + "&longitude=" + fmt.Sprintf("%.4f", lon) +
			"&timezone=UTC&forecast_days=5&daily=" + url.QueryEscape("temperature_2m_max,temperature_2m_min,weather_code,"+
			"wind_speed_10m_max,wind_direction_10m_dominant,precipitation_probability_max,uv_index_max,"+
			"relative_humidity_2m_mean,visibility_mean")
		resp, err := client.Get("https://api.open-meteo.com/v1/forecast?" + q)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			return nil, fmt.Errorf("open-meteo: HTTP %d", resp.StatusCode)
		}
		return io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	}
}

// weatherReply answers a=w2&m=gwl; query is the request's query string.
func weatherReply(query string, fetch WeatherFetch, log func(...any)) []byte {
	p1, _ := qget(parseQS(query, false), "p1")
	m := reP1.FindStringSubmatch(p1)
	if m == nil {
		return xmsgReply(nil, 204)
	}
	x, e1 := strconv.ParseInt(m[1], 10, 64)
	y, e2 := strconv.ParseInt(m[2], 10, 64)
	if e1 != nil || e2 != nil {
		return xmsgReply(nil, 204)
	}
	lon, lat := float64(x)/1e5, float64(y)/1e5
	data, err := fetch(lat, lon)
	var rows [][][]byte
	if err == nil {
		rows, err = rowsFromOpenMeteo(data)
	}
	if err != nil {
		log("weather fetch failed:", err)
		return xmsgReply(nil, 204)
	}
	return xmsgReply([][]byte{
		argList([][]byte{[]byte(fmt.Sprintf("%.2f", lat)), []byte(fmt.Sprintf("%.2f", lon))}, ';'),
		argInt(int64(len(rows))), argTable(rows)}, 0)
}
