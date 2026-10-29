// nodemon-tailscale-bridge is a child process of the Mac application. Its
// private stdin/stdout pipes carry requests; no TCP admin port is opened.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"os"
	"sync"
	"time"

	"nodemon/agent/internal/tailnet"
	"tailscale.com/net/netns"
)

type request struct {
	ID      string `json:"id"`
	Action  string `json:"action"`
	Address string `json:"address,omitempty"`
	Token   string `json:"token,omitempty"`
}
type response struct {
	ID    string          `json:"id"`
	State string          `json:"state,omitempty"`
	URL   string          `json:"url,omitempty"`
	Data  json.RawMessage `json:"data,omitempty"`
	Error string          `json:"error,omitempty"`
}

func main() {
	dir := flag.String("state-dir", "", "private project-local tailnet state directory")
	flag.Parse()
	if *dir == "" {
		os.Exit(2)
	}
	// tsnet has no system tunnel interface of its own. Follow the Mac's
	// existing routes so control-plane connections also work through an
	// already-configured VPN or transparent proxy. This changes no OS routes.
	netns.SetDisableBindConnToInterface(func(string, ...any) {}, true)
	node, err := tailnet.New(*dir, "node-monitor-mac")
	if err != nil {
		os.Exit(2)
	}
	defer node.Close()
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 8)
	enc := json.NewEncoder(os.Stdout)
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		var req request
		if json.Unmarshal(scanner.Bytes(), &req) != nil || req.ID == "" {
			continue
		}
		sem <- struct{}{}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			res := handle(node, req)
			mu.Lock()
			_ = enc.Encode(res)
			mu.Unlock()
		}()
	}
	wg.Wait()
}

func handle(node *tailnet.Node, req request) response {
	res := response{ID: req.ID}
	timeout := 15 * time.Second
	if req.Action == "login" {
		timeout = 40 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	switch req.Action {
	case "status":
		status, err := node.Status(ctx)
		if err != nil {
			res.Error = "unavailable"
		} else {
			res.State = status.BackendState
		}
	case "login":
		url, err := node.LoginURL(ctx)
		if err != nil {
			res.Error = "login"
		} else {
			res.URL = url
		}
	case "fetch":
		// A freshly started userspace node can need a few seconds to connect.
		// Retry only transport failures within the existing request deadline;
		// authentication and invalid-address failures must be reported at once.
		data, err := fetchWithRetry(ctx, time.Second, func(ctx context.Context) ([]byte, error) {
			return fetchWhenReady(ctx, func(ctx context.Context) (bool, error) {
				status, err := node.Status(ctx)
				return err == nil && status != nil && status.BackendState == "Running", err
			}, func(ctx context.Context) ([]byte, error) {
				return node.Fetch(ctx, req.Address, req.Token)
			})
		})
		switch {
		case err == nil && json.Valid(data):
			res.Data = data
		case err == nil:
			res.Error = "incompatible"
		case errors.Is(err, tailnet.ErrUnauthorized):
			res.Error = "unauthorized"
		case errors.Is(err, tailnet.ErrForbidden):
			res.Error = "forbidden"
		case errors.Is(err, tailnet.ErrTooLarge):
			res.Error = "too_large"
		case errors.Is(err, tailnet.ErrInvalidAddress):
			res.Error = "invalid_address"
		default:
			res.Error = "connection"
		}
	default:
		res.Error = "invalid_action"
	}
	return res
}

func fetchWhenReady(ctx context.Context, ready func(context.Context) (bool, error), fetch func(context.Context) ([]byte, error)) ([]byte, error) {
	running, err := ready(ctx)
	if err != nil {
		return nil, err
	}
	if !running {
		return nil, errors.New("tailnet is starting")
	}
	return fetch(ctx)
}

func fetchWithRetry(ctx context.Context, delay time.Duration, fetch func(context.Context) ([]byte, error)) ([]byte, error) {
	for {
		data, err := fetch(ctx)
		if err == nil || errors.Is(err, tailnet.ErrUnauthorized) || errors.Is(err, tailnet.ErrForbidden) ||
			errors.Is(err, tailnet.ErrTooLarge) || errors.Is(err, tailnet.ErrInvalidAddress) || errors.Is(err, tailnet.ErrHTTP) {
			return data, err
		}
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
}
