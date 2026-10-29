package main

import "testing"

func TestAdditionalListenerIsLoopbackOnly(t *testing.T) {
	for _, address := range []string{"0.0.0.0:0", "192.168.1.2:0", "localhost:0", ":0", "invalid"} {
		if listener, err := listenOnLoopback(address); err == nil {
			listener.Close()
			t.Fatalf("accepted nonnumeric or nonloopback listener %q", address)
		}
	}
	listener, err := listenOnLoopback("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	listener.Close()
}
