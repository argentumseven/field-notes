// Disposable integration-test hub, never part of the installed server. The only
// control input is a local public-key file. Private peer/TLS keys stay in memory.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fieldnotes/transport"
	"flag"
	"fmt"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
	"math/big"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

func main() {
	clientHost := flag.String("client-host", "10.0.2.2", "test client host route")
	backend := flag.String("backend", "", "loopback device backend")
	peerFile := flag.String("peer-file", "", "local test phone public key")
	configFile := flag.String("config-file", "", "public connection JSON destination")
	peerAckFile := flag.String("peer-ack-file", "", "optional installed test-peer acknowledgement")
	requestGate := flag.String("request-gate-directory", "", "optional local JNI cancellation-test gate")
	flag.Parse()
	target, e := url.Parse(*backend)
	if e != nil || target.Scheme != "http" || target.Hostname() != "127.0.0.1" || *peerFile == "" || *configFile == "" {
		panic("explicit local fixture paths/backend required")
	}
	raw, e := transport.Keys()
	if e != nil {
		panic(e)
	}
	var keys map[string]string
	if e = json.Unmarshal([]byte(raw), &keys); e != nil {
		panic(e)
	}
	private, e := base64.StdEncoding.DecodeString(keys["private_key"])
	if e != nil {
		panic(e)
	}
	tun, stack, e := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.82.0.1")}, nil, 1280)
	if e != nil {
		panic(e)
	}
	wg := device.NewDevice(tun, conn.NewDefaultBind(), &device.Logger{Verbosef: func(string, ...any) {}, Errorf: func(string, ...any) {}})
	defer wg.Close()
	portReservation, e := net.ListenPacket("udp", "127.0.0.1:0")
	if e != nil {
		panic(e)
	}
	port := portReservation.LocalAddr().(*net.UDPAddr).Port
	portReservation.Close()
	if e = wg.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\n", hex.EncodeToString(private), port)); e != nil {
		panic(e)
	}
	if e = wg.Up(); e != nil {
		panic(e)
	}
	signing, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		panic(e)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Field Notes emulator fixture"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IPAddresses: []net.IP{net.ParseIP("10.82.0.1")}, IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, e := x509.CreateCertificate(rand.Reader, cert, cert, &signing.PublicKey, signing)
	if e != nil {
		panic(e)
	}
	listener, e := stack.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.82.0.1"), Port: 8443})
	if e != nil {
		panic(e)
	}
	tlsListener := tls.NewListener(listener, &tls.Config{MinVersion: tls.VersionTLS13, Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: signing}}})
	proxy := httputil.NewSingleHostReverseProxy(target)
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if *requestGate != "" && r.URL.Path == "/api/head" {
			if _, err := os.Stat(filepath.Join(*requestGate, "armed")); err == nil {
				if err = os.WriteFile(filepath.Join(*requestGate, "entered"), []byte("entered"), 0600); err != nil {
					panic(err)
				}
				select {
				case <-r.Context().Done():
				case <-time.After(20 * time.Second):
				}
				return
			}
		}
		proxy.ServeHTTP(w, r)
	}), ReadHeaderTimeout: 10 * time.Second}
	go server.Serve(tlsListener)
	config := map[string]string{"address": "10.82.0.2/32", "endpoint": net.JoinHostPort(*clientHost, fmt.Sprint(port)), "server_key": keys["public_key"], "api": "https://10.82.0.1:8443", "ca_pem": string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))}
	bytes, _ := json.Marshal(config)
	if e = os.WriteFile(*configFile, bytes, 0600); e != nil {
		panic(e)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	last := ""
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			stop, cancel := context.WithTimeout(context.Background(), time.Second)
			server.Shutdown(stop)
			cancel()
			return
		case <-ticker.C:
			contents, e := os.ReadFile(*peerFile)
			if e != nil || len(contents) > 128 {
				continue
			}
			encoded := strings.TrimSpace(string(contents))
			if encoded == last {
				continue
			}
			public, e := base64.StdEncoding.Strict().DecodeString(encoded)
			if e != nil || len(public) != 32 {
				continue
			}
			if last != "" {
				old, _ := base64.StdEncoding.DecodeString(last)
				wg.IpcSet("public_key=" + hex.EncodeToString(old) + "\nremove=true\n")
			}
			if e = wg.IpcSet("public_key=" + hex.EncodeToString(public) + "\nallowed_ip=10.82.0.2/32\n"); e == nil {
				last = encoded
				if *peerAckFile != "" {
					if e = os.WriteFile(*peerAckFile, []byte(encoded), 0600); e != nil {
						panic(e)
					}
				}
			}
		}
	}
}
