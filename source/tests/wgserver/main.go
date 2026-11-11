// Isolated WireGuard fixture: no administrator rights, OS TUN or route changes.
package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"time"
)

// Non-repeating deterministic bytes exercise transport integrity, rather than
// merely counting a repeated zero buffer. Keep this algorithm in direct-http.py.
func payload() []byte {
	data := make([]byte, 2*1024*1024)
	state := uint32(0x6d2b79f5)
	for i := range data {
		state = 1664525*state + 1013904223
		data[i] = byte(state >> 24)
	}
	return data
}
func main() {
	body := payload()
	digest := fmt.Sprintf("%x", sha256.Sum256(body))
	if len(os.Args) == 2 && os.Args[1] == "--payload-info" {
		fmt.Printf("bytes=%d sha256=%s\n", len(body), digest)
		return
	}
	if len(os.Args) != 2 {
		log.Fatal("usage: wgfixture <private-test-config.json>")
	}
	server, _ := ecdh.X25519().GenerateKey(rand.Reader)
	client, _ := ecdh.X25519().GenerateKey(rand.Reader)
	t, stack, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.88.0.1")}, nil, 1280)
	if err != nil {
		log.Fatal(err)
	}
	// StdNetBind disables Windows UDP connection-reset errors from a vanished peer.
	// A raw Windows RIO bind makes this localhost failure fixture stop receiving.
	dev := device.NewDevice(t, conn.NewStdNetBind(), device.NewLogger(device.LogLevelVerbose, "fixture: "))
	defer dev.Close()
	err = dev.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=0\npublic_key=%s\nallowed_ip=10.88.0.2/32\n", hex.EncodeToString(server.Bytes()), hex.EncodeToString(client.PublicKey().Bytes())))
	if err != nil {
		log.Fatal(err)
	}
	if err = dev.Up(); err != nil {
		log.Fatal(err)
	}
	status, _ := dev.IpcGet()
	port := ""
	for _, line := range strings.Split(status, "\n") {
		if strings.HasPrefix(line, "listen_port=") {
			port = strings.TrimPrefix(line, "listen_port=")
		}
	}
	go func() {
		for range time.Tick(5 * time.Second) {
			s, _ := dev.IpcGet()
			for _, line := range strings.Split(s, "\n") {
				if strings.HasPrefix(line, "rx_bytes=") || strings.HasPrefix(line, "tx_bytes=") || strings.HasPrefix(line, "endpoint=") {
					fmt.Println("STATS", line)
				}
			}
		}
	}()
	data, _ := json.Marshal(map[string]string{"port": port, "serverKey": base64.StdEncoding.EncodeToString(server.PublicKey().Bytes()), "clientKey": base64.StdEncoding.EncodeToString(client.Bytes())})
	if err = os.WriteFile(os.Args[1], data, 0600); err != nil {
		log.Fatal(err)
	}
	dns, err := stack.ListenUDP(&net.UDPAddr{IP: net.IPv4(10, 88, 0, 1), Port: 53})
	if err != nil {
		log.Fatal(err)
	}
	go func() {
		for {
			buf := make([]byte, 1500)
			n, a, e := dns.ReadFrom(buf)
			if e != nil {
				return
			}
			if n < 17 {
				continue
			}
			q := buf[:n]
			q[2] = 0x81
			q[3] = 0x80
			q[6] = 0
			q[7] = 0
			q[8] = 0
			q[9] = 0
			q[10] = 0
			q[11] = 0
			// Fixture resolves A questions to the in-tunnel HTTP endpoint; AAAA is empty.
			if q[n-4] == 0 && q[n-3] == 1 {
				q[7] = 1
				q = append(q, 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 30, 0, 4, 10, 88, 0, 1)
			}
			dns.WriteTo(q, a)
		}
	}()
	listener, err := stack.ListenTCP(&net.TCPAddr{IP: net.IPv4(10, 88, 0, 1), Port: 80})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("READY userspace WireGuard fixture ephemeral UDP; HTTP 204 inside encrypted tunnel")
	log.Fatal(http.Serve(listener, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Println("HTTP", r.Host, r.URL.Path)
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Fixture-Route", "wireguard")
		if r.URL.Path == "/payload" {
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Header().Set("Content-Length", fmt.Sprint(len(body)))
			w.Header().Set("X-Payload-SHA256", digest)
			w.WriteHeader(200)
			n, e := w.Write(body)
			fmt.Println("PAYLOAD", n, e)
			return
		}
		if r.URL.Path == "/payload.sha256" {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			fmt.Fprintln(w, digest)
			return
		}
		w.WriteHeader(204)
	})))
}
