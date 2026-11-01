// Package transport is an app-contained WireGuard/HTTPS client. It creates no OS
// interface, Android VpnService, routes or DNS settings. Ordinary UDP follows the
// current OS network policy. No direct HTTP or public relay fallback exists.
package transport

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type Configuration struct {
	Address              string   `json:"address"`
	Endpoint             string   `json:"endpoint"`
	AlternateEndpoints   []string `json:"alternate_endpoints,omitempty"`
	PrivateKey           string   `json:"private_key"`
	ServerKey            string   `json:"server_key"`
	PresharedKey         string   `json:"preshared_key,omitempty"`
	API                  string   `json:"api"`
	CertificateAuthority string   `json:"ca_pem"`
}
type Request struct {
	Method string `json:"method"`
	Path   string `json:"path"`
	Token  string `json:"token"`
	Body   string `json:"body"`
}
type Response struct {
	Status int    `json:"status"`
	Body   string `json:"body"`
	Binary string `json:"binary,omitempty"`
}
type peer struct {
	device        *device.Device
	http          *http.Client
	transport     *http.Transport
	cancel        context.CancelFunc
	ctx           context.Context
	origin        string
	server        string
	endpoints     []string
	endpointIndex int
	requests      sync.Mutex
}

var current struct {
	sync.RWMutex
	p *peer
}
var slots = make(chan struct{}, 4)

func decode(raw string, out any) error {
	d := json.NewDecoder(strings.NewReader(raw))
	d.DisallowUnknownFields()
	if err := d.Decode(out); err != nil {
		return errors.New("invalid configuration/request")
	}
	if d.Decode(new(any)) != io.EOF {
		return errors.New("extra JSON data")
	}
	return nil
}
func key(s string) (string, error) {
	b, e := base64.StdEncoding.Strict().DecodeString(s)
	if e != nil || len(b) != 32 || bytes.Equal(b, make([]byte, 32)) {
		return "", errors.New("invalid WireGuard key")
	}
	return hex.EncodeToString(b), nil
}
func Keys() (string, error) {
	private := make([]byte, 32)
	if _, e := rand.Read(private); e != nil {
		return "", e
	}
	private[0] &= 248
	private[31] &= 127
	private[31] |= 64
	public, e := curve25519.X25519(private, curve25519.Basepoint)
	if e != nil {
		return "", e
	}
	b, e := json.Marshal(map[string]string{"private_key": base64.StdEncoding.EncodeToString(private), "public_key": base64.StdEncoding.EncodeToString(public)})
	return string(b), e
}
func Configure(raw string) error {
	if len(raw) > 20000 {
		return errors.New("configuration too large")
	}
	var c Configuration
	if e := decode(raw, &c); e != nil {
		return e
	}
	local, e := netip.ParsePrefix(c.Address)
	if e != nil || !local.Addr().Is4() || !local.Addr().IsPrivate() || local.Bits() != 32 {
		return errors.New("a private IPv4 /32 client address is required")
	}
	if !strings.HasPrefix(c.API, "https://") {
		return errors.New("HTTPS is required inside the tunnel")
	}
	api, e := netip.ParseAddrPort(strings.TrimPrefix(c.API, "https://"))
	if e != nil || !api.Addr().Is4() || !api.Addr().IsPrivate() || api.Port() == 0 || api.Addr() == local.Addr() {
		return errors.New("API must be a private numeric IPv4 address and port")
	}
	if len(c.AlternateEndpoints) > 2 {
		return errors.New("too many outer endpoints")
	}
	var endpoints []string
	for _, candidate := range append([]string{c.Endpoint}, c.AlternateEndpoints...) {
		if len(candidate) > 260 || strings.ContainsAny(candidate, "\n\r\x00") {
			return errors.New("invalid UDP endpoint")
		}
		host, port, err := net.SplitHostPort(candidate)
		if err != nil || host == "" || port == "" {
			return errors.New("invalid UDP endpoint")
		}
		endpoint, err := net.ResolveUDPAddr("udp", candidate)
		if err == nil && endpoint.Port > 0 && !endpoint.IP.IsUnspecified() && !endpoint.IP.IsMulticast() {
			endpoints = append(endpoints, endpoint.String())
		}
	}
	if len(endpoints) == 0 {
		return errors.New("no trusted UDP endpoint can be resolved")
	}
	private, e := key(c.PrivateKey)
	if e != nil {
		return e
	}
	server, e := key(c.ServerKey)
	if e != nil {
		return e
	}
	psk := ""
	if c.PresharedKey != "" {
		psk, e = key(c.PresharedKey)
		if e != nil {
			return e
		}
		psk = "preshared_key=" + psk + "\n"
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM([]byte(c.CertificateAuthority)) {
		return errors.New("trusted server CA certificate is required")
	}
	tun, stack, e := netstack.CreateNetTUN([]netip.Addr{local.Addr()}, nil, 1280)
	if e != nil {
		return errors.New("private network creation failed")
	}
	wg := device.NewDevice(tun, conn.NewDefaultBind(), &device.Logger{Verbosef: func(string, ...any) {}, Errorf: func(string, ...any) {}})
	// Scheduled client-initiated sync does not need an always-on NAT heartbeat.
	config := fmt.Sprintf("private_key=%s\npublic_key=%s\n%sallowed_ip=%s/32\nendpoint=%s\npersistent_keepalive_interval=0\n", private, server, psk, api.Addr(), endpoints[0])
	if e = wg.IpcSet(config); e == nil {
		e = wg.Up()
	}
	if e != nil {
		wg.Close()
		return errors.New("WireGuard peer setup failed")
	}
	ctx, cancel := context.WithCancel(context.Background())
	tr := &http.Transport{Proxy: nil, DisableCompression: true, MaxConnsPerHost: 4, MaxIdleConns: 4, IdleConnTimeout: 45 * time.Second, TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 15 * time.Second, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS13, RootCAs: roots}, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		if network != "tcp" || address != api.String() {
			return nil, errors.New("only the paired device API is allowed")
		}
		return stack.DialContextTCPAddrPort(ctx, api)
	}}
	p := &peer{device: wg, http: &http.Client{Transport: tr, Timeout: 12 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, transport: tr, cancel: cancel, ctx: ctx, origin: c.API, server: server, endpoints: endpoints}
	current.Lock()
	old := current.p
	current.p = p
	current.Unlock()
	if old != nil {
		old.cancel()
		old.transport.CloseIdleConnections()
		old.device.Close()
	}
	return nil
}
func Stop() {
	current.Lock()
	old := current.p
	current.p = nil
	current.Unlock()
	if old != nil {
		old.cancel()
		old.transport.CloseIdleConnections()
		old.device.Close()
	}
}
func Exchange(raw string) (string, error) {
	if len(raw) > 6_600_000 {
		return "", errors.New("request exceeds size limit")
	}
	var r Request
	if e := decode(raw, &r); e != nil {
		return "", e
	}
	if r.Method != "GET" && r.Method != "POST" {
		return "", errors.New("unsupported HTTP method")
	}
	if !devicePath(r.Path) || strings.ContainsAny(r.Path, "?#%\\\r\n") || strings.Contains(r.Path, "..") || len(r.Path) > 150 {
		return "", errors.New("invalid device API path")
	}
	if r.Token != "" {
		if len(r.Token) != 64 {
			return "", errors.New("invalid session")
		}
		if _, e := hex.DecodeString(r.Token); e != nil {
			return "", errors.New("invalid session")
		}
	}
	select {
	case slots <- struct{}{}:
		defer func() { <-slots }()
	default:
		return "", errors.New("too many concurrent requests")
	}
	current.RLock()
	p := current.p
	current.RUnlock()
	if p == nil {
		return "", errors.New("pair a device first")
	}
	p.requests.Lock()
	defer p.requests.Unlock()
	var response *http.Response
	var e error
	// Only the trusted outer UDP address changes. WG identity, PSK, inner
	// destination and TLS trust stay fixed; retry preserves the exact body.
	for attempt := 0; attempt < len(p.endpoints); attempt++ {
		request, err := http.NewRequestWithContext(p.ctx, r.Method, p.origin+r.Path, strings.NewReader(r.Body))
		if err != nil {
			return "", errors.New("request could not be created")
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-Field-Protocol", "1")
		if r.Token != "" {
			request.Header.Set("Authorization", "Bearer "+r.Token)
		}
		response, e = p.http.Do(request)
		if e == nil {
			break
		}
		if attempt+1 < len(p.endpoints) {
			p.endpointIndex = (p.endpointIndex + 1) % len(p.endpoints)
			p.transport.CloseIdleConnections()
			if err = p.device.IpcSet(fmt.Sprintf("public_key=%s\nupdate_only=true\nendpoint=%s\n", p.server, p.endpoints[p.endpointIndex])); err != nil {
				break
			}
		}
	}
	if e != nil {
		return "", errors.New("server unavailable or TLS verification failed")
	}
	defer response.Body.Close()
	data, e := io.ReadAll(io.LimitReader(response.Body, 16*1024*1024+1))
	if e != nil || len(data) > 16*1024*1024 {
		return "", errors.New("response incomplete or exceeds size limit")
	}
	out := Response{Status: response.StatusCode}
	if response.Header.Get("Content-Type") == "image/png" || (strings.HasPrefix(r.Path, "/api/device/update/chunks/") && response.Header.Get("Content-Type") == "application/octet-stream") {
		out.Binary = base64.StdEncoding.EncodeToString(data)
	} else {
		out.Body = string(data)
	}
	encoded, e := json.Marshal(out)
	return string(encoded), e
}

func devicePath(path string) bool {
	switch path {
	case "/health", "/api/login", "/api/logout", "/api/session", "/api/reauth", "/api/snapshot", "/api/people", "/api/head", "/api/commands", "/api/device/activate", "/api/device/revoke-self", "/api/device/update":
		return true
	}
	if tail, ok := strings.CutPrefix(path, "/api/device/update/chunks/"); ok {
		parts := strings.Split(tail, "/")
		if len(parts) != 2 || len(parts[0]) != 64 || len(parts[1]) < 1 || len(parts[1]) > 4 {
			return false
		}
		for _, c := range parts[0] {
			if !strings.ContainsRune("0123456789abcdef", c) {
				return false
			}
		}
		for _, c := range parts[1] {
			if c < '0' || c > '9' {
				return false
			}
		}
		return true
	}
	if strings.HasPrefix(path, "/api/images/") {
		id := strings.TrimPrefix(path, "/api/images/")
		if len(id) != 36 {
			return false
		}
		for i, c := range id {
			if i == 8 || i == 13 || i == 18 || i == 23 {
				if c != '-' {
					return false
				}
			} else if !strings.ContainsRune("0123456789abcdefABCDEF", c) {
				return false
			}
		}
		return true
	}
	return false
}
