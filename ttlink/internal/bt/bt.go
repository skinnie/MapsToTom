// Package bt offers the Dial-up Networking service over Bluetooth with the kernel's RFCOMM sockets and
// BlueZ's local SDP server socket (BlueZ 4, as on Android 2.x-4.1; BlueZ 5 only with bluetoothd -C).
// Needs root (or CAP_NET_ADMIN/NET_BIND) on the phone.
package bt

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

// Listener accepts RFCOMM connections on one channel.
type Listener struct {
	fd      int
	Channel uint8
}

// Listen binds the first free RFCOMM channel from the list (0 = try 1..30).
func Listen(channels ...uint8) (*Listener, error) {
	if len(channels) == 0 || (len(channels) == 1 && channels[0] == 0) {
		channels = nil
		for c := uint8(1); c <= 30; c++ {
			channels = append(channels, c)
		}
	}
	var last error
	for _, ch := range channels {
		fd, err := unix.Socket(unix.AF_BLUETOOTH, unix.SOCK_STREAM, unix.BTPROTO_RFCOMM)
		if err != nil {
			return nil, fmt.Errorf("rfcomm socket: %w", err)
		}
		if err = unix.Bind(fd, &unix.SockaddrRFCOMM{Channel: ch}); err == nil {
			if err = unix.Listen(fd, 1); err == nil {
				return &Listener{fd: fd, Channel: ch}, nil
			}
		}
		unix.Close(fd)
		last = err
	}
	return nil, fmt.Errorf("no free rfcomm channel: %w", last)
}

// Accept waits for the next connection and returns it as a blocking stream plus the peer address.
func (l *Listener) Accept() (*os.File, string, error) {
	nfd, sa, err := unix.Accept(l.fd)
	if err != nil {
		return nil, "", err
	}
	peer := "?"
	if rc, ok := sa.(*unix.SockaddrRFCOMM); ok {
		a := rc.Addr
		peer = fmt.Sprintf("%02X:%02X:%02X:%02X:%02X:%02X", a[5], a[4], a[3], a[2], a[1], a[0])
	}
	return os.NewFile(uintptr(nfd), "rfcomm:"+peer), peer, nil
}

func (l *Listener) Close() error { return unix.Close(l.fd) }

// ---- SDP record (data elements, Bluetooth Core Vol 3 Part B 3.2)

func deUint8(v uint8) []byte   { return []byte{0x08, v} }
func deUint16(v uint16) []byte { return []byte{0x09, byte(v >> 8), byte(v)} }
func deUUID16(v uint16) []byte { return []byte{0x19, byte(v >> 8), byte(v)} }
func deText(s string) []byte   { return append([]byte{0x25, byte(len(s))}, s...) }
func deSeq(items ...[]byte) []byte {
	var b []byte
	for _, it := range items {
		b = append(b, it...)
	}
	return append([]byte{0x35, byte(len(b))}, b...)
}

// DUNRecord is the record server/fakedun.py registers: DUN (0x1103) on the given RFCOMM channel.
// No handle attribute: the SDP server assigns one.
func DUNRecord(channel uint8) []byte {
	attr := func(id uint16, v []byte) []byte { return append(deUint16(id), v...) }
	return deSeq(
		attr(0x0001, deSeq(deUUID16(0x1103))),
		attr(0x0004, deSeq(deSeq(deUUID16(0x0100)), deSeq(deUUID16(0x0003), deUint8(channel)))),
		attr(0x0005, deSeq(deUUID16(0x1002))),
		attr(0x0009, deSeq(deSeq(deUUID16(0x1103), deUint16(0x0100)))),
		attr(0x0100, deText("Dial-up Networking")),
		attr(0x0305, deUint8(0)),
	)
}

// SDPSockets are the places BlueZ 4 builds put the local SDP server socket.
var SDPSockets = []string{"/var/run/sdp", "/dev/socket/bluetooth/sdp", "/data/misc/bluetoothd/sdp", "/tmp/sdp"}

// FindSDPSocket returns the first existing path from SDPSockets, else any bound unix socket whose name
// ends in "sdp" (from /proc/net/unix).
func FindSDPSocket() string {
	for _, p := range SDPSockets {
		if fi, err := os.Stat(p); err == nil && fi.Mode()&os.ModeSocket != 0 {
			return p
		}
	}
	f, err := os.Open("/proc/net/unix")
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fs := strings.Fields(sc.Text())
		if n := len(fs); n >= 8 && strings.HasSuffix(fs[n-1], "sdp") && strings.HasPrefix(fs[n-1], "/") {
			return fs[n-1]
		}
	}
	return ""
}

// Registration keeps a record alive: BlueZ removes it when this socket closes.
type Registration struct {
	conn   net.Conn
	Handle uint32
}

func (r *Registration) Close() error { return r.conn.Close() }

// Register sends SDP_SVC_REGISTER_REQ (0x75, BlueZ local extension) with the record.
func Register(path string, record []byte) (*Registration, error) {
	c, err := net.DialTimeout("unix", path, 3*time.Second)
	if err != nil {
		return nil, err
	}
	pl := append([]byte{0}, record...) // flags 0 = any adapter
	req := []byte{0x75, 0x00, 0x01, byte(len(pl) >> 8), byte(len(pl))}
	if _, err := c.Write(append(req, pl...)); err != nil {
		c.Close()
		return nil, err
	}
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	rsp := make([]byte, 64)
	n, err := c.Read(rsp)
	c.SetReadDeadline(time.Time{})
	if err != nil {
		c.Close()
		return nil, err
	}
	rsp = rsp[:n]
	switch {
	case n >= 9 && rsp[0] == 0x76:
		return &Registration{conn: c, Handle: binary.BigEndian.Uint32(rsp[5:9])}, nil
	case n >= 7 && rsp[0] == 0x01:
		c.Close()
		return nil, fmt.Errorf("sdp error 0x%04x", binary.BigEndian.Uint16(rsp[5:7]))
	}
	c.Close()
	return nil, errors.New("sdp: unexpected reply " + fmt.Sprintf("%x", rsp))
}

// SetPhoneClass writes the Class of Device "Phone / Cellular" with networking + telephony services
// (0x5a020c) through a raw HCI socket. Only for devices that do not already say "phone" (tablets);
// Android may rewrite the class later.
func SetPhoneClass(dev uint16) error {
	fd, err := unix.Socket(unix.AF_BLUETOOTH, unix.SOCK_RAW, unix.BTPROTO_HCI)
	if err != nil {
		return err
	}
	defer unix.Close(fd)
	if err := unix.Bind(fd, &unix.SockaddrHCI{Dev: dev, Channel: unix.HCI_CHANNEL_RAW}); err != nil {
		return err
	}
	// HCI command packet: type 1, opcode Write_Class_of_Device (OGF 3, OCF 0x24) = 0x0c24, len 3, CoD LE
	_, err = unix.Write(fd, []byte{0x01, 0x24, 0x0c, 3, 0x0c, 0x02, 0x5a})
	return err
}
