// Package at answers the TomTom's AT commands like a phone with dial-up networking (port of server/fakedun.py).
package at

import (
	"io"
	"regexp"
	"strings"
)

// Replies of a phone that TomTom's connect2internet.db knows (docs/c2i_decoded.txt): Sony Ericsson K510i
// ('SonyEricsson', 'AAB-1022041-BV' -> phone 268) on Vodafone PT (numeric '26801' -> APN internet.vodafone.pt).
// The database knows Vodafone PT only by the numeric code, so +COPS answers numeric. Prefix match: longer first.
var replies = [][2]string{
	{"AT+CGMI", "SonyEricsson"}, {"AT+GMI", "SonyEricsson"}, {"AT+CGMM", "AAB-1022041-BV"}, {"AT+GMM", "AAB-1022041-BV"},
	{"AT+CGMR", "R8BA024"}, {"AT+CGSN", "000000000000000"}, {"AT+CIMI", "268011234567890"},
	{"AT+CSQ", "+CSQ: 20,99"}, {"AT+CREG?", "+CREG: 0,1"}, {"AT+CGREG?", "+CGREG: 0,1"},
	{"AT+COPS?", `+COPS: 0,2,"26801"`}, {"AT+COPS=?", `+COPS: (2,"Vodafone P","Vodafone","26801"),,(0-4),(0,2)`},
	{"AT+CPIN?", "+CPIN: READY"}, {"AT+CGATT?", "+CGATT: 1"}, {"AT+CBC", "+CBC: 0,80"},
	// handsfree (phone side of HFP/HSP; NavCore sends these after the data check)
	{"AT+BRSF", "+BRSF: 0"},
	{"AT+CIND=?", `+CIND: ("service",(0,1)),("call",(0,1)),("callsetup",(0-3)),("callheld",(0-2)),` +
		`("signal",(0-5)),("roam",(0,1)),("battchg",(0-5))`},
	{"AT+CIND?", "+CIND: 1,0,0,0,4,0,4"},
	{"AT+CHLD=?", "+CHLD: (0,1,2,3)"}, {"AT+CLCC=?", ""}, {"AT+CLCC", ""},
	// phonebook: empty
	{"AT+CPBS=?", `+CPBS: ("ME","SM","DC","RC","MC")`}, {"AT+CPBS?", `+CPBS: "ME",0,500`},
	{"AT+CPBR=?", "+CPBR: (1-500),40,24"}, {"AT+CPBR=", ""},
	// SMS: text mode, empty stores
	{"AT+CMGF=?", "+CMGF: (0,1)"}, {"AT+CMGF?", "+CMGF: 1"},
	{"AT+CPMS=?", `+CPMS: ("ME","SM"),("ME","SM"),("ME","SM")`},
	{"AT+CPMS?", `+CPMS: "ME",0,100,"ME",0,100,"ME",0,100`}, {"AT+CPMS=", "+CPMS: 0,100,0,100,0,100"},
	{"AT+CMGL", ""}, {"AT+CSMS=?", "+CSMS: (0,1)"}, {"AT+CSMS?", "+CSMS: 0,1,1,1"}, {"AT+CSMS=", "+CSMS: 1,1,1"},
	{"AT+CNMI=?", "+CNMI: (0,1,2,3),(0,1,2,3),(0,2),(0,1,2),(0,1)"}, {"AT+CMGS=?", ""},
	{"AT+CSCS=?", `+CSCS: ("GSM","IRA","UTF-8")`}, {"AT+CSCS?", `+CSCS: "GSM"`},
}

// Reply returns the answer to one command line and whether it was a data dial (ATD*99..., ATD*TOMTOM...).
// Voice dial (ATD<number>;) -> OK. Unknown commands -> OK.
func Reply(line string) (string, bool) {
	cmd := strings.ToUpper(strings.TrimSpace(line))
	if strings.HasPrefix(cmd, "ATD") {
		if strings.HasSuffix(cmd, ";") {
			return "\r\nOK\r\n", false
		}
		return "\r\nCONNECT\r\n", true
	}
	for _, r := range replies {
		if strings.HasPrefix(cmd, r[0]) {
			if r[1] == "" {
				return "\r\nOK\r\n", false
			}
			return "\r\n" + r[1] + "\r\n\r\nOK\r\n", false
		}
	}
	return "\r\nOK\r\n", false
}

var echoOff = regexp.MustCompile(`^AT[^+]*E0`) // ATE0, ATZE0, ATE0V1 ...

// Serve runs the AT loop until a data dial. It returns true after CONNECT was sent (PPP starts on the same
// stream) and false when the link closed. Like a real phone, commands are echoed until ATE0.
func Serve(rw io.ReadWriter, log func(...any)) bool {
	echo := true
	var buf []byte
	b := make([]byte, 256)
	for {
		n, err := rw.Read(b)
		if n == 0 || err != nil {
			log("link closed by the TomTom:", err)
			return false
		}
		buf = append(buf, b[:n]...)
		for {
			i := indexEOL(buf)
			if i < 0 {
				break
			}
			line := string(buf[:i])
			buf = buf[i+1:]
			if strings.TrimSpace(line) == "" {
				continue
			}
			cmd := strings.ToUpper(strings.TrimSpace(line))
			if echoOff.MatchString(cmd) {
				echo = false
			} else if strings.HasPrefix(cmd, "ATE1") || strings.HasPrefix(cmd, "ATZ") {
				echo = true
			}
			reply, dial := Reply(line)
			out := reply
			if echo {
				out = line + "\r" + reply
			}
			log("AT <-", line, "->", strings.TrimSpace(reply))
			if _, err := rw.Write([]byte(out)); err != nil {
				log("link closed by the TomTom:", err)
				return false
			}
			if dial {
				return true
			}
		}
	}
}

func indexEOL(b []byte) int {
	for i, c := range b {
		if c == '\r' || c == '\n' {
			return i
		}
	}
	return -1
}
