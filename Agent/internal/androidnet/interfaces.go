// Package androidnet supplies the network information needed by tsnet when
// Android denies app processes access to kernel interface enumeration.
package androidnet

import (
	"errors"
	"net"
	"net/netip"
	"time"

	"tailscale.com/net/netmon"
)

const InterfaceName = "android"

// Interfaces returns a synthetic interface containing the source addresses
// chosen by the kernel for the current default routes. Connecting a UDP socket
// selects a route without sending a packet. This follows Tailscale's
// androidbin fallback, which is not registered for Android cgo applications.
func Interfaces() ([]netmon.Interface, error) {
	return interfaces(outboundIP)
}

func interfaces(outbound func(string, string) (netip.Addr, bool)) ([]netmon.Interface, error) {
	var addrs []net.Addr
	for _, route := range [][2]string{{"udp4", "8.8.8.8:53"}, {"udp6", "[2001:4860:4860::8888]:53"}} {
		ip, ok := outbound(route[0], route[1])
		if !ok || !ip.IsValid() || ip.IsLoopback() || ip.IsUnspecified() {
			continue
		}
		ip = ip.Unmap()
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(ip.BitLen(), ip.BitLen())})
	}
	if len(addrs) == 0 {
		return nil, errors.New("no outbound network route")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{Name: InterfaceName, MTU: 1500, Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}, nil
}

func outboundIP(network, address string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	conn, err := d.Dial(network, address)
	if err != nil {
		return netip.Addr{}, false
	}
	defer conn.Close()
	local, ok := conn.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(local.IP)
	return ip, ok
}
