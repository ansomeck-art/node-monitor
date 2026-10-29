//go:build ts_omit_logtail

package tailnet

import "testing"

func TestPrivateLoggingBuild(t *testing.T) {
	if !privateLogBuild {
		t.Fatal("the embedded network must be built without logtail")
	}
}
