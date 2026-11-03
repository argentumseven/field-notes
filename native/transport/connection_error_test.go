package transport

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"net/url"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestConnectionFailuresDistinguishTrustRoutingAndPermissionWithoutEchoingSecrets(t *testing.T) {
	tests := []struct {
		err  error
		want string
	}{
		{x509.HostnameError{Host: "10.82.0.1"}, "TLS address mismatch"},
		{x509.UnknownAuthorityError{}, "TLS authority mismatch"},
		{x509.CertificateInvalidError{Reason: x509.Expired, Cert: &x509.Certificate{NotBefore: time.Now().Add(time.Hour)}}, "not valid yet"},
		{x509.CertificateInvalidError{Reason: x509.Expired}, "has expired"},
		{tls.RecordHeaderError{}, "did not answer with TLS"},
		{&net.OpError{Err: syscall.EACCES}, "Android denied network access"},
		{&net.OpError{Err: syscall.ENETUNREACH}, "no reachable network route"},
		{&net.OpError{Err: syscall.ECONNREFUSED}, "connection was refused"},
		{context.DeadlineExceeded, "timed out"},
		{errors.New("secret-raw-cause"), "connection ended"},
	}
	for _, test := range tests {
		wrapped := &url.Error{Op: "Post", URL: "https://secret-invitation.invalid/", Err: test.err}
		message := connectionFailure(wrapped, true).Error()
		if !strings.Contains(message, test.want) || strings.Contains(message, "secret-") {
			t.Fatalf("Unsafe or incorrect category: %s", message)
		}
	}
}
