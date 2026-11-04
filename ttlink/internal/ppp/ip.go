package ppp

import (
	"encoding/binary"
	"sync/atomic"
)

func csum(data []byte) uint16 {
	var s uint32
	for i := 0; i+1 < len(data); i += 2 {
		s += uint32(data[i])<<8 | uint32(data[i+1])
	}
	if len(data)%2 == 1 {
		s += uint32(data[len(data)-1]) << 8
	}
	for s>>16 != 0 {
		s = s&0xffff + s>>16
	}
	return ^uint16(s)
}

var ipIdent atomic.Uint32

func ipPacket(src, dst [4]byte, proto byte, payload []byte) []byte {
	h := make([]byte, 20, 20+len(payload))
	h[0] = 0x45
	binary.BigEndian.PutUint16(h[2:], uint16(20+len(payload)))
	binary.BigEndian.PutUint16(h[4:], uint16(ipIdent.Add(1)))
	binary.BigEndian.PutUint16(h[6:], 0x4000)
	h[8], h[9] = 64, proto
	copy(h[12:], src[:])
	copy(h[16:], dst[:])
	binary.BigEndian.PutUint16(h[10:], csum(h))
	return append(h, payload...)
}

func l4csum(src, dst [4]byte, proto byte, seg []byte) uint16 {
	p := make([]byte, 0, 12+len(seg))
	p = append(p, src[:]...)
	p = append(p, dst[:]...)
	p = append(p, 0, proto, byte(len(seg)>>8), byte(len(seg)))
	return csum(append(p, seg...))
}
