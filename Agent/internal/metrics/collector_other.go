//go:build !linux

package metrics

import "time"

// Non-Linux builds exist only for the explicitly labelled local demo.
type Collector struct{}

func NewCollector(time.Duration, bool) *Collector { return &Collector{} }
func (*Collector) Collect(now time.Time, interval float64) Snapshot {
	return DemoSnapshot(now, interval)
}
