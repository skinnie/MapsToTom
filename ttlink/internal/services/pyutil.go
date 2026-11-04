package services

// Helpers that reproduce Python behaviour the replies depend on (rounding, modulo, parse_qs, json.dumps, ...).

import (
	"encoding/json"
	"math"
	"math/big"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// pyRound is Python's round(x) for floats: half to even.
func pyRound(x float64) int64 { return int64(math.RoundToEven(x)) }

// pyMod is Python's % for ints (result has the divisor's sign).
func pyMod(a, b int64) int64 {
	m := a % b
	if m != 0 && (m < 0) != (b < 0) {
		m += b
	}
	return m
}

// pyInt parses like Python int(str): surrounding whitespace, sign, '_' between digits.
func pyInt(s string) (int64, bool) {
	s = strings.TrimFunc(s, unicode.IsSpace)
	t := s
	if t != "" && (t[0] == '+' || t[0] == '-') {
		t = t[1:]
	}
	if t == "" || t[0] == '_' || t[len(t)-1] == '_' || strings.Contains(t, "__") {
		return 0, false
	}
	n, err := strconv.ParseInt(strings.ReplaceAll(s, "_", ""), 10, 64)
	return n, err == nil
}

// truncRunes is Python's s[:n] on a str.
func truncRunes(s string, n int) string {
	i := 0
	for k := range s {
		if i == n {
			return s[:k]
		}
		i++
	}
	return s
}

// latin1 turns raw request bytes into the str Python's http.server sees (it decodes the request line as ISO-8859-1).
func latin1(s string) string {
	for i := 0; i < len(s); i++ {
		if s[i] >= 0x80 {
			r := make([]rune, len(s))
			for k := 0; k < len(s); k++ {
				r[k] = rune(s[k])
			}
			return string(r)
		}
	}
	return s
}

// pyDecodeUTF8 is bytes.decode("utf-8", "replace"): one U+FFFD per maximal invalid subpart, like CPython.
func pyDecodeUTF8(b []byte) string {
	if utf8.Valid(b) {
		return string(b)
	}
	var sb strings.Builder
	for i := 0; i < len(b); {
		r, n := utf8.DecodeRune(b[i:])
		if r != utf8.RuneError || n > 1 {
			sb.WriteRune(r)
			i += n
			continue
		}
		c := b[i]
		need, lo, hi := 0, byte(0x80), byte(0xBF)
		switch {
		case c >= 0xC2 && c <= 0xDF:
			need = 1
		case c == 0xE0:
			need, lo = 2, 0xA0
		case c == 0xED:
			need, hi = 2, 0x9F
		case c >= 0xE1 && c <= 0xEF:
			need = 2
		case c == 0xF0:
			need, lo = 3, 0x90
		case c == 0xF4:
			need, hi = 3, 0x8F
		case c >= 0xF1 && c <= 0xF3:
			need = 3
		}
		j := i + 1
		for k := 0; k < need && j < len(b); k++ {
			l, h := byte(0x80), byte(0xBF)
			if k == 0 {
				l, h = lo, hi
			}
			if b[j] < l || b[j] > h {
				break
			}
			j++
		}
		sb.WriteRune(utf8.RuneError)
		i = j
	}
	return sb.String()
}

// unquote is urllib.parse.unquote (utf-8, errors="replace").
func unquote(s string) string {
	if !strings.Contains(s, "%") {
		return s
	}
	var sb strings.Builder
	var pend []byte
	flush := func() {
		if len(pend) > 0 {
			sb.WriteString(pyDecodeUTF8(pend))
			pend = pend[:0]
		}
	}
	for i := 0; i < len(s); {
		if s[i] == '%' && i+2 < len(s) && isHex(s[i+1]) && isHex(s[i+2]) {
			v, _ := strconv.ParseUint(s[i+1:i+3], 16, 8)
			pend = append(pend, byte(v))
			i += 3
			continue
		}
		flush()
		r, n := utf8.DecodeRuneInString(s[i:])
		sb.WriteRune(r)
		i += n
	}
	flush()
	return sb.String()
}

func isHex(c byte) bool {
	return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'
}

type qpair struct{ k, v string }

// parseQS is urllib.parse.parse_qs reduced to the first value of each name, in first-seen order.
func parseQS(q string, keepBlank bool) []qpair {
	var out []qpair
	seen := map[string]bool{}
	for _, nv := range strings.Split(q, "&") {
		if nv == "" {
			continue
		}
		name, val, ok := strings.Cut(nv, "=")
		if !ok && !keepBlank {
			continue
		}
		if val == "" && !keepBlank {
			continue
		}
		name = unquote(strings.ReplaceAll(name, "+", " "))
		val = unquote(strings.ReplaceAll(val, "+", " "))
		if !seen[name] {
			seen[name] = true
			out = append(out, qpair{name, val})
		}
	}
	return out
}

func qget(qs []qpair, k string) (string, bool) {
	for _, p := range qs {
		if p.k == k {
			return p.v, true
		}
	}
	return "", false
}

// degToRad is CPython's Py_MATH_PI / 180.0, rounded in float64 (a Go constant division would round once, exactly).
var degToRad = func() float64 { pi := math.Pi; return pi / 180.0 }()

// crCos is cos(x) correctly rounded, which is what glibc (Python's math.cos) returns; Go's math.Cos can be 1 ulp off.
func crCos(x float64) float64 {
	if math.IsNaN(x) || math.IsInf(x, 0) || math.Abs(x) > 8 {
		return math.Cos(x)
	}
	const prec = 160
	bx := new(big.Float).SetPrec(prec).SetFloat64(x)
	x2 := new(big.Float).SetPrec(prec).Mul(bx, bx)
	sum := new(big.Float).SetPrec(prec).SetInt64(1)
	term := new(big.Float).SetPrec(prec).SetInt64(1)
	eps := new(big.Float).SetPrec(prec).SetMantExp(big.NewFloat(1), -150)
	for n := int64(1); n < 200; n++ {
		term.Mul(term, x2)
		term.Quo(term, new(big.Float).SetPrec(prec).SetInt64((2*n-1)*(2*n)))
		term.Neg(term)
		sum.Add(sum, term)
		if new(big.Float).Abs(term).Cmp(eps) < 0 {
			break
		}
	}
	f, _ := sum.Float64()
	return f
}

// crHypot is sqrt(a*a + b*b) correctly rounded, like CPython 3.10+ math.hypot.
func crHypot(a, b float64) float64 {
	if math.IsNaN(a) || math.IsNaN(b) || math.IsInf(a, 0) || math.IsInf(b, 0) {
		return math.Hypot(a, b)
	}
	const prec = 2200
	ba := new(big.Float).SetPrec(prec).SetFloat64(a)
	bb := new(big.Float).SetPrec(prec).SetFloat64(b)
	ba.Mul(ba, ba)
	bb.Mul(bb, bb)
	ba.Add(ba, bb)
	ba.SetPrec(200).Sqrt(ba)
	f, _ := ba.Float64()
	return f
}

// pyFloatRepr is Python's repr(float).
func pyFloatRepr(f float64) string {
	switch {
	case math.IsNaN(f):
		return "NaN"
	case math.IsInf(f, 1):
		return "Infinity"
	case math.IsInf(f, -1):
		return "-Infinity"
	}
	e := strconv.FormatFloat(f, 'e', -1, 64) // d.ddde±XX
	mant, exps, _ := strings.Cut(e, "e")
	exp, _ := strconv.Atoi(exps)
	if exp < -4 || exp >= 16 {
		return mant + "e" + exps // Go and Python both write at least two exponent digits
	}
	s := strconv.FormatFloat(f, 'f', -1, 64)
	if !strings.ContainsAny(s, ".") {
		s += ".0"
	}
	return s
}

// ---- json.dumps (default separators, ensure_ascii) for the request logs

type jkv struct {
	k string
	v any
}

type jobj []jkv

func jsonDumps(v any) string {
	var sb strings.Builder
	jsonWrite(&sb, v)
	return sb.String()
}

func jsonWrite(sb *strings.Builder, v any) {
	switch x := v.(type) {
	case nil:
		sb.WriteString("null")
	case bool:
		if x {
			sb.WriteString("true")
		} else {
			sb.WriteString("false")
		}
	case int:
		sb.WriteString(strconv.Itoa(x))
	case int64:
		sb.WriteString(strconv.FormatInt(x, 10))
	case BigInt:
		sb.WriteString(x.String())
	case float64:
		sb.WriteString(pyFloatRepr(x))
	case json.Number:
		sb.WriteString(string(x))
	case string:
		jsonString(sb, x)
	case []any:
		sb.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				sb.WriteString(", ")
			}
			jsonWrite(sb, e)
		}
		sb.WriteByte(']')
	case []string:
		a := make([]any, len(x))
		for i := range x {
			a[i] = x[i]
		}
		jsonWrite(sb, a)
	case jobj:
		sb.WriteByte('{')
		for i, kv := range x {
			if i > 0 {
				sb.WriteString(", ")
			}
			jsonString(sb, kv.k)
			sb.WriteString(": ")
			jsonWrite(sb, kv.v)
		}
		sb.WriteByte('}')
	default:
		jsonString(sb, "?")
	}
}

func jsonString(sb *strings.Builder, s string) {
	sb.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"':
			sb.WriteString(`\"`)
		case '\\':
			sb.WriteString(`\\`)
		case '\n':
			sb.WriteString(`\n`)
		case '\r':
			sb.WriteString(`\r`)
		case '\t':
			sb.WriteString(`\t`)
		case '\b':
			sb.WriteString(`\b`)
		case '\f':
			sb.WriteString(`\f`)
		default:
			switch {
			case r >= 0x20 && r <= 0x7e:
				sb.WriteRune(r)
			case r >= 0x10000:
				r -= 0x10000
				sb.WriteString(`\u` + hex4(0xD800+int(r>>10)) + `\u` + hex4(0xDC00+int(r&0x3FF)))
			default:
				sb.WriteString(`\u` + hex4(int(r)))
			}
		}
	}
	sb.WriteByte('"')
}

func hex4(n int) string {
	s := strconv.FormatInt(int64(n), 16)
	return strings.Repeat("0", 4-len(s)) + s
}

// isPrint is str.isprintable() for one character.
func isPrint(r rune) bool { return unicode.IsPrint(r) }
