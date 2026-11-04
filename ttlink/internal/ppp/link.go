// Package ppp is a user-space PPP endpoint with a TCP/UDP relay (port of server/userppp.py): the TomTom's
// dial-up link without pppd and without a network interface.
//
//	LCP/IPCP: we are 192.168.77.1, the TomTom gets 192.168.77.2, DNS = 192.168.77.1
//	ICMP echo                    -> answered here
//	UDP 53 (DNS)                 -> answered here with Resolve
//	TCP to any address:port      -> Dial("ip:port"); the caller decides where 192.168.77.1 goes
package ppp

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	LCP  = 0xc021
	IPCP = 0x8021
	IPV4 = 0x0021
)

var (
	LocalIP = [4]byte{192, 168, 77, 1}
	PeerIP  = [4]byte{192, 168, 77, 2}
)

func ipStr(a [4]byte) string { return net.IP(a[:]).String() }

type Link struct {
	rw      io.ReadWriter
	Dial    func(addr string) (net.Conn, error)
	Resolve func(name string) (net.IP, error)
	Log     func(...any)

	wmu sync.Mutex
	mu  sync.Mutex
	tcp map[connKey]*tcpConn

	magic                      uint32
	lcpUp, lcpAcked, ipcpAcked bool
	ipcpPeerOK                 bool
	ourLCP                     map[byte][]byte
	peerMRU                    int
	ident                      byte
	running                    atomic.Bool
	done                       chan struct{}
}

// New makes a link on rw (the RFCOMM stream after CONNECT). dial gets "ip:port" of every TCP connection
// the TomTom opens.
func New(rw io.ReadWriter, dial func(string) (net.Conn, error), log func(...any)) *Link {
	magic := uint32(time.Now().Unix())
	if magic == 0 {
		magic = 1
	}
	l := &Link{rw: rw, Dial: dial, Log: log, tcp: map[connKey]*tcpConn{}, magic: magic, peerMRU: 1500,
		done: make(chan struct{})}
	l.running.Store(true)
	l.ourLCP = map[byte][]byte{2: {0, 0, 0, 0}, 5: be32(magic)}
	l.Resolve = func(name string) (net.IP, error) {
		ips, err := net.LookupIP(name)
		if err != nil {
			return nil, err
		}
		for _, ip := range ips {
			if v4 := ip.To4(); v4 != nil {
				return v4, nil
			}
		}
		return nil, fmt.Errorf("no IPv4 address for %s", name)
	}
	return l
}

func be32(v uint32) []byte { b := make([]byte, 4); binary.BigEndian.PutUint32(b, v); return b }

// ---- frames

func (l *Link) send(proto uint16, payload []byte) {
	f := Encode(proto, payload)
	l.wmu.Lock()
	defer l.wmu.Unlock()
	if _, err := l.rw.Write(f); err != nil {
		l.running.Store(false)
	}
}

func (l *Link) sendIP(pkt []byte) { l.send(IPV4, pkt) }

func (l *Link) ctl(proto uint16, code, ident byte, data []byte) {
	p := make([]byte, 4, 4+len(data))
	p[0], p[1] = code, ident
	binary.BigEndian.PutUint16(p[2:], uint16(4+len(data)))
	l.send(proto, append(p, data...))
}

func (l *Link) nextID() byte { l.ident++; return l.ident }

// ---- LCP / IPCP

type opt struct {
	t byte
	v []byte
}

func options(data []byte) []opt {
	var out []opt
	for i := 0; i+2 <= len(data); {
		t, n := data[i], int(data[i+1])
		if n < 2 || i+n > len(data) {
			break
		}
		out = append(out, opt{t, data[i+2 : i+n]})
		i += n
	}
	return out
}

func packOpts(opts []opt) []byte {
	var b []byte
	for _, o := range opts {
		b = append(b, o.t, byte(2+len(o.v)))
		b = append(b, o.v...)
	}
	return b
}

func (l *Link) sendLCPReq() {
	keys := make([]int, 0, len(l.ourLCP))
	for k := range l.ourLCP {
		keys = append(keys, int(k))
	}
	sort.Ints(keys)
	var opts []opt
	for _, k := range keys {
		opts = append(opts, opt{byte(k), l.ourLCP[byte(k)]})
	}
	l.ctl(LCP, 1, l.nextID(), packOpts(opts))
}

func (l *Link) sendIPCPReq() {
	l.ctl(IPCP, 1, l.nextID(), packOpts([]opt{{3, LocalIP[:]}}))
}

func (l *Link) onLCP(code, ident byte, data []byte) {
	switch code {
	case 1: // peer Configure-Request
		opts := options(data)
		var rej []opt
		for _, o := range opts {
			if o.t != 1 && o.t != 2 && o.t != 5 && o.t != 7 && o.t != 8 {
				rej = append(rej, o)
			}
		}
		if len(rej) > 0 {
			l.ctl(LCP, 4, ident, packOpts(rej))
			return
		}
		for _, o := range opts {
			if o.t == 1 && len(o.v) == 2 {
				l.peerMRU = int(binary.BigEndian.Uint16(o.v))
			}
		}
		l.ctl(LCP, 2, ident, data)
		l.lcpUp = true
		if !l.lcpAcked {
			l.sendLCPReq()
		}
	case 2: // our request acked
		l.lcpAcked = true
	case 3, 4: // nak/reject: drop those options, ask again
		for _, o := range options(data) {
			delete(l.ourLCP, o.t)
		}
		l.sendLCPReq()
	case 5: // Terminate-Request
		l.ctl(LCP, 6, ident, nil)
		l.Log("ppp: terminated by the TomTom")
		l.running.Store(false)
	case 9: // Echo-Request
		if len(data) >= 4 {
			l.ctl(LCP, 10, ident, append(be32(l.magic), data[4:]...))
		}
	}
	if l.lcpUp && l.lcpAcked && (code == 1 || code == 2) && !l.ipcpAcked {
		l.sendIPCPReq()
	}
}

func (l *Link) onIPCP(code, ident byte, data []byte) {
	switch code {
	case 1:
		opts := options(data)
		var rej, nak []opt
		want := map[byte][4]byte{3: PeerIP, 129: LocalIP, 131: LocalIP}
		for _, o := range opts {
			w, ok := want[o.t]
			if !ok {
				rej = append(rej, o)
			} else if string(o.v) != string(w[:]) {
				nak = append(nak, opt{o.t, w[:]})
			}
		}
		if len(rej) > 0 {
			l.ctl(IPCP, 4, ident, packOpts(rej))
			return
		}
		if len(nak) > 0 {
			l.ctl(IPCP, 3, ident, packOpts(nak))
			return
		}
		l.ctl(IPCP, 2, ident, data)
		if !l.ipcpPeerOK {
			l.ipcpPeerOK = true
			l.Log("ppp: up, TomTom =", ipStr(PeerIP))
		}
	case 2:
		l.ipcpAcked = true
	case 3, 4:
		l.sendIPCPReq()
	case 5:
		l.ctl(IPCP, 6, ident, nil)
	}
}

// ---- IP

func (l *Link) onIP(pkt []byte) {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return
	}
	ihl := int(pkt[0]&15) * 4
	total := int(binary.BigEndian.Uint16(pkt[2:]))
	if total > len(pkt) || ihl > total {
		return
	}
	var src, dst [4]byte
	copy(src[:], pkt[12:16])
	copy(dst[:], pkt[16:20])
	body := pkt[ihl:total]
	switch pkt[9] {
	case 1: // ICMP echo request
		if len(body) >= 4 && body[0] == 8 {
			rep := append([]byte{0, 0, 0, 0}, body[4:]...)
			binary.BigEndian.PutUint16(rep[2:], csum(rep))
			l.sendIP(ipPacket(dst, src, 1, rep))
		}
	case 17:
		if len(body) >= 8 && binary.BigEndian.Uint16(body[2:]) == 53 {
			go l.dns(src, binary.BigEndian.Uint16(body), dst, append([]byte(nil), body[8:]...))
		}
	case 6:
		if len(body) >= 20 {
			l.onTCP(src, dst, body)
		}
	}
}

// dns answers A queries with Resolve; other types get an empty answer.
func (l *Link) dns(src [4]byte, sport uint16, dst [4]byte, q []byte) {
	if len(q) < 12 {
		return
	}
	i := 12
	var labels []string
	for i < len(q) && q[i] != 0 {
		n := int(q[i])
		if i+1+n > len(q) {
			return
		}
		labels = append(labels, string(q[i+1:i+1+n]))
		i += 1 + n
	}
	if i+5 > len(q) {
		return
	}
	qtype := binary.BigEndian.Uint16(q[i+1:])
	question := q[12 : i+5]
	name := strings.Join(labels, ".")
	var ans []byte
	if qtype == 1 {
		if ip, err := l.Resolve(name); err == nil && ip.To4() != nil {
			ans = []byte{0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4}
			ans = append(ans, ip.To4()...)
		}
	}
	rcode := uint16(0)
	if len(ans) == 0 && qtype == 1 {
		rcode = 3
	}
	rep := make([]byte, 12)
	copy(rep, q[:2])
	binary.BigEndian.PutUint16(rep[2:], 0x8180|rcode)
	binary.BigEndian.PutUint16(rep[4:], 1)
	if len(ans) > 0 {
		binary.BigEndian.PutUint16(rep[6:], 1)
		l.Log("dns", name, "->", net.IP(ans[len(ans)-4:]).String())
	} else {
		l.Log("dns", name, "-> none")
	}
	rep = append(append(rep, question...), ans...)
	udp := make([]byte, 8, 8+len(rep))
	binary.BigEndian.PutUint16(udp[0:], 53)
	binary.BigEndian.PutUint16(udp[2:], sport)
	binary.BigEndian.PutUint16(udp[4:], uint16(8+len(rep)))
	udp = append(udp, rep...)
	c := l4csum(dst, src, 17, udp)
	if c == 0 {
		c = 0xffff
	}
	binary.BigEndian.PutUint16(udp[6:], c)
	l.sendIP(ipPacket(dst, src, 17, udp))
}

func (l *Link) onTCP(src, dst [4]byte, seg []byte) {
	sport, dport := binary.BigEndian.Uint16(seg[0:]), binary.BigEndian.Uint16(seg[2:])
	seq, ack := binary.BigEndian.Uint32(seg[4:]), binary.BigEndian.Uint32(seg[8:])
	off, flags, wnd := int(seg[12]>>4)*4, seg[13], int(binary.BigEndian.Uint16(seg[14:]))
	if off < 20 || off > len(seg) {
		return
	}
	data := seg[off:]
	key := connKey{src, sport, dst, dport}
	l.mu.Lock()
	c := l.tcp[key]
	if c == nil {
		if flags&SYN != 0 && flags&ACK == 0 {
			target := net.JoinHostPort(ipStr(dst), fmt.Sprint(dport))
			mss := max(256, min(1460, l.peerMRU-40))
			l.tcp[key] = newTCPConn(l, key, seq, target, mss)
			l.mu.Unlock()
			l.Log("tcp", ipStr(src), sport, "->", target)
			return
		}
		l.mu.Unlock()
		if flags&RST == 0 { // unknown connection: reset it
			rst := make([]byte, 20)
			binary.BigEndian.PutUint16(rst[0:], dport)
			binary.BigEndian.PutUint16(rst[2:], sport)
			binary.BigEndian.PutUint32(rst[4:], ack)
			binary.BigEndian.PutUint32(rst[8:], seq+uint32(len(data)))
			rst[12], rst[13] = 0x50, RST|ACK
			binary.BigEndian.PutUint16(rst[16:], l4csum(dst, src, 6, rst))
			l.sendIP(ipPacket(dst, src, 6, rst))
		}
		return
	}
	l.mu.Unlock()
	if flags&SYN != 0 { // retransmitted SYN: the timer resends the SYN-ACK
		return
	}
	c.onSegment(seq, ack, flags, wnd, data)
}

func (l *Link) dropConn(k connKey) {
	l.mu.Lock()
	delete(l.tcp, k)
	l.mu.Unlock()
}

func (l *Link) conns() []*tcpConn {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]*tcpConn, 0, len(l.tcp))
	for _, c := range l.tcp {
		out = append(out, c)
	}
	return out
}

// OpenConns is the number of relayed TCP connections still open.
func (l *Link) OpenConns() int { return len(l.conns()) }

// Run serves the link until the TomTom hangs up or the stream fails.
func (l *Link) Run() {
	go func() {
		t := time.NewTicker(500 * time.Millisecond)
		defer t.Stop()
		for {
			select {
			case <-l.done:
				return
			case <-t.C:
				for _, c := range l.conns() {
					c.retransmit()
				}
			}
		}
	}()
	defer close(l.done)
	l.sendLCPReq()
	var de Deframer
	buf := make([]byte, 4096)
	for l.running.Load() {
		n, err := l.rw.Read(buf)
		if n == 0 || err != nil {
			break
		}
		for _, f := range de.Feed(buf[:n]) {
			switch {
			case (f.Proto == LCP || f.Proto == IPCP) && len(f.Payload) >= 4:
				code, ident := f.Payload[0], f.Payload[1]
				end := int(binary.BigEndian.Uint16(f.Payload[2:]))
				if end < 4 || end > len(f.Payload) {
					end = len(f.Payload)
				}
				if f.Proto == LCP {
					l.onLCP(code, ident, f.Payload[4:end])
				} else {
					l.onIPCP(code, ident, f.Payload[4:end])
				}
			case f.Proto == IPV4:
				l.onIP(f.Payload)
			case l.lcpUp: // IPv6CP, CCP, ...: Protocol-Reject
				l.ctl(LCP, 8, l.nextID(), append([]byte{byte(f.Proto >> 8), byte(f.Proto)}, f.Payload...))
			}
		}
	}
	l.running.Store(false)
	for _, c := range l.conns() {
		c.mu.Lock()
		c.closeLocked()
		c.mu.Unlock()
	}
	l.Log("ppp: link closed")
}
