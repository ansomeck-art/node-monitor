//go:build !ts_omit_logtail

package tailnet

import (
	"path/filepath"
	"testing"
)

func TestRefusesLoggingEnabled(t *testing.T) {
	_, err := New(filepath.Join(t.TempDir(), "identity"), "test-node")
	if err == nil {
		t.Fatal("must not start when the Tailscale log buffer is enabled")
	}
}
