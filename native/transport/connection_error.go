package transport

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"syscall"
	"time"
)

// Return bounded, actionable messages; never echo a response, request or token.
func connectionFailure(err error, tunnel bool) error {
	var host x509.HostnameError
	var authority x509.UnknownAuthorityError
	var invalid x509.CertificateInvalidError
	var record tls.RecordHeaderError
	switch {
	case errors.As(err, &host):
		return errors.New("TLS address mismatch. The server certificate does not cover the private API address. The administrator must renew its certificate; verification was not bypassed.")
	case errors.As(err, &authority):
		return errors.New("TLS authority mismatch. The server certificate is not trusted by this invitation. Ask the administrator to check its CA and issue a fresh invitation; keep the phone keys.")
	case errors.As(err, &invalid):
		if invalid.Reason == x509.Expired {
			if invalid.Cert != nil && time.Now().Before(invalid.Cert.NotBefore) {
				return errors.New("TLS certificate is not valid yet. Check the phone and server clocks.")
			}
			return errors.New("TLS certificate has expired, or the phone clock is ahead. Check both clocks; the administrator can renew the server certificate without resetting pairing.")
		}
		return errors.New("TLS certificate validation failed. The administrator must check the certificate chain; verification was not bypassed.")
	case errors.As(err, &record):
		return errors.New("The server did not answer with TLS. Check the configured HTTPS API port; plaintext enrollment is not allowed.")
	case errors.Is(err, syscall.EACCES), errors.Is(err, syscall.EPERM):
		return errors.New("Android denied network access. Check Field Notes' Network permission in this profile and its VPN policy; management access is not required.")
	}
	where := "Legacy LAN enrollment"
	next := "Use a fresh key-bound tunnel invitation from the updated server."
	if tunnel {
		where = "Private task tunnel"
		next = "Check the server's WireGuard UDP port, router forwarding, firewall and the phone's VPN policy. Management HTTPS is not used."
	}
	var network net.Error
	if errors.As(err, &network) && network.Timeout() {
		return fmt.Errorf("%s timed out. %s", where, next)
	}
	if errors.Is(err, syscall.ECONNREFUSED) {
		return fmt.Errorf("%s connection was refused. Check that the server and private API are running. %s", where, next)
	}
	if errors.Is(err, syscall.ENETUNREACH) || errors.Is(err, syscall.EHOSTUNREACH) {
		return fmt.Errorf("%s has no reachable network route. %s", where, next)
	}
	return fmt.Errorf("%s connection ended before a verified reply. %s", where, next)
}
