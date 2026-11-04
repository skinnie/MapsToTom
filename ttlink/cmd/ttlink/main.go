// ttlink: plays a Bluetooth phone with dial-up networking for an old TomTom (ONE v4, GO 950) and carries
// the replacement Live services itself. Runs as root on an old Android (BlueZ 4) or any Linux with BlueZ.
//
//	TomTom --RFCOMM (DUN)--> AT answers --> PPP (192.168.77.1 <-> .2) --> TCP:
//	  192.168.77.1:8080  -> the built-in services (or -upstream HOST:8080)
//	  any other address  -> straight to the internet
package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"ttlink/internal/at"
	"ttlink/internal/bt"
	"ttlink/internal/ppp"
)

var (
	tcpAddr    = flag.String("tcp", "", "test mode: take the TomTom stream from TCP connections on this address instead of Bluetooth")
	channel    = flag.Int("channel", 0, "RFCOMM channel (0 = first free)")
	sdpPath    = flag.String("sdp", "", "BlueZ SDP server socket (default: search)")
	phoneClass = flag.Bool("phone-class", false, "set the Bluetooth class to Phone (for tablets; phones already are)")
	upstream   = flag.String("upstream", "", "send the TomTom's 192.168.77.1 traffic to this host instead of the built-in services")
	dataDir    = flag.String("dir", ".", "folder with ttlink.conf, fuel *.prices.json and the logs/ folder")
	daemon     = flag.Bool("daemon", false, "for the app: log to DIR/ttlink.log, pid in DIR/ttlink.pid, ignore hang-up")
)

// readConf reads DIR/ttlink.conf: key=value lines, # comments. The app writes it.
func readConf(dir string) map[string]string {
	m := map[string]string{}
	b, err := os.ReadFile(filepath.Join(dir, "ttlink.conf"))
	if err != nil {
		return m
	}
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		if k, v, ok := strings.Cut(line, "="); ok && !strings.HasPrefix(line, "#") {
			m[strings.TrimSpace(k)] = strings.TrimSpace(v)
		}
	}
	return m
}

// startDaemon makes the files the app reads: world-readable, because the app is not root.
func startDaemon(dir string) {
	syscall.Umask(0o022)
	signal.Ignore(syscall.SIGHUP)
	lp := filepath.Join(dir, "ttlink.log")
	if fi, err := os.Stat(lp); err == nil && fi.Size() > 512<<10 { // keep the log small
		os.Rename(lp, lp+".old")
	}
	f, err := os.OpenFile(lp, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		log.Fatal(err)
	}
	os.Chmod(lp, 0o644)
	log.SetOutput(f)
	os.WriteFile(filepath.Join(dir, "ttlink.pid"), []byte(strconv.Itoa(os.Getpid())), 0o644)
}

func main() {
	flag.Parse()
	log.SetFlags(log.Ltime)
	logf := func(a ...any) { log.Println(a...) }
	if *daemon {
		startDaemon(*dataDir)
	}
	logf("ttlink starting, pid", os.Getpid())

	svcAddr := ""
	if *upstream == "" {
		h, err := servicesHandler(*dataDir, logf)
		if err != nil {
			log.Fatal("services: ", err)
		}
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			log.Fatal(err)
		}
		svcAddr = ln.Addr().String()
		go (&http.Server{Handler: h, ReadHeaderTimeout: 30 * time.Second}).Serve(ln)
		logf("services built in")
	} else {
		logf("services at", *upstream)
	}

	dial := func(addr string) (net.Conn, error) {
		host, port, _ := net.SplitHostPort(addr)
		if host == "192.168.77.1" {
			if *upstream != "" {
				return net.DialTimeout("tcp", net.JoinHostPort(*upstream, port), 15*time.Second)
			}
			if port == "8080" {
				return net.DialTimeout("tcp", svcAddr, 5*time.Second)
			}
			return nil, fmt.Errorf("nothing on port %s", port)
		}
		return net.DialTimeout("tcp", addr, 15*time.Second)
	}

	serve := func(rw io.ReadWriteCloser, who string) {
		defer rw.Close()
		logf("connection from", who)
		if !at.Serve(rw, logf) {
			return
		}
		logf("dial: PPP up")
		ppp.New(rw, dial, logf).Run()
	}

	if *tcpAddr != "" {
		ln, err := net.Listen("tcp", *tcpAddr)
		if err != nil {
			log.Fatal(err)
		}
		logf("test mode: waiting on", ln.Addr())
		for {
			c, err := ln.Accept()
			if err != nil {
				log.Fatal(err)
			}
			go serve(c, c.RemoteAddr().String())
		}
	}

	if *phoneClass {
		if err := bt.SetPhoneClass(0); err != nil {
			logf("could not set the phone class:", err)
		}
	}
	l, err := bt.Listen(uint8(*channel))
	if err != nil {
		log.Fatal(err)
	}
	path := *sdpPath
	if path == "" {
		path = bt.FindSDPSocket()
	}
	if path == "" {
		log.Fatal("no BlueZ SDP socket found (try -sdp PATH; on BlueZ 5 start bluetoothd with -C)")
	}
	reg, err := bt.Register(path, bt.DUNRecord(l.Channel))
	if err != nil {
		log.Fatal("SDP register at ", path, ": ", err)
	}
	defer reg.Close()
	logf(fmt.Sprintf("Dial-up Networking on RFCOMM channel %d (SDP %s, record 0x%x); waiting for the TomTom", l.Channel, path, reg.Handle))
	for {
		f, peer, err := l.Accept()
		if err != nil {
			if strings.Contains(err.Error(), "interrupted") {
				continue
			}
			log.Fatal(err)
		}
		go serve(f, peer)
	}
}
