package server

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"nodemon/agent/internal/metrics"
	"strings"
	"sync"
)

// Requests only read the latest cached sample. More clients never trigger extra collection.
type Store struct {
	mu       sync.RWMutex
	snapshot metrics.Snapshot
}

func (s *Store) Put(value metrics.Snapshot) { s.mu.Lock(); s.snapshot = value; s.mu.Unlock() }
func (s *Store) Get() metrics.Snapshot      { s.mu.RLock(); defer s.mu.RUnlock(); return s.snapshot }
func Handler(store *Store, token string) http.Handler {
	digest := sha256.Sum256([]byte(token))
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"ok","service":"nodemon-agent"}`))
	})
	mux.HandleFunc("/v1/snapshot", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		if r.Method != "GET" {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		header := r.Header.Get("Authorization")
		candidate := sha256.Sum256([]byte(strings.TrimPrefix(header, "Bearer ")))
		if token == "" || !strings.HasPrefix(header, "Bearer ") || subtle.ConstantTimeCompare(candidate[:], digest[:]) != 1 {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
			return
		}
		_ = json.NewEncoder(w).Encode(store.Get())
	})
	return mux
}
