package androidnet

import (
	"net/netip"
	"testing"
)

func TestInterfacesUseAvailableRoutes(t *testing.T) {
	interfaces, err := interfaces(func(network, _ string) (netip.Addr, bool) {
		if network == "udp4" {
			return netip.MustParseAddr("192.0.2.4"), true
		}
		return netip.MustParseAddr("2001:db8::4"), true
	})
	if err != nil || len(interfaces) != 1 || interfaces[0].Name != InterfaceName || !interfaces[0].IsUp() {
		t.Fatalf("unexpected synthetic interface: %v, %v", interfaces, err)
	}
	addrs, err := interfaces[0].Addrs()
	if err != nil || len(addrs) != 2 || addrs[0].String() != "192.0.2.4/32" || addrs[1].String() != "2001:db8::4/128" {
		t.Fatalf("unexpected route addresses: %v, %v", addrs, err)
	}
}

func TestInterfacesRequireUsableRoute(t *testing.T) {
	_, err := interfaces(func(network, _ string) (netip.Addr, bool) {
		if network == "udp4" {
			return netip.MustParseAddr("127.0.0.1"), true
		}
		return netip.Addr{}, false
	})
	if err == nil {
		t.Fatal("loopback alone must not be treated as an internet route")
	}
}
