package services

// Hercules endpoint (hercules.py): decode a client batch, answer positionally (docs/traffic_schema.md section 5).

import (
	"errors"
	"fmt"
	"sort"
	"strconv"
	"sync"

	"golang.org/x/crypto/blowfish"
)

const (
	kindLogin, kindSubscribe, kindUnsubscribe, kindCall = 3, 6, 7, 0x10
	kindUpload, kindParamAck                            = 14, 0x13 // client parameter upload (FUN_00131fa4) and its acknowledgement
	slotIncidents                                       = 7
	herculesMax                                         = 100 // per fetch; the device is a 2009 handheld
)

var (
	loginKey       = []byte{0, 1, 2, 3, 4, 5, 6, 7} // the client only stores it; length must be a multiple of 8
	loginCipherKey = []byte("#p<l]u*s")             // static Blowfish key of the login payload and of the session-key unwrap (captured 2026-10-26)
)

func errorReply(code int64, text string) *Struct { return NewStruct(4, code, 5, text) }

// bboxFromArgs: args = payload tag 0x10: STRUCT { 3: LIST[ STRUCT {30,31,32,33: int} ] }
func bboxFromArgs(args any) *BBox {
	s, ok := args.(*Struct)
	if !ok {
		return nil
	}
	var r *Struct
	switch l := s.Get(3).(type) {
	case []any:
		if len(l) > 0 {
			r, _ = l[0].(*Struct)
		}
	}
	if r == nil {
		return nil
	}
	var bb BBox
	for k, t := range []byte{30, 31, 32, 33} {
		n, ok := pyIntOf(r.Get(t))
		if !ok {
			return nil
		}
		bb[k] = n
	}
	return &bb
}

func bfECB(key, data []byte, decrypt bool) ([]byte, error) {
	if len(key) < 4 { // as the Python cryptography package
		return nil, errors.New("invalid key size")
	}
	c, err := blowfish.NewCipher(key)
	if err != nil {
		return nil, err
	}
	if len(data)%8 != 0 {
		return nil, errors.New("data not a multiple of the block length")
	}
	out := make([]byte, len(data))
	for i := 0; i < len(data); i += 8 {
		if decrypt {
			c.Decrypt(out[i:i+8], data[i:i+8])
		} else {
			c.Encrypt(out[i:i+8], data[i:i+8])
		}
	}
	return out, nil
}

// sessionKey is the Blowfish key of later requests: BF-decrypt(static, loginKey) + TAB + serial + TAB + IMEI + SIMid,
// cut to 56 bytes, TAB padded to 8.
func sessionKey(lk, serial, imei, simid []byte) ([]byte, error) {
	d, err := bfECB(loginCipherKey, lk, true)
	if err != nil {
		return nil, err
	}
	k := append(append(append(append(append(d, '\t'), serial...), '\t'), imei...), simid...)
	if len(k) > 56 {
		k = k[:56]
	}
	for len(k)%8 != 0 {
		k = append(k, '\t')
	}
	return k, nil
}

// decodePayload decrypts a Blowfish-ECB, zero padded Hermes value (the tag-3 BUFFER of client requests).
func decodePayload(buf, key []byte) (any, error) {
	plain, err := bfECB(key, buf[:len(buf)/8*8], true)
	if err != nil {
		return nil, err
	}
	return Decode(plain)
}

// hashKey is the session map key of a Python-hashable value (True == 1, str and bytes differ).
func hashKey(v any) (string, bool) {
	switch x := v.(type) {
	case nil:
		return "n", true
	case bool:
		if x {
			return "i1", true
		}
		return "i0", true
	case int64:
		return "i" + strconv.FormatInt(x, 10), true
	case BigInt:
		return "i" + x.String(), true
	case string:
		return "s" + x, true
	case []byte:
		return "b" + string(x), true
	}
	return "", false // list / Struct: unhashable
}

func strOrBytes(v any) ([]byte, error) {
	switch x := v.(type) {
	case string:
		return []byte(x), nil
	case []byte:
		return x, nil
	}
	return nil, fmt.Errorf("can't concat %T to bytes", v)
}

type hercules struct {
	source   Source
	photon   PhotonFetch
	log      func(...any)
	mu       sync.Mutex
	sessions map[string][]byte // serial -> Blowfish key for later requests
}

func newHercules(src Source, photon PhotonFetch, log func(...any)) *hercules {
	return &hercules{source: src, photon: photon, log: log, sessions: map[string][]byte{}}
}

func (h *hercules) unwrap(kind, serial any, buf []byte) (any, error) {
	if pyEq(kind, kindLogin) {
		lp, err := decodePayload(buf, loginCipherKey) // {1: serial, 2: IMEI, 3: SIMid, 4: serial}
		if err != nil {
			return nil, err
		}
		s, ok := lp.(*Struct)
		if !ok {
			return nil, errors.New("login payload is not a struct")
		}
		f1, e1 := strOrBytes(s.Get(1))
		f2, e2 := strOrBytes(s.Get(2))
		f3, e3 := strOrBytes(s.Get(3))
		if e := errors.Join(e1, e2, e3); e != nil {
			return nil, e
		}
		k, err := sessionKey(loginKey, f1, f2, f3)
		if err != nil {
			return nil, err
		}
		hk, ok := hashKey(serial)
		if !ok {
			return nil, errors.New("unhashable serial")
		}
		h.mu.Lock()
		h.sessions[hk] = k
		h.mu.Unlock()
		return lp, nil
	}
	hk, ok := hashKey(serial)
	if !ok {
		return nil, errors.New("unhashable serial")
	}
	h.mu.Lock()
	key, have := h.sessions[hk]
	h.mu.Unlock()
	if !have {
		return buf, nil
	}
	p, err := decodePayload(buf, key)
	if err != nil {
		return nil, err
	}
	h.log("hercules request", pyRepr(kind), truncRunes(pyRepr(p), 600))
	return p, nil
}

func (h *hercules) handleMessage(m any) any {
	msg, ok := m.(*Struct)
	if !ok {
		return errorReply(9, "bad message")
	}
	kind, payload, serial := msg.Get(1), msg.Get(3), msg.Get(2)
	if buf, ok := payload.([]byte); ok { // real devices send the payload as an encrypted BUFFER
		if p, err := h.unwrap(kind, serial, buf); err != nil {
			h.log("hercules payload decrypt failed", err)
		} else {
			payload = p
		}
	}
	ps, _ := payload.(*Struct)
	switch {
	case pyEq(kind, kindLogin):
		return NewStruct(1, kindLogin, 6, NewStruct(0x12, loginKey))
	case pyEq(kind, kindSubscribe) || pyEq(kind, kindUnsubscribe):
		return NewStruct(1, 1)
	case pyEq(kind, kindCall):
		var slot, args any
		if ps != nil {
			slot, args = ps.Get(5), ps.Get(0x10)
		}
		if slot == nil || pyEq(slot, slotIncidents) {
			bb := bboxFromArgs(args)
			incs := h.source(bb)
			if bb != nil && len(incs) > herculesMax { // keep the nearest to the middle of the view
				cx, cy := float64(bb[0]+bb[2])/2e5, float64(bb[1]+bb[3])/2e5
				d := func(i *Incident) float64 {
					dx, dy := i.Lon-cx, i.Lat-cy
					return float64(dx*dx) + float64(dy*dy)
				}
				incs = append([]Incident(nil), incs...)
				sort.SliceStable(incs, func(a, b int) bool { return d(&incs[a]) < d(&incs[b]) })
				incs = incs[:herculesMax]
			}
			h.log("hercules fetch", pyRepr(slot), len(incs))
			return NewStruct(1, kindCall, 6, trafficPayload(int64(slotIncidents), incs))
		}
		if pyEq(slot, localSearchSlot) {
			h.log("hercules localsearch", pyRepr(args))
			return localSearch(args, h.photon, h.log)
		}
		// road conditions and anything else: acknowledge with an empty payload
		return NewStruct(1, kindCall, 6, NewStruct(5, slot))
	case pyEq(kind, kindUpload):
		// client parameter upload (FUN_00129734): {8: index, 9: value}; 2 location, 3 language, 4 device name,
		// 5 app version, 6 map version, 7 map name, 8 operator id, 9 current country (CClientParameter*).
		// Ack = message kind 0x13 (FUN_001292f8): tag 8 index, 0x13 -> item+0xc, 0x14 != 0 -> item done (no resend).
		var idx any
		if ps != nil {
			idx = ps.Get(8)
		}
		h.log("hercules upload", truncRunes(pyRepr(payload), 200))
		switch idx.(type) {
		case int64, bool, BigInt:
		default:
			return errorReply(9, "bad parameter upload")
		}
		return NewStruct(1, kindParamAck, 8, idx, 0x13, true, 0x14, true) // tags 0x13/0x14 are bool in the schema table
	}
	return errorReply(9, "unknown kind "+pyRepr(kind))
}

// handleBody answers one POST body. A panic in a source counts as an error, as the Python exception did.
func (h *hercules) handleBody(body []byte) (out []byte, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("panic: %v", r)
		}
	}()
	msg, err := UnpackBody(body)
	if err != nil {
		return nil, err
	}
	var reply any
	if l, ok := msg.([]any); ok {
		rs := make([]any, len(l))
		for i := range l {
			rs[i] = h.handleMessage(l[i])
		}
		reply = rs
	} else {
		reply = h.handleMessage(msg)
	}
	return PackBody(reply)
}
