//go:build windows

package main

import (
	"nodemon/agent/internal/securestate"
	"os"
)

func protectExistingToken(name string, _ os.FileInfo) error { return securestate.Protect(name, false) }
func protectNewToken(name string, _ *os.File) error         { return securestate.Protect(name, false) }
func openControlTerminal() (*os.File, error)                { return os.OpenFile("CONOUT$", os.O_WRONLY, 0) }
