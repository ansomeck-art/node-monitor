package tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"sort"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/ipn/ipnstate"
)

// DiscoveredNode contains no credentials and is only returned to the local UI.
type DiscoveredNode struct {
	Name      string `json:"name"`
	Address   string `json:"address"`
	OwnerAuth bool   `json:"ownerAuth"`
	Access    string `json:"access"`
}

// SameOwner is deliberately conservative. Tagged devices and shared devices
// have different identity semantics and cannot use this convenience path.
func (n *Node) SameOwner(ctx context.Context, remoteAddr string) bool {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	status, err := n.Status(ctx)
	if err != nil || status == nil {
		return false
	}
	peer, err := n.client.WhoIs(ctx, remoteAddr)
	if err != nil {
		return false
	}
	return sameOwner(status, peer)
}

func sameOwner(status *ipnstate.Status, peer *apitype.WhoIsResponse) bool {
	if status == nil || status.BackendState != "Running" || status.Self == nil || status.Self.UserID == 0 || status.Self.AltSharerUserID != 0 || (status.Self.Tags != nil && status.Self.Tags.Len() > 0) || peer == nil || peer.Node == nil || peer.UserProfile == nil {
		return false
	}
	return len(peer.Node.Tags) == 0 && peer.Node.Sharer == 0 && peer.Node.User == status.Self.UserID && peer.UserProfile.ID == status.Self.UserID
}

// Discover probes only port 9274 on bounded peers supplied by the local
// Tailscale status. It neither scans the public internet nor returns secrets.
func (n *Node) Discover(ctx context.Context) ([]DiscoveredNode, error) {
	scans := 0
	return retryEmptyDiscovery(ctx, 10, time.Second, func() ([]DiscoveredNode, bool, error) {
		status, err := n.client.Status(ctx)
		if err != nil || status == nil || status.BackendState != "Running" {
			return nil, false, errors.New("remote network is not ready")
		}
		// The backend may be Running before its peer map has arrived. Waiting
		// for that map does not send probes to the other devices.
		if len(status.Peer) == 0 {
			return nil, true, nil
		}
		scans++
		return n.discoverPeers(ctx, status), scans < 2, nil
	})
}

// A freshly started node can report Running before its peer map or the first
// connection is ready. Briefly retry an empty scan instead of showing a false
// "no servers" result immediately after sign-in.
func retryEmptyDiscovery(ctx context.Context, attempts int, delay time.Duration, scan func() ([]DiscoveredNode, bool, error)) ([]DiscoveredNode, error) {
	for attempt := 0; attempt < attempts; attempt++ {
		nodes, retry, err := scan()
		if err != nil || len(nodes) > 0 || !retry || attempt == attempts-1 {
			return nodes, err
		}
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
	return nil, nil
}

func (n *Node) discoverPeers(ctx context.Context, status *ipnstate.Status) []DiscoveredNode {
	candidates := make([]DiscoveredNode, 0, len(status.Peer))
	for _, peer := range status.Peer {
		if peer == nil {
			continue
		}
		var ip netip.Addr
		for _, candidate := range peer.TailscaleIPs {
			if netip.MustParsePrefix("100.64.0.0/10").Contains(candidate) {
				ip = candidate
				break
			}
			if !ip.IsValid() && netip.MustParsePrefix("fd7a:115c:a1e0::/48").Contains(candidate) {
				ip = candidate
			}
		}
		if !ip.IsValid() {
			continue
		}
		name := strings.TrimSpace(peer.HostName)
		if name == "" {
			name = strings.TrimSuffix(peer.DNSName, ".")
		}
		if name == "" {
			name = "服务器"
		}
		candidates = append(candidates, DiscoveredNode{Name: name, Address: net.JoinHostPort(ip.String(), "9274")})
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].Address < candidates[j].Address })
	if len(candidates) > 32 {
		candidates = candidates[:32]
	}
	jobs := make(chan DiscoveredNode)
	var mu sync.Mutex
	results := []DiscoveredNode{}
	var workers sync.WaitGroup
	for i := 0; i < 4; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for candidate := range jobs {
				probe, cancel := context.WithTimeout(ctx, 3*time.Second)
				if n.isMonitor(probe, candidate.Address) {
					data, err := n.Fetch(probe, candidate.Address, "")
					candidate.Access = "unavailable"
					if errors.Is(err, ErrUnauthorized) {
						candidate.Access = "token"
					}
					var snapshot struct {
						SchemaVersion int `json:"schemaVersion"`
					}
					if err == nil && json.Unmarshal(data, &snapshot) == nil && snapshot.SchemaVersion == 1 {
						candidate.OwnerAuth, candidate.Access = true, "ready"
					}
					mu.Lock()
					results = append(results, candidate)
					mu.Unlock()
				}
				cancel()
			}
		}()
	}
	for _, candidate := range candidates {
		select {
		case jobs <- candidate:
		case <-ctx.Done():
			break
		}
	}
	close(jobs)
	workers.Wait()
	sort.Slice(results, func(i, j int) bool {
		if results[i].Name == results[j].Name {
			return results[i].Address < results[j].Address
		}
		return results[i].Name < results[j].Name
	})
	return results
}

func (n *Node) isMonitor(ctx context.Context, address string) bool {
	endpoint, err := Endpoint(address)
	if err != nil {
		return false
	}
	endpoint = strings.TrimSuffix(endpoint, "/v1/snapshot") + "/health"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return false
	}
	resp, err := n.http.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return false
	}
	var health struct {
		Service string `json:"service"`
		Status  string `json:"status"`
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&health) == nil && health.Service == "nodemon-agent" && health.Status == "ok"
}
