package metrics

import (
	"encoding/binary"
	"fmt"
	"math/bits"
)

// Parse x64 SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX package records. A
// malformed or truncated record is ignored instead of inventing CPU counts.
func parseWindowsPackages(data []byte) []CPUPackage {
	result := []CPUPackage{}
	for offset := 0; offset+32 <= len(data); {
		length := int(binary.LittleEndian.Uint32(data[offset+4:]))
		if length < 32 || length > len(data)-offset {
			break
		}
		groups := int(binary.LittleEndian.Uint16(data[offset+30:]))
		if groups == 0 || groups > (length-32)/16 {
			break
		}
		cores := 0
		for j := 0; j < groups; j++ {
			cores += bits.OnesCount64(binary.LittleEndian.Uint64(data[offset+32+j*16:]))
		}
		index := len(result)
		result = append(result, CPUPackage{ID: fmt.Sprintf("package-%d", index), PackageID: index, Name: fmt.Sprintf("CPU %d", index+1), LogicalCores: cores})
		offset += length
	}
	return result
}
