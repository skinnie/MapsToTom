package services

// XMsg ASCII framing (docs/service_discovery.md section 2) and the service discovery blob (discovery.py).

import (
	"bytes"
	"fmt"
	"strings"
)

// argInt is type '1': 12 ASCII digits.
func argInt(v int64) []byte { return []byte(fmt.Sprintf("1%012d", v)) }

// argStr is type '2': 6 ASCII digits length + bytes.
func argStr(t []byte) []byte { return append([]byte(fmt.Sprintf("2%06d", len(t))), t...) }

// argBuf is type '7': 8 ASCII digits length + raw bytes (xmsg_arg.c case 7).
func argBuf(b []byte) []byte { return append([]byte(fmt.Sprintf("7%08d", len(b))), b...) }

// argList is type '8': separator, 6 digits length, items joined by the separator.
func argList(items [][]byte, sep byte) []byte {
	p := bytes.Join(items, []byte{sep})
	return append([]byte(fmt.Sprintf("8%c%06d", sep, len(p))), p...)
}

// argTable is type '4': column and row separators, 6 digits length, rows.
func argTable(rows [][][]byte) []byte {
	rs := make([][]byte, len(rows))
	for i, r := range rows {
		rs[i] = bytes.Join(r, []byte{'\t'})
	}
	p := bytes.Join(rs, []byte{'\n'})
	return append([]byte(fmt.Sprintf("4\t\n%06d", len(p))), p...)
}

// xmsgReply is 8 digits total length, 4 digits error code, 4 digits argument count, the arguments.
func xmsgReply(args [][]byte, errCode int) []byte {
	body := []byte(fmt.Sprintf("%04d%04d", errCode, len(args)))
	for _, a := range args {
		body = append(body, a...)
	}
	return append([]byte(fmt.Sprintf("%08d", len(body)+8)), body...)
}

type kvEntry struct{ k, v string }

type kvGroup struct {
	name    string
	entries []kvEntry
}

// discoveryBlob is the ServiceDiscovery text. base: 'http://host:port' of this server; router: 'host:port'
// (ROUTER2/ROUTER is not a URL).
func discoveryBlob(base, router string) string {
	groups := []kvGroup{
		{"HERCULES", []kvEntry{{"HERCULES", base + "/h204"}}},
		{"ROADCONDITIONS", []kvEntry{{"ROADCONDITIONS", base + "/roadconditions"}}},
		{"ROUTER2", []kvEntry{{"PORTLAND", base + "/portland"}, {"ROUTER", router}}},
		{"WEATHER2", []kvEntry{{"WEATHER", base + "/weather"}}},
		{"LOCALSEARCH", []kvEntry{{"LOCALSEARCH", base + "/localsearch"}}},
		// data-setup check of the phone wizard (CBrowseAPN, docs/bt_data_path.md section 9): APN_LOG must say APNOK,
		// TEST_BIN returns a magic
		{"APN", []kvEntry{{"APN_LOG", base + "/apn"}, {"TEST_BIN", base + "/apn_test"}}},
		// traffic, service 0x11 (Portland/PLUS on phone-connected devices): FUN_001498b4 resolves group = traffic.dat
		// line 1 (services+0x428) and key = traffic.dat line 2 (services+0x454), read by FUN_0014fb70.
		// Iberia map: NAVTRAFFIC / NTadespt
		{"NAVTRAFFIC", []kvEntry{{"NTadespt", base + "/portland"}, {"NAVTRAFFIC", base + "/portland"}}},
	}
	// every other service index the client looks up (docs/service_discovery.md section 3) points at this server, so
	// the device's requests get logged; unknown paths get an XMsg "no content" reply
	for _, gk := range [][2]string{{"LOGON", "LOGON"}, {"PRODACT", "PRODACT"}, {"SVCACT", "SVCACT"}, {"ACCOUNT", "ACCOUNT"},
		{"ACCOUNT2", "ACCOUNT"}, {"MESSAGE", "MESSAGE"}, {"BUDDY", "BUDDY"},
		{"DYNSC", "DYNSC"}, {"FUELPRICES", "FUELPRICES"}, {"FUELPRICES", "FUELPRICESEUROPE"},
		{"MISC", "MB"}, {"ONDEVPAYMENT", "ONDEVPAYMENT"}, {"TRACK", "TRACK"}} {
		groups = append(groups, kvGroup{gk[0], []kvEntry{{gk[1], base + "/" + strings.ToLower(gk[0])}}})
	}
	var sb strings.Builder
	for _, g := range groups {
		sb.WriteString(g.name + "\n")
		for _, e := range g.entries {
			sb.WriteString(e.k + "|" + e.v + "\n")
		}
		sb.WriteString("\n")
	}
	return sb.String()
}
