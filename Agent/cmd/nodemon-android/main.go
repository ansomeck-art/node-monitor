//go:build android

// Android JNI bridge: all authorization material stays in private memory or
// the app's private Tailscale directory. No shell, proxy port, or VPN is used.
package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unsafe"

	"nodemon/agent/internal/tailnet"
	"tailscale.com/net/netns"
)

var bridge struct {
	sync.Mutex
	node *tailnet.Node
	dir  string
}

func nodeFor(dir string) (*tailnet.Node, error) {
	bridge.Lock()
	defer bridge.Unlock()
	if bridge.node != nil && bridge.dir == dir {
		return bridge.node, nil
	}
	if bridge.node != nil {
		return nil, errors.New("identity directory changed")
	}
	netns.SetDisableBindConnToInterface(func(string, ...any) {}, true)
	node, err := tailnet.New(dir, "node-monitor-android")
	if err != nil {
		return nil, err
	}
	bridge.node, bridge.dir = node, dir
	return node, nil
}

type answer struct {
	State string                   `json:"state,omitempty"`
	URL   string                   `json:"url,omitempty"`
	Data  json.RawMessage          `json:"data,omitempty"`
	Nodes []tailnet.DiscoveredNode `json:"nodes,omitempty"`
	Error string                   `json:"error,omitempty"`
}

func respond(a answer) *C.char {
	b, _ := json.Marshal(a)
	// JNI NewStringUTF expects modified UTF-8. JSON-escape all non-ASCII
	// characters, including supplementary characters represented as pairs.
	var ascii strings.Builder
	for _, r := range string(b) {
		switch {
		case r < 128:
			ascii.WriteByte(byte(r))
		case r <= 0xffff:
			fmt.Fprintf(&ascii, "\\u%04x", r)
		default:
			hi, lo := utf16.EncodeRune(r)
			fmt.Fprintf(&ascii, "\\u%04x\\u%04x", hi, lo)
		}
	}
	return C.CString(ascii.String())
}

//export nmRequest
func nmRequest(action, dir, address, token *C.char) *C.char {
	node, err := nodeFor(C.GoString(dir))
	if err != nil {
		return respond(answer{Error: "unavailable"})
	}
	timeout := 15 * time.Second
	if C.GoString(action) == "login" || C.GoString(action) == "discover" {
		timeout = 40 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	switch C.GoString(action) {
	case "status":
		status, err := node.Status(ctx)
		if err != nil || status == nil {
			return respond(answer{Error: "unavailable"})
		}
		return respond(answer{State: status.BackendState})
	case "login":
		url, err := node.LoginURL(ctx)
		if err != nil {
			return respond(answer{Error: "login"})
		}
		return respond(answer{URL: url})
	case "discover":
		nodes, err := node.Discover(ctx)
		if err != nil {
			return respond(answer{Error: "not_connected"})
		}
		return respond(answer{Nodes: nodes})
	case "fetch":
		for {
			status, statusErr := node.Status(ctx)
			if statusErr == nil && status != nil && status.BackendState == "Running" {
				data, fetchErr := node.Fetch(ctx, C.GoString(address), C.GoString(token))
				switch {
				case fetchErr == nil && json.Valid(data):
					return respond(answer{Data: data})
				case fetchErr == nil:
					return respond(answer{Error: "incompatible"})
				case errors.Is(fetchErr, tailnet.ErrUnauthorized):
					return respond(answer{Error: "unauthorized"})
				case errors.Is(fetchErr, tailnet.ErrForbidden):
					return respond(answer{Error: "forbidden"})
				case errors.Is(fetchErr, tailnet.ErrTooLarge):
					return respond(answer{Error: "too_large"})
				case errors.Is(fetchErr, tailnet.ErrInvalidAddress):
					return respond(answer{Error: "invalid_address"})
				case errors.Is(fetchErr, tailnet.ErrHTTP):
					return respond(answer{Error: "http"})
				}
			}
			select {
			case <-ctx.Done():
				return respond(answer{Error: "connection"})
			case <-time.After(time.Second):
			}
		}
	default:
		return respond(answer{Error: "invalid_action"})
	}
}

//export nmFree
func nmFree(value *C.char) { C.free(unsafe.Pointer(value)) }
func main()                {}
