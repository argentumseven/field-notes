package transport

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func pair(t *testing.T) map[string]string {
	t.Helper()
	s, e := Keys()
	if e != nil {
		t.Fatal(e)
	}
	var out map[string]string
	if e = json.Unmarshal([]byte(s), &out); e != nil {
		t.Fatal(e)
	}
	return out
}
func TestRealWireGuardHTTPSAndBoundaries(t *testing.T) {
	serverKeys, clientKeys := pair(t), pair(t)
	serverTun, stack, e := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.82.0.1")}, nil, 1280)
	if e != nil {
		t.Fatal(e)
	}
	server := device.NewDevice(serverTun, conn.NewDefaultBind(), &device.Logger{Verbosef: func(string, ...any) {}, Errorf: func(string, ...any) {}})
	defer server.Close()
	udp, e := net.ListenPacket("udp4", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	port := udp.LocalAddr().(*net.UDPAddr).Port
	udp.Close()
	private, _ := key(serverKeys["private_key"])
	public, _ := key(clientKeys["public_key"])
	if e = server.IpcSet("private_key=" + private + "\nlisten_port=" + big.NewInt(int64(port)).String() + "\npublic_key=" + public + "\nallowed_ip=10.82.0.2/32\n"); e != nil {
		t.Fatal(e)
	}
	if e = server.Up(); e != nil {
		t.Fatal(e)
	}
	signing, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Field Notes fixture"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IPAddresses: []net.IP{net.ParseIP("10.82.0.1")}, IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, e := x509.CreateCertificate(rand.Reader, template, template, &signing.PublicKey, signing)
	if e != nil {
		t.Fatal(e)
	}
	ca := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	listener, e := stack.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.82.0.1"), Port: 8443})
	if e != nil {
		t.Fatal(e)
	}
	tlsListener := tls.NewListener(listener, &tls.Config{MinVersion: tls.VersionTLS13, Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: signing}}})
	api := &http.Server{ReadHeaderTimeout: time.Second, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path != "/api/head" {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte(`{"sequence":42}`))
	})}
	go api.Serve(tlsListener)
	defer api.Shutdown(context.Background())
	c := Configuration{Address: "10.82.0.2/32", Endpoint: net.JoinHostPort("127.0.0.1", big.NewInt(int64(port)).String()), PrivateKey: clientKeys["private_key"], ServerKey: serverKeys["public_key"], API: "https://10.82.0.1:8443", CertificateAuthority: string(ca)}
	config, _ := json.Marshal(c)
	if e = Configure(string(config)); e != nil {
		t.Fatal(e)
	}
	defer Stop()
	response, e := Exchange(`{"method":"GET","path":"/api/head","token":"","body":""}`)
	if e != nil {
		t.Fatal(e)
	}
	var decoded Response
	if e = json.Unmarshal([]byte(response), &decoded); e != nil {
		t.Fatal(e)
	}
	if decoded.Status != 200 || decoded.Body != `{"sequence":42}` {
		t.Fatalf("unexpected result: %s", response)
	}
	for _, path := range []string{"/api/admin", "/api/../admin", "https://elsewhere.invalid/api/head", "/api/head?redirect=yes"} {
		b, _ := json.Marshal(Request{Method: "GET", Path: path})
		if _, e = Exchange(string(b)); e == nil {
			t.Fatalf("accepted forbidden path %s", path)
		}
	}
	if _, e = Exchange(strings.Repeat("a", 6_600_001)); e == nil {
		t.Fatal("oversized request accepted")
	}
	wrongSigning, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	wrongDER, e := x509.CreateCertificate(rand.Reader, template, template, &wrongSigning.PublicKey, wrongSigning)
	if e != nil {
		t.Fatal(e)
	}
	c.CertificateAuthority = string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: wrongDER}))
	wrongConfig, _ := json.Marshal(c)
	if e = Configure(string(wrongConfig)); e != nil {
		t.Fatal(e)
	}
	if _, e = Exchange(`{"method":"GET","path":"/api/head"}`); e == nil {
		t.Fatal("untrusted TLS server accepted")
	}
	Stop()
	if _, e = Exchange(`{"method":"GET","path":"/api/head"}`); e == nil {
		t.Fatal("stopped transport accepted request")
	}
}
func TestInvalidConfigurationNeverCreatesAnOSRoute(t *testing.T) {
	for _, raw := range []string{`{}`, `{"address":"0.0.0.0/0"}`, `{"extra":"unexpected"}`} {
		if e := Configure(raw); e == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
}
