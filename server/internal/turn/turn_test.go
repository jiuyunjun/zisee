package turn

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func newTestClient(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	client := New("key-id", "api-token")
	client.http = server.Client()
	// Point the client at the stub while keeping the real path shape.
	transport := server.Client().Transport
	client.http.Transport = rewrite{base: transport, host: strings.TrimPrefix(server.URL, "http://")}
	return client
}

type rewrite struct {
	base http.RoundTripper
	host string
}

func (r rewrite) RoundTrip(request *http.Request) (*http.Response, error) {
	request.URL.Scheme = "http"
	request.URL.Host = r.host
	return r.base.RoundTrip(request)
}

func TestCredentialsReturnsIssuedServer(t *testing.T) {
	var gotPath, gotAuth string
	var gotTTL int
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotAuth = r.URL.Path, r.Header.Get("Authorization")
		var body struct {
			TTL int `json:"ttl"`
		}
		data, _ := io.ReadAll(r.Body)
		json.Unmarshal(data, &body)
		gotTTL = body.TTL
		w.WriteHeader(http.StatusCreated)
		io.WriteString(w, `{"iceServers":{"urls":["stun:stun.cloudflare.com:3478","turn:turn.cloudflare.com:3478?transport=udp"],"username":"u","credential":"c"}}`)
	})
	issued, err := client.Credentials(context.Background(), time.Hour)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotPath != "/v1/turn/keys/key-id/credentials/generate" {
		t.Fatalf("unexpected path %q", gotPath)
	}
	if gotAuth != "Bearer api-token" {
		t.Fatal("api token must be sent as a bearer token")
	}
	if gotTTL != 3600 {
		t.Fatalf("ttl not forwarded, got %d", gotTTL)
	}
	if issued.Username != "u" || issued.Credential != "c" || len(issued.URLs) != 2 {
		t.Fatal("issued credential not decoded")
	}
}

func TestCredentialsRejectsUnusableResponses(t *testing.T) {
	cases := map[string]http.HandlerFunc{
		"upstream error": func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusForbidden) },
		"missing credential": func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusCreated)
			io.WriteString(w, `{"iceServers":{"urls":["turn:example"],"username":"u"}}`)
		},
		"no urls": func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusCreated)
			io.WriteString(w, `{"iceServers":{"urls":[],"username":"u","credential":"c"}}`)
		},
		"malformed": func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusCreated)
			io.WriteString(w, `{`)
		},
	}
	for name, handler := range cases {
		t.Run(name, func(t *testing.T) {
			client := newTestClient(t, handler)
			if _, err := client.Credentials(context.Background(), time.Hour); err != ErrUnavailable {
				t.Fatalf("expected ErrUnavailable, got %v", err)
			}
		})
	}
}

func TestCredentialsRejectsOutOfRangeTTL(t *testing.T) {
	client := New("key-id", "api-token")
	for _, ttl := range []time.Duration{30 * time.Second, 48 * time.Hour} {
		if _, err := client.Credentials(context.Background(), ttl); err == nil {
			t.Fatalf("accepted ttl %s", ttl)
		}
	}
}

func TestErrorsNeverEchoTheKey(t *testing.T) {
	// The endpoint URL embeds the key id; transport errors must not surface it.
	client := New("secret-key-id", "secret-token")
	client.http.Timeout = time.Millisecond
	_, err := client.Credentials(context.Background(), time.Hour)
	if err == nil {
		t.Fatal("expected failure against unreachable host")
	}
	if strings.Contains(err.Error(), "secret-key-id") || strings.Contains(err.Error(), "secret-token") {
		t.Fatal("error leaked credentials")
	}
}

func TestFallbackIsStunOnly(t *testing.T) {
	fallback := Fallback()
	if len(fallback.URLs) != 1 || !strings.HasPrefix(fallback.URLs[0], "stun:") {
		t.Fatal("fallback must be STUN only")
	}
	if fallback.Username != "" || fallback.Credential != "" {
		t.Fatal("fallback must not carry credentials")
	}
}
