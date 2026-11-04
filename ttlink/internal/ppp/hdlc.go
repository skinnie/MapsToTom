package ppp

// HDLC-like framing (RFC 1662).

var fcsTable = func() (t [256]uint16) {
	for b := 0; b < 256; b++ {
		v := uint16(b)
		for i := 0; i < 8; i++ {
			if v&1 != 0 {
				v = v>>1 ^ 0x8408
			} else {
				v >>= 1
			}
		}
		t[b] = v
	}
	return
}()

func fcs16(data []byte) uint16 {
	fcs := uint16(0xffff)
	for _, b := range data {
		fcs = fcs>>8 ^ fcsTable[(fcs^uint16(b))&0xff]
	}
	return fcs
}

// Encode builds one frame: full address/control and 2-byte protocol (always allowed); escapes 0x7e, 0x7d
// and all control characters.
func Encode(proto uint16, payload []byte) []byte {
	body := make([]byte, 0, len(payload)+6)
	body = append(body, 0xff, 0x03, byte(proto>>8), byte(proto))
	body = append(body, payload...)
	f := fcs16(body) ^ 0xffff
	body = append(body, byte(f), byte(f>>8))
	out := make([]byte, 0, len(body)*2+2)
	out = append(out, 0x7e)
	for _, b := range body {
		if b == 0x7e || b == 0x7d || b < 0x20 {
			out = append(out, 0x7d, b^0x20)
		} else {
			out = append(out, b)
		}
	}
	return append(out, 0x7e)
}

// Frame is one received PPP frame.
type Frame struct {
	Proto   uint16
	Payload []byte
}

// Deframer turns bytes into frames. Frames with a bad FCS are dropped. Compressed address/control and
// protocol fields are accepted.
type Deframer struct {
	buf []byte
	esc bool
}

func (d *Deframer) Feed(data []byte) []Frame {
	var out []Frame
	for _, b := range data {
		switch {
		case b == 0x7e:
			if f, ok := d.finish(); ok {
				out = append(out, f)
			}
		case b == 0x7d:
			d.esc = true
		default:
			if d.esc {
				b ^= 0x20
			}
			d.buf = append(d.buf, b)
			d.esc = false
		}
	}
	return out
}

func (d *Deframer) finish() (Frame, bool) {
	f := d.buf
	d.buf, d.esc = nil, false
	if len(f) < 4 || fcs16(f) != 0xf0b8 {
		return Frame{}, false
	}
	f = f[:len(f)-2]
	if len(f) >= 2 && f[0] == 0xff && f[1] == 0x03 {
		f = f[2:]
	}
	if len(f) == 0 {
		return Frame{}, false
	}
	if f[0]&1 != 0 { // compressed 1-byte protocol
		return Frame{uint16(f[0]), f[1:]}, true
	}
	if len(f) < 2 {
		return Frame{}, false
	}
	return Frame{uint16(f[0])<<8 | uint16(f[1]), f[2:]}, true
}
