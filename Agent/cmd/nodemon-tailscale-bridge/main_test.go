package main

import (
	"context"
	"errors"
	"testing"
	"time"

	"nodemon/agent/internal/tailnet"
)

func TestFetchRetryOnTransientFailure(t *testing.T) {
	attempts := 0
	data, err := fetchWithRetry(context.Background(), time.Millisecond, func(context.Context) ([]byte, error) {
		attempts++
		if attempts < 3 {
			return nil, errors.New("transport not ready")
		}
		return []byte(`{"schemaVersion":1}`), nil
	})
	if err != nil || attempts != 3 || len(data) == 0 {
		t.Fatalf("transient failure was not retried successfully: attempts=%d err=%v", attempts, err)
	}
}

func TestFetchWaitsForTailnetReadiness(t *testing.T) {
	checks, fetches := 0, 0
	data, err := fetchWithRetry(context.Background(), time.Millisecond, func(ctx context.Context) ([]byte, error) {
		return fetchWhenReady(ctx, func(context.Context) (bool, error) {
			checks++
			return checks >= 3, nil
		}, func(context.Context) ([]byte, error) {
			fetches++
			return []byte(`{"schemaVersion":1}`), nil
		})
	})
	if err != nil || checks != 3 || fetches != 1 || len(data) == 0 {
		t.Fatalf("fetch did not wait for readiness: checks=%d fetches=%d err=%v", checks, fetches, err)
	}
}

func TestFetchDoesNotRetryAuthenticationFailure(t *testing.T) {
	attempts := 0
	_, err := fetchWithRetry(context.Background(), time.Millisecond, func(context.Context) ([]byte, error) {
		attempts++
		return nil, tailnet.ErrUnauthorized
	})
	if !errors.Is(err, tailnet.ErrUnauthorized) || attempts != 1 {
		t.Fatalf("authentication failure was retried: attempts=%d err=%v", attempts, err)
	}
}

func TestFetchRetryStopsAtDeadline(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	defer cancel()
	_, err := fetchWithRetry(ctx, time.Millisecond, func(context.Context) ([]byte, error) {
		return nil, errors.New("transport unavailable")
	})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("retry did not stop at the deadline: %v", err)
	}
}
