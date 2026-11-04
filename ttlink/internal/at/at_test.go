package at

import (
	"net"
	"strings"
	"testing"
	"time"
)

func TestReply(t *testing.T) {
	cases := map[string]string{
		"AT+CGMI":     "\r\nSonyEricsson\r\n\r\nOK\r\n",
		"at+cops?":    "\r\n+COPS: 0,2,\"26801\"\r\n\r\nOK\r\n",
		"AT+CLCC":     "\r\nOK\r\n",
		"AT+XYZ":      "\r\nOK\r\n",
		"ATD0123456;": "\r\nOK\r\n",
		"ATD*99***1#": "\r\nCONNECT\r\n",
	}
	for in, want := range cases {
		if got, _ := Reply(in); got != want {
			t.Errorf("%q -> %q, want %q", in, got, want)
		}
	}
	if _, dial := Reply("ATD*99***1#"); !dial {
		t.Error("data dial not detected")
	}
}

func TestServeEchoAndDial(t *testing.T) {
	a, b := net.Pipe()
	done := make(chan bool)
	go func() { done <- Serve(a, func(...any) {}) }()
	read := func() string {
		b.SetReadDeadline(time.Now().Add(2 * time.Second))
		buf := make([]byte, 512)
		n, _ := b.Read(buf)
		return string(buf[:n])
	}
	b.Write([]byte("ATZ\r"))
	if r := read(); !strings.HasPrefix(r, "ATZ\r") || !strings.HasSuffix(r, "OK\r\n") {
		t.Fatalf("ATZ: %q", r)
	}
	b.Write([]byte("ATE0\r"))
	read()
	b.Write([]byte("AT+CGMM\r"))
	if r := read(); strings.HasPrefix(r, "AT") || !strings.Contains(r, "AAB-1022041-BV") {
		t.Fatalf("after ATE0: %q", r)
	}
	b.Write([]byte("ATD*99***1#\r"))
	if r := read(); r != "\r\nCONNECT\r\n" {
		t.Fatalf("dial: %q", r)
	}
	if !<-done {
		t.Fatal("Serve did not report the dial")
	}
}
