package metrics

import "time"

const Version = "0.1.0"

type Snapshot struct {
	SchemaVersion int               `json:"schemaVersion"`
	AgentVersion  string            `json:"agentVersion"`
	SampledAt     time.Time         `json:"sampledAt"`
	Interval      float64           `json:"sampleIntervalSeconds"`
	GPUInterval   float64           `json:"gpuSampleIntervalSeconds"`
	Demo          bool              `json:"demo"`
	Host          Host              `json:"host"`
	CPU           CPU               `json:"cpu"`
	Memory        Memory            `json:"memory"`
	GPUs          []GPU             `json:"gpus"`
	Disks         []Disk            `json:"disks"`
	Network       Network           `json:"network"`
	Sensors       []Sensor          `json:"sensors"`
	Capabilities  map[string]string `json:"capabilities"`
	Warnings      []string          `json:"warnings"`
}
type Host struct {
	ID     string  `json:"id"`
	Name   string  `json:"name"`
	OS     string  `json:"os"`
	Arch   string  `json:"arch"`
	Kernel string  `json:"kernel"`
	Uptime float64 `json:"uptimeSeconds"`
}
type CPU struct {
	Usage    *float64     `json:"usagePercent"`
	Cores    int          `json:"cores"`
	Load     []float64    `json:"loadAverage"`
	Packages []CPUPackage `json:"packages,omitempty"`
}
type CPUPackage struct {
	ID           string   `json:"id"`
	PackageID    int      `json:"packageID"`
	Name         string   `json:"name"`
	LogicalCores int      `json:"logicalCores"`
	Usage        *float64 `json:"usagePercent"`
	Temperature  *float64 `json:"temperatureCelsius"`
}
type Memory struct {
	Total     uint64   `json:"totalBytes"`
	Used      uint64   `json:"usedBytes"`
	Available uint64   `json:"availableBytes"`
	SwapTotal uint64   `json:"swapTotalBytes"`
	SwapUsed  uint64   `json:"swapUsedBytes"`
	Usage     *float64 `json:"usagePercent"`
}
type GPU struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Vendor      string    `json:"vendor"`
	Usage       *float64  `json:"usagePercent"`
	Temperature *float64  `json:"temperatureCelsius"`
	MemoryUsed  *uint64   `json:"memoryUsedBytes"`
	MemoryTotal *uint64   `json:"memoryTotalBytes"`
	Power       *float64  `json:"powerWatts"`
	Fan         *float64  `json:"fanPercent"`
	SampledAt   time.Time `json:"sampledAt"`
	Status      string    `json:"status"`
}
type Disk struct {
	ID         string `json:"id"`
	Mount      string `json:"mount"`
	Filesystem string `json:"filesystem"`
	Total      uint64 `json:"totalBytes"`
	Used       uint64 `json:"usedBytes"`
	Available  uint64 `json:"availableBytes"`
}
type Network struct {
	Receive    *float64           `json:"receiveBytesPerSecond"`
	Send       *float64           `json:"sendBytesPerSecond"`
	Read       *float64           `json:"diskReadBytesPerSecond"`
	Write      *float64           `json:"diskWriteBytesPerSecond"`
	Interfaces []NetworkInterface `json:"interfaces"`
}
type NetworkInterface struct {
	Name     string   `json:"name"`
	Received uint64   `json:"receivedBytes"`
	Sent     uint64   `json:"sentBytes"`
	Receive  *float64 `json:"receiveBytesPerSecond"`
	Send     *float64 `json:"sendBytesPerSecond"`
	Virtual  bool     `json:"virtual"`
}
type Sensor struct {
	ID      string  `json:"id"`
	Name    string  `json:"name"`
	Kind    string  `json:"kind"`
	Celsius float64 `json:"celsius"`
	Source  string  `json:"source"`
}

func Number(v float64) *float64 { return &v }
func Bytes(v uint64) *uint64    { return &v }
