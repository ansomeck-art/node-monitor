package metrics

import (
	"encoding/csv"
	"strconv"
	"strings"
	"time"
)

func optionalNumber(value string) *float64 {
	v, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
	if err != nil || v < 0 {
		return nil
	}
	return Number(v)
}
func parseNVIDIA(text string, now time.Time) []GPU {
	rows, err := csv.NewReader(strings.NewReader(text)).ReadAll()
	if err != nil {
		return nil
	}
	result := []GPU{}
	for _, row := range rows {
		if len(row) < 8 {
			continue
		}
		for i := range row {
			row[i] = strings.TrimSpace(row[i])
		}
		if row[0] == "" {
			continue
		}
		g := GPU{ID: row[0], Name: row[1], Vendor: "NVIDIA", Usage: optionalNumber(row[2]), Temperature: optionalNumber(row[3]), Power: optionalNumber(row[6]), Fan: optionalNumber(row[7]), SampledAt: now.UTC(), Status: "ok"}
		if v := optionalNumber(row[4]); v != nil {
			g.MemoryUsed = Bytes(uint64(*v * 1024 * 1024))
		}
		if v := optionalNumber(row[5]); v != nil {
			g.MemoryTotal = Bytes(uint64(*v * 1024 * 1024))
		}
		if g.Usage == nil || g.Temperature == nil {
			g.Status = "partial"
		}
		result = append(result, g)
	}
	return result
}
