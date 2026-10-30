package transport

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestBootstrapPinsLANIdentityAndRejectsRedirectsAndOversize(t *testing.T) {
	signing, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(12), NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IPAddresses: []net.IP{net.ParseIP("192.168.1.10")}, IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &signing.PublicKey, signing)
	if err != nil {
		t.Fatal(err)
	}
	requests := 0
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.TLS.Version != tls.VersionTLS13 || r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
			t.Error("Invalid bootstrap boundary")
		}
		body, _ := io.ReadAll(r.Body)
		switch string(body) {
		case "redirect":
			http.Redirect(w, r, "https://example.invalid", 302)
		case "oversize":
			io.WriteString(w, strings.Repeat("x", 32769))
		default:
			io.WriteString(w, `{"state":"pending_approval"}`)
		}
	}))
	server.TLS = &tls.Config{MinVersion: tls.VersionTLS13, Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: signing}}}
	server.StartTLS()
	defer server.Close()
	dial := func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
	}
	input := bootstrapRequest{URL: "https://192.168.1.10:9443/api/enrollment", CA: string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), Path: "/claim", Body: "{}"}
	if _, err = bootstrap(input, dial); err != nil {
		t.Fatal(err)
	}
	for _, body := range []string{"redirect", "oversize"} {
		copy := input
		copy.Body = body
		if _, err = bootstrap(copy, dial); err == nil {
			t.Fatalf("accepted %s", body)
		}
	}
	before := requests
	for _, origin := range []string{"http://192.168.1.10/api/enrollment", "https://8.8.8.8/api/enrollment", "https://192.168.1.10:9443/api/enrollment?", "https://192.168.1.10:9443/api/enrollment#x", "https://192.168.1.10:9443/api/%65nrollment"} {
		copy := input
		copy.URL = origin
		if _, err = bootstrap(copy, dial); err == nil {
			t.Fatalf("accepted origin %s", origin)
		}
	}
	for _, path := range []string{"/snapshot", "/../admin", "/claim?x=1"} {
		copy := input
		copy.Path = path
		if _, err = bootstrap(copy, dial); err == nil {
			t.Fatalf("accepted path %s", path)
		}
	}
	if requests != before {
		t.Fatal("invalid requests reached the network")
	}
	// Same pinned CA cannot authorize a different LAN host identity.
	input.URL = "https://192.168.1.11:9443/api/enrollment"
	if _, err = bootstrap(input, dial); err == nil {
		t.Fatal("hostname mismatch accepted")
	}
}
