package tailnet

import (
	"context"
	"io"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/views"
)

func TestSameOwnerRejectsSharedTaggedAndOtherUsers(t *testing.T) {
	status := &ipnstate.Status{BackendState: "Running", Self: &ipnstate.PeerStatus{UserID: 42}}
	peer := &apitype.WhoIsResponse{Node: &tailcfg.Node{User: 42}, UserProfile: &tailcfg.UserProfile{ID: 42}}
	if !sameOwner(status, peer) {
		t.Fatal("matching user should be accepted")
	}
	peer.Node.User = 43
	if sameOwner(status, peer) {
		t.Fatal("other user accepted")
	}
	peer.Node.User = 42
	peer.Node.Sharer = 3
	if sameOwner(status, peer) {
		t.Fatal("shared device accepted")
	}
	peer.Node.Sharer = 0
	peer.Node.Tags = []string{"tag:server"}
	if sameOwner(status, peer) {
		t.Fatal("tagged client accepted")
	}
	peer.Node.Tags = nil
	tags := views.SliceOf([]string{"tag:server"})
	status.Self.Tags = &tags
	if sameOwner(status, peer) {
		t.Fatal("tagged server accepted")
	}
}

type discoveryTransport struct{}

func (discoveryTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	body, code := `{"status":"ok","service":"nodemon-agent"}`, 200
	if r.URL.Hostname() == "100.64.0.3" {
		body = `{"status":"ok","service":"other"}`
	}
	if r.URL.Path == "/v1/snapshot" {
		if r.URL.Hostname() == "100.64.0.1" {
			body = `{"schemaVersion":1}`
		} else {
			body = `{"error":"unauthorized"}`
			code = 401
		}
		if r.Header.Get("Authorization") != "" {
			panic("discovery must never send a bearer token")
		}
	}
	return &http.Response{StatusCode: code, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header)}, nil
}
func TestDiscoverOnlyIdentifiedMonitors(t *testing.T) {
	status := &ipnstate.Status{Peer: map[key.NodePublic]*ipnstate.PeerStatus{}}
	for i, ip := range []string{"100.64.0.1", "100.64.0.2", "100.64.0.3", "192.168.1.2"} {
		k := key.NewNode().Public()
		status.Peer[k] = &ipnstate.PeerStatus{HostName: []string{"one", "two", "other", "lan"}[i], TailscaleIPs: []netip.Addr{netip.MustParseAddr(ip)}}
	}
	n := &Node{http: &http.Client{Transport: discoveryTransport{}}}
	found := n.discoverPeers(context.Background(), status)
	if len(found) != 2 {
		t.Fatalf("got %d discovered monitors", len(found))
	}
	if found[0].Name != "one" || !found[0].OwnerAuth || found[0].Access != "ready" {
		t.Fatalf("unexpected first result: %+v", found[0])
	}
	if found[1].Name != "two" || found[1].OwnerAuth || found[1].Access != "token" {
		t.Fatalf("unexpected second result: %+v", found[1])
	}
}

func TestDiscoveryRetriesEmptyResultsAndStopsAfterSuccess(t *testing.T) {
	calls := 0
	found, err := retryEmptyDiscovery(context.Background(), 3, 0, func() ([]DiscoveredNode, bool, error) {
		calls++
		if calls == 2 {
			return []DiscoveredNode{{Name: "server", Access: "ready"}}, true, nil
		}
		return nil, true, nil
	})
	if err != nil || calls != 2 || len(found) != 1 || found[0].Access != "ready" {
		t.Fatalf("retry did not return the discovered server: calls=%d nodes=%v err=%v", calls, found, err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	calls = 0
	_, err = retryEmptyDiscovery(ctx, 3, time.Hour, func() ([]DiscoveredNode, bool, error) {
		calls++
		return nil, true, nil
	})
	if err != context.Canceled || calls != 1 {
		t.Fatalf("retry did not respect cancellation: calls=%d err=%v", calls, err)
	}

	calls = 0
	_, err = retryEmptyDiscovery(context.Background(), 10, 0, func() ([]DiscoveredNode, bool, error) {
		calls++
		return nil, calls < 2, nil
	})
	if err != nil || calls != 2 {
		t.Fatalf("retry did not stop when further probing was disabled: calls=%d err=%v", calls, err)
	}
}
