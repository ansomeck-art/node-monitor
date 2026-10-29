// Package tailnet contains the private, embedded Tailscale transport. It never
// changes system routes or listens on an operating-system network interface.
package tailnet

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
)

const MaxSnapshot = 1 << 20

var (
	ErrInvalidAddress = errors.New("invalid tailnet address")
	ErrUnauthorized   = errors.New("unauthorized")
	ErrForbidden      = errors.New("forbidden")
	ErrTooLarge       = errors.New("response too large")
	ErrHTTP           = errors.New("agent returned an unexpected response")
	ErrLogin          = errors.New("unable to obtain an authorization link")
)

type Node struct {
	Server *tsnet.Server
	client *local.Client
	http   *http.Client
}

func New(dir, hostname string) (*Node, error) {
	// tsnet otherwise sends its backend and temporary authorization URLs to
	// Tailscale's logtail buffer. Never start it in an unprotected build.
	if !privateLogBuild {
		return nil, errors.New("embedded Tailscale requires the ts_omit_logtail build tag")
	}
	// Keep interactive authorization explicit and avoid inheriting another
	// application's auth key, control server, or forced-login settings.
	for _, key := range []string{"TS_AUTHKEY", "TS_AUTH_KEY", "TS_CLIENT_SECRET", "TS_CLIENT_ID", "TS_ID_TOKEN", "TS_AUDIENCE", "TSNET_FORCE_LOGIN", "TS_CONTROL_URL"} {
		if os.Getenv(key) != "" {
			return nil, errors.New("conflicting Tailscale environment settings")
		}
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return nil, err
	}
	// tsnet otherwise logs login URLs by default. Never write them to logs.
	s := &tsnet.Server{Dir: dir, Hostname: hostname, UserLogf: func(string, ...any) {}, Logf: func(string, ...any) {}}
	lc, err := s.LocalClient()
	if err != nil {
		return nil, err
	}
	hc := s.HTTPClient()
	hc.Timeout = 10 * time.Second
	hc.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &Node{Server: s, client: lc, http: hc}, nil
}

func (n *Node) Close() error { return n.Server.Close() }

func (n *Node) Status(ctx context.Context) (*ipnstate.Status, error) {
	return n.client.StatusWithoutPeers(ctx)
}

// LoginURL is returned only to the local user interface or an interactive TTY.
// It is never persisted or written to service logs by this project.
func (n *Node) LoginURL(ctx context.Context) (string, error) {
	status, err := n.Status(ctx)
	if err == nil && status.BackendState == "Running" {
		return "", nil
	}
	if err := n.client.StartLoginInteractive(ctx); err != nil {
		return "", ErrLogin
	}
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	for {
		status, err := n.Status(ctx)
		if err == nil {
			if status.BackendState == "Running" {
				return "", nil
			}
			if status.AuthURL != "" {
				u, parseErr := url.Parse(status.AuthURL)
				if parseErr != nil || u.Scheme != "https" || u.Hostname() != "login.tailscale.com" || u.User != nil {
					return "", ErrLogin
				}
				return u.String(), nil
			}
		}
		select {
		case <-ctx.Done():
			return "", ErrLogin
		case <-ticker.C:
		}
	}
}

// Endpoint permits only an explicit Tailscale IP or MagicDNS name. The
// resulting HTTP request must be dialed through tsnet, never a normal socket.
func Endpoint(address string) (string, error) {
	address = strings.TrimSpace(address)
	if !strings.Contains(address, "://") {
		address = "http://" + address
	}
	u, err := url.Parse(address)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.Host == "" || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return "", ErrInvalidAddress
	}
	host := strings.ToLower(u.Hostname())
	if host == "" {
		return "", ErrInvalidAddress
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		v4 := netip.MustParsePrefix("100.64.0.0/10")
		v6 := netip.MustParsePrefix("fd7a:115c:a1e0::/48")
		if !v4.Contains(ip) && !v6.Contains(ip) {
			return "", ErrInvalidAddress
		}
	} else {
		if !strings.HasSuffix(host, ".ts.net") || len(host) > 253 {
			return "", ErrInvalidAddress
		}
		for _, label := range strings.Split(host, ".") {
			if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
				return "", ErrInvalidAddress
			}
			for _, c := range label {
				if !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-' {
					return "", ErrInvalidAddress
				}
			}
		}
	}
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "9274"
		}
	}
	p, err := strconv.Atoi(port)
	if err != nil || p < 1 || p > 65535 {
		return "", ErrInvalidAddress
	}
	u.Host = net.JoinHostPort(host, port)
	u.Path = "/v1/snapshot"
	return u.String(), nil
}

func (n *Node) Fetch(ctx context.Context, address, token string) ([]byte, error) {
	endpoint, err := Endpoint(address)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, ErrInvalidAddress
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")
	resp, err := n.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("tailnet connection failed: %w", err)
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusOK:
	case http.StatusUnauthorized:
		return nil, ErrUnauthorized
	case http.StatusForbidden:
		return nil, ErrForbidden
	default:
		return nil, fmt.Errorf("%w: %d", ErrHTTP, resp.StatusCode)
	}
	if resp.ContentLength > MaxSnapshot {
		return nil, ErrTooLarge
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, MaxSnapshot+1))
	if err != nil {
		return nil, errors.New("tailnet response interrupted")
	}
	if len(data) > MaxSnapshot {
		return nil, ErrTooLarge
	}
	return data, nil
}
