//go:build windows

// Package securestate protects credentials and embedded Tailscale state using
// Windows ACLs, rather than relying on Unix permission bits on Windows.
package securestate

import (
	"fmt"
	"golang.org/x/sys/windows"
)

// Protect removes inherited access and grants only the current account and
// LocalSystem. Directory permissions are inherited by newly-created files.
func Protect(name string, directory bool) error {
	token, err := windows.OpenCurrentProcessToken()
	if err != nil {
		return err
	}
	defer token.Close()
	user, err := token.GetTokenUser()
	if err != nil {
		return err
	}
	flags := ""
	if directory {
		flags = "OICI"
	}
	descriptor, err := windows.SecurityDescriptorFromString(fmt.Sprintf("D:P(A;%s;FA;;;%s)(A;%s;FA;;;SY)", flags, user.User.Sid.String(), flags))
	if err != nil {
		return err
	}
	acl, _, err := descriptor.DACL()
	if err != nil {
		return err
	}
	return windows.SetNamedSecurityInfo(name, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
}
