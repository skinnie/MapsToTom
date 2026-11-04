package services

// TomTom Hermes value codec as used by the GO 950 (NavCore 9.541) 'ttn' binary (port of server/hermes.py).
// Derived from decompilation: decoder FUN_00134b24, encoder FUN_001361a4.
// A Hercules body is: 1 flag byte (0x00 raw, 0x10 zlib) + a Hermes value.
//
// Go values: nil, bool, int64 (int is accepted when encoding), BigInt, string, []byte (BUFFER), []any (LIST),
// *Struct (STRUCT). STRUCT children are (tag, value) pairs, each tag a single byte before its value; LIST children
// have no tag.

import (
	"bytes"
	"compress/zlib"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
)

const maxStr = 0x3FFF

// Struct is an ordered tag -> value mapping.
type Struct struct{ Items []Item }

// Item is one child of a Struct.
type Item struct {
	Tag byte
	Val any
}

// NewStruct builds a Struct from tag, value, tag, value, ...
func NewStruct(kv ...any) *Struct {
	s := &Struct{}
	for i := 0; i+1 < len(kv); i += 2 {
		s.Items = append(s.Items, Item{byte(toInt(kv[i])), kv[i+1]})
	}
	return s
}

func toInt(v any) int64 {
	switch x := v.(type) {
	case int:
		return int64(x)
	case int64:
		return x
	case byte:
		return int64(x)
	}
	panic("hermes: tag is not an int")
}

// Get returns the value of the first child with this tag, or nil.
func (s *Struct) Get(tag byte) any {
	for _, it := range s.Items {
		if it.Tag == tag {
			return it.Val
		}
	}
	return nil
}

// BigInt is an integer whose magnitude does not fit int64 (8-byte values up to 2^64-1).
type BigInt struct {
	Neg bool
	Mag uint64
}

func (b BigInt) String() string {
	s := strconv.FormatUint(b.Mag, 10)
	if b.Neg && b.Mag != 0 {
		s = "-" + s
	}
	return s
}

func encInt(neg bool, m uint64) []byte {
	size := 8
	switch {
	case m < 0x100:
		size = 1
	case m < 0x10000:
		size = 2
	case m < 0x80000000:
		size = 4
	}
	base := map[int]byte{1: 0xC2, 2: 0xC4, 4: 0xC6, 8: 0xC8}[size]
	if neg {
		base |= 1
	}
	out := []byte{base}
	for i := size - 1; i >= 0; i-- {
		out = append(out, byte(m>>(8*uint(i))))
	}
	return out
}

func encCount(typeBits byte, n int) ([]byte, error) {
	if n <= 7 {
		return []byte{typeBits | byte(n)}, nil
	}
	if n > 0x7FF {
		return nil, errors.New("too many children")
	}
	return []byte{typeBits | byte(n>>8) | 8, byte(n)}, nil
}

// Encode serialises one value.
func Encode(v any) ([]byte, error) {
	var b bytes.Buffer
	if err := encode(&b, v); err != nil {
		return nil, err
	}
	return b.Bytes(), nil
}

func encode(b *bytes.Buffer, v any) error {
	switch x := v.(type) {
	case nil:
		b.WriteByte(0x0F)
	case bool:
		if x {
			b.WriteByte(0xC1)
		} else {
			b.WriteByte(0xC0)
		}
	case int:
		return encode(b, int64(x))
	case int64:
		if x < 0 {
			b.Write(encInt(true, uint64(-(x+1))+1))
		} else {
			b.Write(encInt(false, uint64(x)))
		}
	case BigInt:
		b.Write(encInt(x.Neg && x.Mag != 0, x.Mag))
	case string:
		s := []byte(x)
		if len(s) > maxStr {
			s = s[:maxStr]
		}
		b.Write([]byte{0x40 | byte(len(s)>>8), byte(len(s))})
		b.Write(s)
	case []byte:
		if len(x) > maxStr {
			return errors.New("buffer too long")
		}
		b.Write([]byte{0x80 | byte(len(x)>>8), byte(len(x))})
		b.Write(x)
	case []any:
		h, err := encCount(0x20, len(x))
		if err != nil {
			return err
		}
		b.Write(h)
		for _, e := range x {
			if err := encode(b, e); err != nil {
				return err
			}
		}
	case *Struct:
		h, err := encCount(0x10, len(x.Items))
		if err != nil {
			return err
		}
		b.Write(h)
		for _, it := range x.Items {
			b.WriteByte(it.Tag)
			if err := encode(b, it.Val); err != nil {
				return err
			}
		}
	default:
		return fmt.Errorf("hermes: cannot encode %T", v)
	}
	return nil
}

var errTrunc = errors.New("index out of range")

// maxDepth stands in for Python's recursion limit on hostile input.
const maxDepth = 900

func dec(d []byte, p int, tagged bool, depth int) (byte, any, int, error) {
	if depth > maxDepth {
		return 0, nil, p, errors.New("maximum recursion depth exceeded")
	}
	var tag byte
	if tagged {
		if p >= len(d) {
			return 0, nil, p, errTrunc
		}
		tag = d[p]
		p++
	}
	if p >= len(d) {
		return 0, nil, p, errTrunc
	}
	b := d[p]
	switch b & 0xC0 {
	case 0x40, 0x80:
		if p+1 >= len(d) {
			return 0, nil, p, errTrunc
		}
		n := int(b&0x3F)<<8 | int(d[p+1])
		if p+2+n > len(d) {
			if b&0xC0 == 0x40 {
				return 0, nil, p, errors.New("truncated string")
			}
			return 0, nil, p, errors.New("truncated buffer")
		}
		s := d[p+2 : p+2+n]
		if b&0xC0 == 0x40 {
			return tag, pyDecodeUTF8(s), p + 2 + n, nil
		}
		return tag, append([]byte(nil), s...), p + 2 + n, nil
	case 0x00:
		if b == 0x0F {
			return tag, nil, p + 1, nil
		}
		t := b & 0x30
		if t != 0x10 && t != 0x20 {
			return 0, nil, p, fmt.Errorf("bad type byte 0x%02x", b)
		}
		var n int
		if b&8 != 0 {
			if p+1 >= len(d) {
				return 0, nil, p, errTrunc
			}
			n = int(b&7)<<8 | int(d[p+1])
			p += 2
		} else {
			n = int(b & 7)
			p++
		}
		if t == 0x10 {
			s := &Struct{}
			for i := 0; i < n; i++ {
				tg, v, np, err := dec(d, p, true, depth+1)
				if err != nil {
					return 0, nil, np, err
				}
				p = np
				s.Items = append(s.Items, Item{tg, v})
			}
			return tag, s, p, nil
		}
		l := make([]any, 0, n)
		for i := 0; i < n; i++ {
			_, v, np, err := dec(d, p, false, depth+1)
			if err != nil {
				return 0, nil, np, err
			}
			p = np
			l = append(l, v)
		}
		return tag, l, p, nil
	}
	kind, neg := b&0xFE, b&1 != 0
	if kind == 0xC0 {
		return tag, b == 0xC1, p + 1, nil
	}
	size := map[byte]int{0xC2: 1, 0xC4: 2, 0xC6: 4, 0xC8: 8}[kind]
	if size == 0 {
		return 0, nil, p, fmt.Errorf("bad type byte 0x%02x", b)
	}
	if p+1+size > len(d) {
		return 0, nil, p, errors.New("truncated int")
	}
	var m uint64
	for _, c := range d[p+1 : p+1+size] {
		m = m<<8 | uint64(c)
	}
	p += 1 + size
	if m > 1<<63-1 {
		if neg && m == 1<<63 {
			return tag, int64(-1 << 63), p, nil
		}
		return tag, BigInt{neg, m}, p, nil
	}
	if neg {
		return tag, -int64(m), p, nil
	}
	return tag, int64(m), p, nil
}

// Decode reads one value from the start of data (trailing bytes are ignored, as in hermes.py).
func Decode(data []byte) (any, error) {
	_, v, _, err := dec(data, 0, false, 0)
	return v, err
}

// PackBody is the Hercules body of a value: 0x10 + zlib when that is shorter, else 0x00 + raw.
// Go's deflate is not zlib's, so compressed bodies differ in bytes from hermes.py (same content).
func PackBody(msg any) ([]byte, error) {
	raw, err := Encode(msg)
	if err != nil {
		return nil, err
	}
	var z bytes.Buffer
	w := zlib.NewWriter(&z)
	w.Write(raw)
	w.Close()
	if z.Len() < len(raw) {
		return append([]byte{0x10}, z.Bytes()...), nil
	}
	return append([]byte{0x00}, raw...), nil
}

const maxInflate = 16 << 20

// UnpackBody decodes a Hercules body.
func UnpackBody(body []byte) (any, error) {
	if len(body) == 0 {
		return nil, errTrunc
	}
	switch body[0] >> 4 {
	case 0:
		return Decode(body[1:])
	case 1:
		r, err := zlib.NewReader(bytes.NewReader(body[1:]))
		if err != nil {
			return nil, err
		}
		raw, err := io.ReadAll(io.LimitReader(r, maxInflate))
		if err != nil {
			return nil, err
		}
		return Decode(raw)
	}
	return nil, fmt.Errorf("unknown compression algorithm %d", body[0]>>4)
}

// show is mockserver.show(): the JSON form of a decoded value in requests.jsonl.
func show(v any) any {
	switch x := v.(type) {
	case *Struct:
		items := make([]any, len(x.Items))
		for i, it := range x.Items {
			items[i] = []any{int64(it.Tag), show(it.Val)}
		}
		return jobj{{"struct", items}}
	case []any:
		out := make([]any, len(x))
		for i := range x {
			out[i] = show(x[i])
		}
		return out
	case []byte:
		return fmt.Sprintf("bytes:%x", x)
	}
	return v
}

// pyRepr is Python's repr() of a decoded value (used in error texts).
func pyRepr(v any) string {
	switch x := v.(type) {
	case nil:
		return "None"
	case bool:
		if x {
			return "True"
		}
		return "False"
	case int64:
		return strconv.FormatInt(x, 10)
	case int:
		return strconv.Itoa(x)
	case BigInt:
		return x.String()
	case string:
		return reprStr(x)
	case []byte:
		return "b" + reprBytes(x)
	case []any:
		parts := make([]string, len(x))
		for i := range x {
			parts[i] = pyRepr(x[i])
		}
		return "[" + strings.Join(parts, ", ") + "]"
	case *Struct:
		parts := make([]string, len(x.Items))
		for i, it := range x.Items {
			parts[i] = "(" + strconv.Itoa(int(it.Tag)) + ", " + pyRepr(it.Val) + ")"
		}
		return "Struct([" + strings.Join(parts, ", ") + "])"
	}
	return fmt.Sprint(v)
}

func reprQuote(hasSingle, hasDouble bool) byte {
	if hasSingle && !hasDouble {
		return '"'
	}
	return '\''
}

func reprStr(s string) string {
	q := reprQuote(strings.Contains(s, "'"), strings.Contains(s, `"`))
	var sb strings.Builder
	sb.WriteByte(q)
	for _, r := range s {
		switch {
		case r == rune(q) || r == '\\':
			sb.WriteByte('\\')
			sb.WriteRune(r)
		case r == '\t':
			sb.WriteString(`\t`)
		case r == '\n':
			sb.WriteString(`\n`)
		case r == '\r':
			sb.WriteString(`\r`)
		case r < 0x20 || r == 0x7f:
			fmt.Fprintf(&sb, `\x%02x`, r)
		case r < 0x7f || isPrint(r):
			sb.WriteRune(r)
		case r < 0x100:
			fmt.Fprintf(&sb, `\x%02x`, r)
		case r < 0x10000:
			fmt.Fprintf(&sb, `\u%04x`, r)
		default:
			fmt.Fprintf(&sb, `\U%08x`, r)
		}
	}
	sb.WriteByte(q)
	return sb.String()
}

func reprBytes(b []byte) string {
	q := reprQuote(bytes.IndexByte(b, '\'') >= 0, bytes.IndexByte(b, '"') >= 0)
	var sb strings.Builder
	sb.WriteByte(q)
	for _, c := range b {
		switch {
		case c == q || c == '\\':
			sb.WriteByte('\\')
			sb.WriteByte(c)
		case c == '\t':
			sb.WriteString(`\t`)
		case c == '\n':
			sb.WriteString(`\n`)
		case c == '\r':
			sb.WriteString(`\r`)
		case c < 0x20 || c >= 0x7f:
			fmt.Fprintf(&sb, `\x%02x`, c)
		default:
			sb.WriteByte(c)
		}
	}
	sb.WriteByte(q)
	return sb.String()
}
