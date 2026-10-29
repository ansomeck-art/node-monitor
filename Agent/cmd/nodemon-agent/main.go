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
	"nodemon/agent/internal/tailnet"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:9274", "HTTP(S) listen address")
	loopbackListen := flag.String("loopback-listen", "", "optional additional plaintext loopback listener, for example 127.0.0.1:9275")
	tailnetListen := flag.String("tailscale-listen", "", "optional tailnet-only listener, e.g. :9274 (never a public listener)")
	tailnetDir := flag.String("tailscale-dir", "./data/tailscale-agent", "private project-local Tailscale identity directory")
	tailnetHostname := flag.String("tailscale-hostname", "node-monitor-server", "name of the embedded Tailscale device")
	tailnetLogin := flag.Bool("tailscale-login", false, "authorize the embedded Tailscale device in an interactive terminal")
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
	if *tailnetLogin {
		if err := loginTailnet(*tailnetDir, *tailnetHostname); err != nil {
			log.Fatal("Tailscale authorization failed: ", err)
		}
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
	if *tailnetListen != "" {
		host, port, err := net.SplitHostPort(*tailnetListen)
		n, portErr := strconv.Atoi(port)
		if err != nil || host != "" || portErr != nil || n < 1 || n > 65535 {
			log.Fatal("--tailscale-listen must be a port such as :9274")
		}
	}
	token, err := loadToken(*tokenFile)
	if err != nil {
		log.Fatal("Cannot load/create private token file: ", err)
	}
	listener, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal("Cannot open listener: ", err)
	}
	defer listener.Close()
	var loopbackListener net.Listener
	if *loopbackListen != "" {
		loopbackListener, err = listenOnLoopback(*loopbackListen)
		if err != nil {
			log.Fatal("Cannot open additional loopback listener: ", err)
		}
		defer loopbackListener.Close()
	}
	handler := server.Handler(store, token)
	newServer := func() *http.Server {
		return &http.Server{Handler: handler, ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	}
	srv := newServer()
	var loopbackServer *http.Server
	if loopbackListener != nil {
		loopbackServer = newServer()
	}
	var tailnetNode *tailnet.Node
	var tailnetListener net.Listener
	var tailnetServer *http.Server
	if *tailnetListen != "" {
		tailnetNode, err = tailnet.New(*tailnetDir, *tailnetHostname)
		if err != nil {
			log.Fatal("Cannot initialize embedded Tailscale")
		}
		defer tailnetNode.Close()
		tailnetListener, err = tailnetNode.Server.Listen("tcp", *tailnetListen)
		if err != nil {
			log.Fatal("Cannot initialize tailnet-only listener")
		}
		defer tailnetListener.Close()
		tailnetServer = newServer()
	}
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
		ready := map[string]string{"url": scheme + "://" + listener.Addr().String()}
		if loopbackListener != nil {
			ready["loopbackURL"] = "http://" + loopbackListener.Addr().String()
		}
		data, _ := json.Marshal(ready)
		if err := privateWrite(*readyFile, data); err != nil {
			log.Fatal("Cannot write readiness file")
		}
		defer os.Remove(*readyFile)
	}
	log.Printf("nodemon-agent %s ready; interval=%s GPU interval=%s demo=%t; token contents are never logged", metrics.Version, *interval, *gpuInterval, *demo)
	serveErrors := make(chan error, 3)
	go func() {
		if *cert != "" {
			serveErrors <- srv.ServeTLS(listener, *cert, *key)
		} else {
			serveErrors <- srv.Serve(listener)
		}
	}()
	if loopbackServer != nil {
		go func() { serveErrors <- loopbackServer.Serve(loopbackListener) }()
	}
	if tailnetServer != nil {
		go func() { serveErrors <- tailnetServer.Serve(tailnetListener) }()
	}
	select {
	case err = <-serveErrors:
	case <-ctx.Done():
	}
	stop()
	shutdown, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdown)
	if loopbackServer != nil {
		_ = loopbackServer.Shutdown(shutdown)
	}
	if tailnetServer != nil {
		_ = tailnetServer.Shutdown(shutdown)
	}
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal("Server stopped: ", err)
	}
}

// Login links are displayed only on the controlling terminal, never in daemon
// logs or a generated file. Run this before enabling the embedded listener.
func loginTailnet(dir, hostname string) error {
	tty, err := os.OpenFile("/dev/tty", os.O_WRONLY, 0)
	if err != nil {
		return errors.New("an interactive terminal is required")
	}
	defer tty.Close()
	node, err := tailnet.New(dir, hostname)
	if err != nil {
		return errors.New("cannot initialize the private identity directory")
	}
	defer node.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	url, err := node.LoginURL(ctx)
	if err != nil {
		return errors.New("could not obtain an authorization link")
	}
	if url != "" {
		fmt.Fprintln(tty, "Open this private, temporary Tailscale authorization link in your browser:")
		fmt.Fprintln(tty, url)
		fmt.Fprintln(tty, "Waiting for authorization (do not share the link)...")
	}
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		status, err := node.Status(ctx)
		if err == nil && status.BackendState == "Running" {
			fmt.Fprintln(tty, "Tailscale authorization complete. Server address:")
			for _, ip := range status.TailscaleIPs {
				if ip.Is4() {
					fmt.Fprintln(tty, ip.String())
					break
				}
			}
			return nil
		}
		select {
		case <-ctx.Done():
			return errors.New("authorization timed out")
		case <-ticker.C:
		}
	}
}

func listenOnLoopback(address string) (net.Listener, error) {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return nil, fmt.Errorf("invalid loopback address")
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return nil, fmt.Errorf("additional listener must use a numeric loopback IP")
	}
	return net.Listen("tcp", address)
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
