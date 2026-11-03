//go:build !windows

package main

import (
	"fmt"
	"os"
)

func protectExistingToken(_ string, info os.FileInfo) error {
	if info.Mode().Perm()&0077 != 0 {
		return fmt.Errorf("token file must have permission 0600")
	}
	return nil
}
func protectNewToken(_ string, f *os.File) error { return f.Chmod(0600) }
func openControlTerminal() (*os.File, error)     { return os.OpenFile("/dev/tty", os.O_WRONLY, 0) }
