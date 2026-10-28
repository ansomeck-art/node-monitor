package server

import (
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
func TestHealthDoesNotExposeMetrics(t *testing.T) {
	w := httptest.NewRecorder()
	Handler(&Store{}, "secret").ServeHTTP(w, httptest.NewRequest("GET", "/health", nil))
	if w.Code != 200 || strings.Contains(w.Body.String(), "cpu") || strings.Contains(w.Body.String(), "host") {
		t.Fatal("health endpoint disclosed metrics")
	}
}
