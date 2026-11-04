package ppp

import (
	"encoding/binary"
	"net"
	"sync"
	"time"
)

// TCP flags
const (
	FIN = 0x01
	SYN = 0x02
	RST = 0x04
	PSH = 0x08
	ACK = 0x10
)

type connKey struct {
	peerIP   [4]byte
	peerPort uint16
	ourIP    [4]byte
	ourPort  uint16
}

// tcpConn is one TomTom-initiated TCP connection relayed to an upstream net.Conn. In-order data only:
// out-of-order segments are dropped and the TomTom retransmits. One retransmit timer, window from the peer.
type tcpConn struct {
	link                *Link
	key                 connKey
	mu                  sync.Mutex
	rcvNxt, iss         uint32
	sndUna, sndNxt      uint32
	out                 []byte // bytes from upstream not yet acked by the peer (from sndUna)
	peerWnd, mss        int
	finIn, finOutSent   bool
	upstreamEOF, closed bool
	lastTx              time.Time
	up                  net.Conn
	target              string
}

func newTCPConn(l *Link, key connKey, isnPeer uint32, target string, mss int) *tcpConn {
	iss := uint32(time.Now().UnixMilli())
	c := &tcpConn{link: l, key: key, rcvNxt: isnPeer + 1, iss: iss, sndUna: iss, sndNxt: iss,
		peerWnd: 4096, mss: mss, target: target}
	go c.connectUpstream()
	return c
}

func (c *tcpConn) synOpts() []byte {
	return []byte{2, 4, byte(c.mss >> 8), byte(c.mss)}
}

func (c *tcpConn) connectUpstream() {
	up, err := c.link.Dial(c.target)
	if err != nil {
		c.link.Log("tcp", c.target, "upstream failed:", err)
		c.mu.Lock()
		c.send(RST|ACK, nil, c.iss, nil)
		c.closeLocked()
		c.mu.Unlock()
		return
	}
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		up.Close()
		return
	}
	c.up = up
	c.send(SYN|ACK, nil, c.iss, c.synOpts())
	c.sndNxt = c.iss + 1
	c.lastTx = time.Now()
	c.mu.Unlock()
	buf := make([]byte, 16384)
	for {
		n, err := up.Read(buf)
		c.mu.Lock()
		if n > 0 {
			c.out = append(c.out, buf[:n]...)
		}
		if err != nil {
			c.upstreamEOF = true
		}
		c.push()
		c.mu.Unlock()
		if err != nil {
			return
		}
	}
}

func (c *tcpConn) onSegment(seq, ack uint32, flags byte, wnd int, data []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if flags&RST != 0 {
		c.closeLocked()
		return
	}
	if flags&ACK != 0 {
		acked := ack - c.sndUna
		inflight := c.sndNxt - c.sndUna
		if acked > 0 && acked <= inflight {
			if c.sndUna == c.iss { // our SYN
				acked--
				c.sndUna++
			}
			n := min(int(acked), len(c.out))
			c.out = c.out[n:]
			c.sndUna += uint32(n)
			if int(acked) > n && c.finOutSent { // our FIN was acked
				c.sndUna++
				if c.finIn {
					c.closeLocked()
				}
			}
		}
		c.peerWnd = wnd
	}
	if len(data) > 0 || flags&FIN != 0 {
		if seq == c.rcvNxt {
			if len(data) > 0 && c.up != nil {
				c.up.Write(data)
			}
			c.rcvNxt += uint32(len(data))
			if flags&FIN != 0 && !c.finIn {
				c.finIn = true
				c.rcvNxt++
				if cw, ok := c.up.(interface{ CloseWrite() error }); ok {
					cw.CloseWrite()
				}
			}
		}
		// in order or not: (re)acknowledge what we have
		c.send(ACK, nil, c.sndNxt, nil)
		if c.finIn && c.finOutSent && c.sndUna == c.sndNxt {
			c.closeLocked()
		}
	}
	c.push()
}

// push sends what the peer window allows, then FIN after the last byte once upstream closed. Lock held.
func (c *tcpConn) push() {
	if c.closed || (c.sndUna == c.iss && c.sndNxt == c.iss) || c.finOutSent {
		return
	}
	base := c.sndUna
	if c.sndUna == c.iss {
		base++
	}
	sent := int(c.sndNxt - base)
	for sent < len(c.out) && sent < c.peerWnd {
		end := sent + min(c.mss, c.peerWnd-sent)
		if end > len(c.out) {
			end = len(c.out)
		}
		chunk := c.out[sent:end]
		c.send(ACK|PSH, chunk, base+uint32(sent), nil)
		sent += len(chunk)
		c.sndNxt = base + uint32(sent)
		c.lastTx = time.Now()
	}
	if c.upstreamEOF && sent == len(c.out) {
		c.send(FIN|ACK, nil, c.sndNxt, nil)
		c.sndNxt++
		c.finOutSent = true
		c.lastTx = time.Now()
	}
}

// retransmit goes back to sndUna if nothing was acked for 1.5 s.
func (c *tcpConn) retransmit() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.sndUna == c.sndNxt || time.Since(c.lastTx) < 1500*time.Millisecond {
		return
	}
	if c.sndUna == c.iss { // SYN-ACK lost
		c.send(SYN|ACK, nil, c.iss, c.synOpts())
		c.lastTx = time.Now()
		return
	}
	c.sndNxt = c.sndUna
	if c.finOutSent && len(c.out) == 0 {
		c.send(FIN|ACK, nil, c.sndUna, nil)
		c.sndNxt = c.sndUna + 1
		c.lastTx = time.Now()
		return
	}
	c.finOutSent = false
	c.push()
}

func (c *tcpConn) send(flags byte, data []byte, seq uint32, opts []byte) {
	hlen := 20 + len(opts)
	seg := make([]byte, hlen, hlen+len(data))
	binary.BigEndian.PutUint16(seg[0:], c.key.ourPort)
	binary.BigEndian.PutUint16(seg[2:], c.key.peerPort)
	binary.BigEndian.PutUint32(seg[4:], seq)
	if flags&ACK != 0 {
		binary.BigEndian.PutUint32(seg[8:], c.rcvNxt)
	}
	seg[12] = byte(hlen/4) << 4
	seg[13] = flags
	binary.BigEndian.PutUint16(seg[14:], 65535)
	copy(seg[20:], opts)
	seg = append(seg, data...)
	binary.BigEndian.PutUint16(seg[16:], l4csum(c.key.ourIP, c.key.peerIP, 6, seg))
	c.link.sendIP(ipPacket(c.key.ourIP, c.key.peerIP, 6, seg))
}

func (c *tcpConn) closeLocked() {
	if c.closed {
		return
	}
	c.closed = true
	if c.up != nil {
		c.up.Close()
	}
	c.link.dropConn(c.key)
}
