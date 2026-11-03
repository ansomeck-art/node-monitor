package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"nodemon/agent/internal/metrics"
	"strings"
	"testing"
	"time"
)

func TestAuthenticationCachedSampleAndMethods(t *testing.T) {
	s := &Store{}
	now := time.Now()
	s.Put(metrics.DemoSnapshot(now, 5))
	token := strings.Repeat("a", 43)
	handler := Handler(s, token)
	for _, test := range []struct {
		method, token string
		code          int
	}{{"GET", "", 401}, {"GET", "wrong", 401}, {"POST", token, 405}, {"GET", token, 200}} {
		r := httptest.NewRequest(test.method, "/v1/snapshot", nil)
		if test.token != "" {
			r.Header.Set("Authorization", "Bearer "+test.token)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != test.code {
			t.Fatalf("%s got %d want %d", test.method, w.Code, test.code)
		}
		if w.Code == http.StatusOK {
			var sample metrics.Snapshot
			if err := json.Unmarshal(w.Body.Bytes(), &sample); err != nil {
				t.Fatal(err)
			}
			if !sample.Demo || !sample.SampledAt.Equal(now) {
				t.Fatal("request must return cached sample, including timestamp/demo provenance")
			}
		}
		if strings.Contains(w.Body.String(), token) {
			t.Fatal("credential leaked")
		}
	}
}

func TestTailnetOwnerAccessDoesNotChangeTokenAuthentication(t *testing.T) {
	store := &Store{}
	store.Put(metrics.DemoSnapshot(time.Now(), 5))
	approved := func(_ context.Context, addr string) bool { return addr == "100.64.0.7:1234" }
	handler := TailnetHandler(store, "test-token", approved)
	for _, tc := range []struct {
		addr, header string
		want         int
	}{
		{"100.64.0.7:1234", "", 200},
		{"100.64.0.8:1234", "", 401},
		{"100.64.0.7:1234", "Bearer wrong", 401},
		{"100.64.0.8:1234", "Bearer test-token", 200},
	} {
		r := httptest.NewRequest("GET", "/v1/snapshot", nil)
		r.RemoteAddr = tc.addr
		if tc.header != "" {
			r.Header.Set("Authorization", tc.header)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Fatalf("tailnet auth returned %d; expected %d", w.Code, tc.want)
		}
	}
	r := httptest.NewRequest("GET", "/v1/snapshot", nil)
	r.RemoteAddr = "100.64.0.7:1234"
	w := httptest.NewRecorder()
	Handler(store, "test-token").ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("ordinary listener must still require token")
	}
}
func TestHealthDoesNotExposeMetrics(t *testing.T) {
	w := httptest.NewRecorder()
	Handler(&Store{}, "secret").ServeHTTP(w, httptest.NewRequest("GET", "/health", nil))
	if w.Code != 200 || strings.Contains(w.Body.String(), "cpu") || strings.Contains(w.Body.String(), "host") {
		t.Fatal("health endpoint disclosed metrics")
	}
}
