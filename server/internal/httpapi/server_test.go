package httpapi

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"

	"zisee/server/internal/identity"
)

type unavailableStore struct{ identity.Store }

func (unavailableStore) Ping(context.Context) error { return errors.New("unavailable") }

func TestReadinessAndInputBoundaries(t *testing.T) {
	handler := New(unavailableStore{}, slog.New(slog.NewTextHandler(io.Discard, nil))).Handler()
	cases := []struct {
		method, path, body, contentType, origin string
		status                                  int
	}{
		{"GET", "/healthz", "", "", "", 200},
		{"GET", "/readyz", "", "", "", 503},
		{"POST", "/v1/identity/bootstrap", "{}", "text/plain", "", 415},
		{"POST", "/v1/identity/bootstrap", "{} {}", "application/json", "", 400},
		{"POST", "/v1/identity/bootstrap", `{"unknown":1}`, "application/json", "", 400},
		{"GET", "/v1/identity", "", "", "https://untrusted.example", 403},
		{"GET", "/v1/identity?token=bad", "", "", "", 400},
		{"GET", "/v1/identity", "", "", "", 401},
	}
	for _, c := range cases {
		request := httptest.NewRequest(c.method, c.path, strings.NewReader(c.body))
		if c.contentType != "" {
			request.Header.Set("Content-Type", c.contentType)
		}
		if c.origin != "" {
			request.Header.Set("Origin", c.origin)
		}
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != c.status {
			t.Errorf("%s %s: %d != %d", c.method, c.path, response.Code, c.status)
		}
	}
}

func TestPerInstanceRequestCeiling(t *testing.T) {
	server := New(unavailableStore{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	for range 30 {
		if !server.allow() {
			t.Fatal("early limit")
		}
	}
	if server.allow() {
		t.Fatal("unbounded requests")
	}
}
