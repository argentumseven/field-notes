package transport

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"time"
)

type bootstrapRequest struct {
	URL  string `json:"url"`
	CA   string `json:"ca"`
	Path string `json:"path"`
	Body string `json:"body"`
}

// Bootstrap uses only the explicitly trusted LAN enrollment origin. It carries
// an invitation/claim, never an existing notes session or a phone private key.
// It creates no tunnel, routes, VPN service, redirect or plaintext fallback.
func Bootstrap(raw string) (string, error) {
	if len(raw) > 20000 {
		return "", errors.New("enrollment request too large")
	}
	var input bootstrapRequest
	if err := decode(raw, &input); err != nil {
		return "", err
	}
	return bootstrap(input, nil)
}

func bootstrap(input bootstrapRequest, dial func(context.Context, string, string) (net.Conn, error)) (string, error) {
	origin, err := url.Parse(input.URL)
	if err != nil || origin.Scheme != "https" || origin.User != nil || origin.RawPath != "" || origin.ForceQuery || origin.RawQuery != "" || origin.Fragment != "" || origin.Path != "/api/enrollment" {
		return "", errors.New("enrollment requires the trusted LAN HTTPS origin")
	}
	address, err := netip.ParseAddr(origin.Hostname())
	if err != nil || !address.Is4() || !address.IsPrivate() {
		return "", errors.New("enrollment is available on your home LAN only")
	}
	if input.Path != "/claim" && input.Path != "/status" && input.Path != "/cancel" {
		return "", errors.New("invalid enrollment action")
	}
	if len(input.CA) > 12000 || len(input.Body) > 4096 {
		return "", errors.New("enrollment data exceeds its bounds")
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM([]byte(input.CA)) {
		return "", errors.New("trusted enrollment CA required")
	}
	transport := &http.Transport{Proxy: nil, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS13, RootCAs: roots}, TLSHandshakeTimeout: 8 * time.Second, ResponseHeaderTimeout: 8 * time.Second, MaxConnsPerHost: 1}
	// The optional dialer is package-private for a local TLS fixture. Untrusted
	// requests cannot set it; ordinary app sockets follow Android's network policy.
	if dial != nil {
		transport.DialContext = dial
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 12 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	request, err := http.NewRequest(http.MethodPost, input.URL+input.Path, strings.NewReader(input.Body))
	if err != nil {
		return "", errors.New("invalid enrollment request")
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Field-Protocol", "1")
	response, err := client.Do(request)
	if err != nil {
		return "", errors.New("home enrollment server unavailable or its TLS identity could not be verified")
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 32769))
	if err != nil || len(data) > 32768 {
		return "", errors.New("enrollment response incomplete or too large")
	}
	if response.StatusCode >= 300 && response.StatusCode < 400 {
		return "", errors.New("enrollment redirects are not allowed")
	}
	encoded, err := json.Marshal(Response{Status: response.StatusCode, Body: string(data)})
	return string(encoded), err
}
