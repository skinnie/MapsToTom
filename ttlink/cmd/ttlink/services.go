package main

import (
	"net/http"
	"path/filepath"

	"ttlink/internal/services"
)

// servicesHandler builds the services the TomTom reaches at 192.168.77.1:8080. Settings from DIR/ttlink.conf:
// tomtom_key (traffic), france=1 (Bison Fute events). Fuel price sets: DIR/*.prices.json.
func servicesHandler(dir string, logf func(...any)) (http.Handler, error) {
	conf := readConf(dir)
	if conf["tomtom_key"] == "" {
		logf("no TomTom key in ttlink.conf: traffic stays empty")
	}
	return services.New(services.Config{
		Base:         "http://192.168.77.1:8080",
		Router:       "192.168.77.1:9000",
		LogDir:       filepath.Join(dir, "logs"),
		FuelGlob:     dir,
		TomTomKey:    conf["tomtom_key"],
		FranceDATEX2: conf["france"] == "1",
		Log:          logf,
	}), nil
}
