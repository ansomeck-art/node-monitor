package tailnet

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestEndpointRestrictsTargetsAndNormalizes(t *testing.T) {
	valid := map[string]string{
		"100.64.0.1":                        "http://100.64.0.1:9274/v1/snapshot",
		"100.127.255.254:1234":              "http://100.127.255.254:1234/v1/snapshot",
		"test.example.ts.net:9274":          "http://test.example.ts.net:9274/v1/snapshot",
		"https://test.example.ts.net:8443/": "https://test.example.ts.net:8443/v1/snapshot",
		"[fd7a:115c:a1e0::1]:9274":          "http://[fd7a:115c:a1e0::1]:9274/v1/snapshot",
	}
	for input, want := range valid {
		got, err := Endpoint(input)
		if err != nil || got != want {
			t.Errorf("valid endpoint %q rejected or normalized incorrectly", input)
		}
	}
	for _, input := range []string{"", "127.0.0.1:9274", "192.168.1.1:9274", "100.128.0.1", "https://example.com", "http://example.ts.net/path", "http://example.ts.net/?x=y", "http://user@example.ts.net", "test.ts.net:0", "http://[::1]:80"} {
		if _, err := Endpoint(input); err == nil {
			t.Errorf("unsafe endpoint accepted: %q", input)
		}
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) { return f(req) }

func TestFetchCarriesTokenAndBoundsResponses(t *testing.T) {
	node := &Node{http: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if req.URL.Host != "100.64.0.1:9274" || req.URL.Path != "/v1/snapshot" || req.Header.Get("Authorization") != "Bearer valid-token" {
			t.Error("request was not directed or authenticated as expected")
		}
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(`{"schemaVersion":1}`)), ContentLength: -1, Header: make(http.Header)}, nil
	})}}
	data, err := node.Fetch(context.Background(), "100.64.0.1", "valid-token")
	if err != nil || string(data) != `{"schemaVersion":1}` {
		t.Fatal("valid response failed")
	}
	if _, err := node.Fetch(context.Background(), "192.168.1.1", "valid-token"); !errors.Is(err, ErrInvalidAddress) {
		t.Fatal("non-tailnet address was accepted")
	}
	node.http.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusUnauthorized, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
	})
	if _, err := node.Fetch(context.Background(), "100.64.0.1", "wrong"); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("invalid credential was not rejected")
	}
	node.http.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(strings.Repeat("x", MaxSnapshot+1))), ContentLength: -1, Header: make(http.Header)}, nil
	})
	if _, err := node.Fetch(context.Background(), "100.64.0.1", "valid-token"); !errors.Is(err, ErrTooLarge) {
		t.Fatal("oversized response accepted")
	}
}

func TestFetchTransportErrorPreservesCauseWithoutToken(t *testing.T) {
	node := &Node{http: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, context.DeadlineExceeded
	})}}
	_, err := node.Fetch(context.Background(), "100.64.0.1:9274", "private-token")
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("transport cause is not available for timeout classification")
	}
	if strings.Contains(err.Error(), "private-token") {
		t.Fatal("transport error exposed the access token")
	}
}
