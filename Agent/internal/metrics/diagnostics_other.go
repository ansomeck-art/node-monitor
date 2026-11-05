//go:build !linux

package metrics

func (c *Collector) ConfigureDiagnostics(bool, []string) {}
