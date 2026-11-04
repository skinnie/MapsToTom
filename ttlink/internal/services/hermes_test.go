package services

import (
	"bytes"
	"encoding/hex"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Vectors from hermes.encode (python3 -I).
var hermesVectors = []struct {
	v   any
	hex string
}{
	{nil, "0f"},
	{true, "c1"},
	{false, "c0"},
	{int64(0), "c200"},
	{int64(255), "c2ff"},
	{int64(256), "c40100"},
	{int64(-1), "c301"},
	{int64(65536), "c600010000"},
	{int64(1<<31 - 1), "c67fffffff"},
	{int64(1 << 31), "c80000000080000000"}, // 4-byte ints stop at 0x7FFFFFFF
	{int64(-1 << 63), "c98000000000000000"},
	{BigInt{false, 1<<64 - 1}, "c8ffffffffffffffff"},
	{"abc", "4003616263"},
	{"é", "4002c3a9"},
	{[]byte{0, 0xff}, "800200ff"},
	{[]any{}, "20"},
	{[]any{int64(1), int64(2), int64(3), int64(4), int64(5), int64(6), int64(7), int64(8)}, "2808c201c202c203c204c205c206c207c208"},
	{NewStruct(1, int64(3), 2, "Z", 6, NewStruct(0x12, []byte{0, 1, 2, 3, 4, 5, 6, 7})), "1301c2030240015a06111280080001020304050607"},
}

func TestHermesEncodeDecode(t *testing.T) {
	for _, c := range hermesVectors {
		got, err := Encode(c.v)
		if err != nil || hex.EncodeToString(got) != c.hex {
			t.Errorf("Encode(%s) = %x, %v; want %s", pyRepr(c.v), got, err, c.hex)
			continue
		}
		back, err := Decode(got)
		if err != nil {
			t.Errorf("Decode(%s): %v", c.hex, err)
			continue
		}
		again, _ := Encode(back)
		if !bytes.Equal(again, got) || pyRepr(back) != pyRepr(c.v) {
			t.Errorf("round trip %s: %s", c.hex, pyRepr(back))
		}
	}
}

func TestHermesLimits(t *testing.T) {
	long, _ := Encode(strings.Repeat("x", 300))
	if hex.EncodeToString(long[:3]) != "412c78" {
		t.Errorf("300-byte string header %x", long[:3])
	}
	l := make([]any, 2047)
	for i := range l {
		l[i] = int64(i)
	}
	if b, err := Encode(l); err != nil || hex.EncodeToString(b[:2]) != "2fff" {
		t.Errorf("2047-item list: %x %v", b[:2], err)
	}
	if _, err := Encode(append(l, nil)); err == nil {
		t.Error("2048 items should not encode")
	}
	if _, err := Encode(make([]byte, maxStr+1)); err == nil {
		t.Error("over-long buffer should not encode")
	}
	if s, _ := Encode(strings.Repeat("y", maxStr+5)); len(s) != 2+maxStr {
		t.Error("strings are cut to 0x3FFF bytes")
	}
	for _, bad := range []string{"", "40", "4005616263", "8001", "c4ff", "30", "ca", "1101", "2a"} {
		if _, err := Decode(must(hex.DecodeString(bad))); err == nil {
			t.Errorf("Decode(%s) should fail", bad)
		}
	}
	if v, _ := Decode([]byte{0x40, 2, 0xe2, 0x82}); v != "�" { // one U+FFFD per invalid subpart, like Python
		t.Errorf("invalid utf-8 -> %q", v)
	}
}

func TestPackBody(t *testing.T) {
	small, _ := PackBody(NewStruct(1, 1))
	if hex.EncodeToString(small) != "001101c201" {
		t.Errorf("small body %x", small)
	}
	big := NewStruct(1, 16, 2, strings.Repeat("abc", 200))
	b, _ := PackBody(big)
	if b[0] != 0x10 {
		t.Fatalf("compressible body flag %x", b[0])
	}
	v, err := UnpackBody(b)
	if err != nil || pyRepr(v) != pyRepr(big) {
		t.Errorf("unpack: %v", err)
	}
	if _, err := UnpackBody([]byte{0x20, 0}); err == nil {
		t.Error("compression 2 should fail")
	}
}

func TestXMsgFraming(t *testing.T) {
	got := xmsgReply([][]byte{argInt(-5), argStr([]byte("OK")), argBuf([]byte{1, 2}),
		argList([][]byte{[]byte("1.00"), []byte("-2.00")}, ';'), argTable([][][]byte{{[]byte("a"), []byte("b")}, {[]byte("c"), {}}})}, 0)
	want := "00000082000000051-000000000052000002OK700000002\x01\x028;0000101.00;-2.004\t\n000006a\tb\nc\t"
	if string(got) != want {
		t.Errorf("got %q\nwant %q", got, want)
	}
	if s := string(xmsgReply(nil, 204)); s != "0000001602040000" {
		t.Errorf("204: %q", s)
	}
	if s := string(xmsgReply(nil, 600)); s != "0000001606000000" {
		t.Errorf("600: %q", s)
	}
	if s := string(xmsgReply([][]byte{argTable(nil)}, 0)); s != "00000025000000014\t\n000000" {
		t.Errorf("empty table: %q", s)
	}
	blob := discoveryBlob("http://h:1", "h:2")
	d := xmsgReply([][]byte{argStr([]byte(blob))}, 0)
	if !strings.HasPrefix(string(d[8:]), "000000012") || !strings.Contains(blob, "ROUTER2\nPORTLAND|http://h:1/portland\nROUTER|h:2\n\n") {
		t.Errorf("discovery reply %q", d[:40])
	}
}

// A restart rebuilds the Hercules session key from the logged login, so the next encrypted request still decodes.
func TestSessionRestore(t *testing.T) {
	read := func(name string) (string, []byte) {
		req := must(os.ReadFile(filepath.Join(goldenDir, name+".req")))
		line, body, _ := bytes.Cut(req, []byte("\n"))
		_, target, _ := bytes.Cut(line, []byte(" "))
		return string(target), body
	}
	post := func(h *server, name string) []byte {
		target, body := read(name)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest("POST", target, bytes.NewReader(body)))
		return rec.Body.Bytes()
	}
	dir := t.TempDir()
	cfg := Config{LogDir: dir, Source: goldenSource(t)}
	post(New(cfg).(*server), "15_h204_kind3")
	want := must(os.ReadFile(filepath.Join(goldenDir, "17_h204_kind16.resp")))
	if got := post(New(cfg).(*server), "17_h204_kind16"); !bytes.Equal(got, want) {
		t.Errorf("after restart: %x, want %x", got, want)
	}
	cfg.LogDir = t.TempDir() // no login logged: the payload stays undecrypted
	if got := post(New(cfg).(*server), "17_h204_kind16"); bytes.Equal(got, want) {
		t.Error("reply should differ without the session")
	}
}

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}
