package metrics

import (
	"encoding/binary"
	"testing"
)

func TestWindowsPackageTopologyAndMalformedRecord(t *testing.T) {
	record := func(mask uint64) []byte {
		b := make([]byte, 48)
		binary.LittleEndian.PutUint32(b[0:], 3)
		binary.LittleEndian.PutUint32(b[4:], uint32(len(b)))
		binary.LittleEndian.PutUint16(b[30:], 1)
		binary.LittleEndian.PutUint64(b[32:], mask)
		return b
	}
	data := append(record(0xff), record(0xff00)...)
	packages := parseWindowsPackages(data)
	if len(packages) != 2 || packages[0].LogicalCores != 8 || packages[1].LogicalCores != 8 || packages[0].ID == packages[1].ID {
		t.Fatalf("unexpected topology: %+v", packages)
	}
	// Never fabricate a CPU package from a truncated affinity list.
	binary.LittleEndian.PutUint16(data[30:], 9)
	if got := parseWindowsPackages(data); len(got) != 0 {
		t.Fatalf("accepted malformed topology: %+v", got)
	}
}
