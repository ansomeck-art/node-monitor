//go:build !windows

package securestate

func Protect(string, bool) error { return nil }
