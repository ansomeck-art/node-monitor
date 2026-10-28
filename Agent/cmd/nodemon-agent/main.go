package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"nodemon/agent/internal/metrics"
	"nodemon/agent/internal/server"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:9274", "HTTP(S) listen address")
	tokenFile := flag.String("token-file", "./data/agent.token", "private bearer-token file (auto-created, never logged)")
	interval := flag.Duration("interval", 5*time.Second, "base collection interval, minimum 2s")
	gpuInterval := flag.Duration("gpu-interval", 15*time.Second, "GPU query interval, minimum base interval")
	gpuOff := flag.Bool("no-gpu", false, "disable GPU queries (including nvidia-smi)")
	cert := flag.String("tls-cert", "", "PEM TLS certificate path")
	key := flag.String("tls-key", "", "PEM TLS private-key path")
	plain := flag.Bool("allow-http-lan", false, "explicitly allow plaintext on a trusted LAN/VPN; otherwise TLS or loopback required")
	demo := flag.Bool("demo", false, "explicit simulated metrics; works on macOS for UI/protocol verification")
	readyFile := flag.String("ready-file", "", "write chosen address here, useful with --listen 127.0.0.1:0")
	once := flag.Bool("once", false, "print one metrics JSON sample and exit (no token output)")
	version := flag.Bool("version", false, "print version and exit")
	flag.Parse()
	if *version {
		fmt.Println("nodemon-agent " + metrics.Version)
		return
	}
	if !*demo && runtime.GOOS != "linux" {
		log.Fatal("Real collection requires Linux. Use --demo only for simulated data.")
	}
	if *interval < 2*time.Second || *gpuInterval < *interval {
		log.Fatal("Require interval >= 2s and gpu-interval >= interval")
	}
	collector := metrics.NewCollector(*gpuInterval, !*gpuOff)
	collect := func(now time.Time) metrics.Snapshot {
		if *demo {
			return metrics.DemoSnapshot(now, interval.Seconds())
		}
		return collector.Collect(now, interval.Seconds())
	}
	store := &server.Store{}
	store.Put(collect(time.Now()))
	if *once {
		_ = json.NewEncoder(os.Stdout).Encode(store.Get())
		return
	}
	host, _, err := net.SplitHostPort(*listen)
	if err != nil {
		log.Fatal("Invalid listen address")
	}
	ip := net.ParseIP(host)
	loopback := host == "localhost" || (ip != nil && ip.IsLoopback())
	if !loopback && (*cert == "" || *key == "") && !*plain {
		log.Fatal("Non-loopback listeners require TLS, or explicit --allow-http-lan on a trusted LAN/VPN")
	}
	if (*cert == "") != (*key == "") {
		log.Fatal("Both --tls-cert and --tls-key are required")
	}
	token, err := loadToken(*tokenFile)
	if err != nil {
		log.Fatal("Cannot load/create private token file: ", err)
	}
	listener, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal("Cannot open listener: ", err)
	}
	srv := &http.Server{Handler: server.Handler(store, token), ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		ticker := time.NewTicker(*interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-ticker.C:
				store.Put(collect(now))
			}
		}
	}()
	if *readyFile != "" {
		scheme := "http"
		if *cert != "" {
			scheme = "https"
		}
		data, _ := json.Marshal(map[string]string{"url": scheme + "://" + listener.Addr().String()})
		if err := privateWrite(*readyFile, data); err != nil {
			log.Fatal("Cannot write readiness file")
		}
		defer os.Remove(*readyFile)
	}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdown)
	}()
	log.Printf("nodemon-agent %s ready; interval=%s GPU interval=%s demo=%t; token contents are never logged", metrics.Version, *interval, *gpuInterval, *demo)
	if *cert != "" {
		err = srv.ServeTLS(listener, *cert, *key)
	} else {
		err = srv.Serve(listener)
	}
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal("Server stopped: ", err)
	}
}
func loadToken(path string) (string, error) {
	if info, err := os.Stat(path); err == nil {
		if !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
			return "", fmt.Errorf("token file must be a regular file with permission 0600")
		}
		b, err := os.ReadFile(path)
		if err != nil {
			return "", err
		}
		token := strings.TrimSpace(string(b))
		if len(token) < 32 {
			return "", fmt.Errorf("token must have at least 32 characters")
		}
		return token, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", err
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	encoded := base64.RawURLEncoding.EncodeToString(b)
	return encoded, privateWrite(path, []byte(encoded+"\n"))
}
func privateWrite(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	if err = f.Chmod(0600); err != nil {
		f.Close()
		return err
	}
	_, err = f.Write(data)
	closeErr := f.Close()
	if err != nil {
		return err
	}
	return closeErr
}
