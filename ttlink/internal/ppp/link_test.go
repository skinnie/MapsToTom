package ppp

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

// Same checks as server/test_userppp.py: LCP/IPCP up like Linux pppd, a DNS answer, a 10 KB HTTP-sized reply
// relayed through Dial with one segment lost on purpose, then a clean close.

func streamPair(t *testing.T) (net.Conn, net.Conn) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	ch := make(chan net.Conn)
	go func() { c, _ := ln.Accept(); ch <- c }()
	a, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	return a, <-ch
}

type client struct {
	t       *testing.T
	c       net.Conn
	de      Deframer
	pending []Frame
}

func (k *client) recv(timeout time.Duration) *Frame {
	end := time.Now().Add(timeout)
	buf := make([]byte, 65536)
	for len(k.pending) == 0 && time.Now().Before(end) {
		k.c.SetReadDeadline(end)
		n, _ := k.c.Read(buf)
		k.pending = append(k.pending, k.de.Feed(buf[:n])...)
	}
	if len(k.pending) == 0 {
		return nil
	}
	f := k.pending[0]
	k.pending = k.pending[1:]
	return &f
}

func (k *client) ctl(proto uint16, code, ident byte, data []byte) {
	p := []byte{code, ident, 0, 0}
	binary.BigEndian.PutUint16(p[2:], uint16(4+len(data)))
	k.c.Write(Encode(proto, append(p, data...)))
}

func TestLink(t *testing.T) {
	body := bytes.Repeat(func() []byte {
		b := make([]byte, 256)
		for i := range b {
			b[i] = byte(i)
		}
		return b
	}(), 40)
	var dialed string
	dial := func(addr string) (net.Conn, error) {
		dialed = addr
		srv, cli := net.Pipe()
		go func() {
			req := make([]byte, 0, 512)
			b := make([]byte, 512)
			for !bytes.Contains(req, []byte("\r\n\r\n")) {
				n, err := srv.Read(b)
				if err != nil {
					return
				}
				req = append(req, b[:n]...)
			}
			srv.Write(append([]byte("HTTP/1.0 200 OK\r\nContent-Length: 10240\r\n\r\n"), body...))
			srv.Close()
		}()
		return cli, nil
	}
	a, b := streamPair(t)
	defer a.Close()
	defer b.Close()
	l := New(a, dial, func(...any) {})
	l.Resolve = func(name string) (net.IP, error) { return net.IPv4(127, 0, 0, 1), nil }
	go l.Run()
	k := &client{t: t, c: b}

	// LCP like Linux pppd (ACCM, magic, PFC, ACFC); ack whatever the server asks
	k.ctl(LCP, 1, 1, []byte{2, 6, 0, 0, 0, 0, 5, 6, 'A', 'B', 'C', 'D', 7, 2, 8, 2})
	myIP, dnsIP := []byte{0, 0, 0, 0}, []byte{0, 0, 0, 0}
	k.ctl(IPCP, 1, 2, append(append([]byte{3, 6}, myIP...), append([]byte{129, 6}, dnsIP...)...))
	lcpAcked, ipcpUp := false, false
	for !ipcpUp {
		f := k.recv(3 * time.Second)
		if f == nil {
			t.Fatal("no PPP reply")
		}
		code, ident := f.Payload[0], f.Payload[1]
		switch {
		case f.Proto == LCP && code == 1:
			k.ctl(LCP, 2, ident, f.Payload[4:])
		case f.Proto == LCP && code == 2:
			lcpAcked = true
		case f.Proto == IPCP && code == 3:
			for _, o := range options(f.Payload[4:]) {
				if o.t == 3 {
					myIP = o.v
				} else if o.t == 129 {
					dnsIP = o.v
				}
			}
			k.ctl(IPCP, 1, 3, append(append([]byte{3, 6}, myIP...), append([]byte{129, 6}, dnsIP...)...))
		case f.Proto == IPCP && code == 1:
			k.ctl(IPCP, 2, ident, f.Payload[4:])
		case f.Proto == IPCP && code == 2:
			ipcpUp = true
		}
	}
	if !lcpAcked || !bytes.Equal(myIP, PeerIP[:]) || !bytes.Equal(dnsIP, LocalIP[:]) {
		t.Fatalf("ppp up wrong: lcp acked %v ip %v dns %v", lcpAcked, myIP, dnsIP)
	}

	me, srv := PeerIP, LocalIP
	// DNS
	q := []byte{0, 7, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 9, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't', 0, 0, 1, 0, 1}
	udp := append([]byte{0x14, 0xe9, 0, 53, 0, byte(8 + len(q)), 0, 0}, q...)
	k.c.Write(Encode(IPV4, ipPacket(me, srv, 17, udp)))
	for {
		f := k.recv(3 * time.Second)
		if f == nil {
			t.Fatal("no dns reply")
		}
		if f.Proto == IPV4 && f.Payload[9] == 17 {
			ans := f.Payload[28:]
			if ans[0] != 0 || ans[1] != 7 || !bytes.Equal(ans[len(ans)-4:], []byte{127, 0, 0, 1}) {
				t.Fatalf("dns reply %x", ans)
			}
			break
		}
	}

	// TCP: SYN, request, read everything (dropping one segment), FIN
	const port = 8080
	tcp := func(flags byte, seq, ack uint32, data []byte) {
		seg := make([]byte, 20)
		binary.BigEndian.PutUint16(seg[0:], 40000)
		binary.BigEndian.PutUint16(seg[2:], port)
		binary.BigEndian.PutUint32(seg[4:], seq)
		binary.BigEndian.PutUint32(seg[8:], ack)
		seg[12], seg[13] = 0x50, flags
		binary.BigEndian.PutUint16(seg[14:], 2048)
		seg = append(seg, data...)
		binary.BigEndian.PutUint16(seg[16:], l4csum(me, srv, 6, seg))
		k.c.Write(Encode(IPV4, ipPacket(me, srv, 6, seg)))
	}
	tcpIn := func() (uint32, uint32, byte, []byte) {
		for {
			f := k.recv(5 * time.Second)
			if f == nil {
				t.Fatal("tcp timeout")
			}
			if f.Proto == IPV4 && f.Payload[9] == 6 {
				seg := f.Payload[20:]
				if l4csum(srv, me, 6, seg) != 0 {
					t.Fatal("bad tcp checksum")
				}
				off := int(seg[12]>>4) * 4
				return binary.BigEndian.Uint32(seg[4:]), binary.BigEndian.Uint32(seg[8:]), seg[13], seg[off:]
			}
		}
	}
	iss := uint32(1000)
	tcp(SYN, iss, 0, nil)
	seq, ack, fl, _ := tcpIn()
	if fl&SYN == 0 || fl&ACK == 0 || ack != iss+1 {
		t.Fatalf("syn-ack: flags %x ack %d", fl, ack)
	}
	if dialed != "192.168.77.1:8080" {
		t.Fatalf("dialed %q", dialed)
	}
	rcv := seq + 1
	req := []byte("GET /x HTTP/1.0\r\nHost: s\r\n\r\n")
	tcp(ACK|PSH, iss+1, rcv, req)
	var got []byte
	fin, dropped := false, false
	for !fin {
		seq, _, fl, data := tcpIn()
		if len(data) > 0 && !dropped && len(got) > 3000 { // lose one segment: the sender must retransmit
			dropped = true
			continue
		}
		if seq == rcv {
			got = append(got, data...)
			rcv += uint32(len(data))
			if fl&FIN != 0 {
				rcv++
				fin = true
			}
		}
		tcp(ACK, iss+1+uint32(len(req)), rcv, nil)
	}
	tcp(FIN|ACK, iss+1+uint32(len(req)), rcv, nil)
	parts := bytes.SplitN(got, []byte("\r\n\r\n"), 2)
	if len(parts) != 2 || !bytes.Equal(parts[1], body) {
		t.Fatalf("body: got %d bytes", len(got))
	}
	time.Sleep(300 * time.Millisecond)
	if n := l.OpenConns(); n != 0 {
		t.Fatalf("%d connections still open", n)
	}
}
