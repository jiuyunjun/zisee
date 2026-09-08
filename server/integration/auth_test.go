package integration

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/jackc/pgx/v5"
	"zisee/server/internal/httpapi"
	"zisee/server/internal/identity"
	"zisee/server/internal/postgres"
)

func database(t *testing.T) (*postgres.Store, string) {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set: real PostgreSQL integration test skipped")
	}
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal("test database unavailable")
	}
	schema := fmt.Sprintf("zisee_test_%x", time.Now().UnixNano())
	quoted := pgx.Identifier{schema}.Sanitize()
	if _, err = conn.Exec(ctx, "CREATE SCHEMA "+quoted); err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(dsn)
	if err != nil {
		t.Fatal("invalid test DSN")
	}
	query := u.Query()
	query.Set("search_path", schema)
	u.RawQuery = query.Encode()
	store, err := postgres.Open(ctx, u.String())
	if err != nil {
		t.Fatal("test store unavailable")
	}
	t.Cleanup(func() {
		store.Close()
		if _, err := conn.Exec(ctx, "DROP SCHEMA "+quoted+" CASCADE"); err != nil {
			t.Error("test schema cleanup failed")
		}
		conn.Close(ctx)
	})
	if err := store.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	if err := store.Migrate(ctx); err != nil {
		t.Fatal("migration not idempotent", err)
	}
	return store, u.String()
}

func registration(t *testing.T) (identity.Registration, *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	req := identity.Registration{
		IdentityID: "zid_01991000-0000-7000-8000-000000000001",
		DeviceID:   "zdev_abcdefghijklmnopqrstuvwx", DisplayName: "九云",
		PublicKey: base64.RawURLEncoding.EncodeToString(der),
	}
	req.Signature = sign(t, key, identity.BootstrapPayload(req))
	return req, key
}

func sign(t *testing.T, key *ecdsa.PrivateKey, payload []byte) string {
	t.Helper()
	digest := sha256.Sum256(payload)
	signature, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return base64.RawURLEncoding.EncodeToString(signature)
}

func login(t *testing.T, service *identity.Service, device string, key *ecdsa.PrivateKey) identity.Token {
	t.Helper()
	c, err := service.NewChallenge(context.Background(), device)
	if err != nil {
		t.Fatal(err)
	}
	token, err := service.Authenticate(context.Background(), c.ID, sign(t, key, identity.ChallengePayload(c)))
	if err != nil {
		t.Fatal(err)
	}
	return token
}

func TestRegistrationPersistenceConflictAndRename(t *testing.T) {
	store, dsn := database(t)
	service := identity.New(store)
	ctx := context.Background()
	req, key := registration(t)
	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			if _, err := service.Bootstrap(ctx, req); err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	original, err := store.Identity(ctx, req.IdentityID)
	if err != nil {
		t.Fatal(err)
	}
	bad, _ := registration(t) // same identity/device, different key; valid proof of wrong key
	if _, err := service.Bootstrap(ctx, bad); !errors.Is(err, identity.ErrConflict) {
		t.Fatalf("expected conflict, got %v", err)
	}
	bad = req
	bad.DeviceID = "zdev_otherdeviceabcdefghijklm"
	bad.Signature = sign(t, key, identity.BootstrapPayload(bad))
	if _, err := service.Bootstrap(ctx, bad); !errors.Is(err, identity.ErrConflict) {
		t.Fatal("must not attach new device")
	}
	token := login(t, service, req.DeviceID, key)
	session, err := service.Authorize(ctx, token.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	renamed, err := service.Rename(ctx, session, "Jiu")
	if err != nil || renamed.ID != original.ID || !renamed.CreatedAt.Equal(original.CreatedAt) {
		t.Fatal("rename changed identity")
	}
	if _, err = service.Bootstrap(ctx, req); err != nil {
		t.Fatal(err)
	}
	// A new pool/service represents another instance or a process restart.
	reopened, err := postgres.Open(ctx, dsn)
	if err != nil {
		t.Fatal("reopen failed")
	}
	defer reopened.Close()
	next := identity.New(reopened)
	restored, err := next.Authorize(ctx, token.AccessToken)
	if err != nil {
		t.Fatal("session did not persist")
	}
	user, err := next.Me(ctx, restored)
	if err != nil || user.DisplayName != "Jiu" {
		t.Fatal("retry overwrote name or persistence failed")
	}
}

func TestChallengeSingleUseExpiryAndTokenRevocation(t *testing.T) {
	store, _ := database(t)
	service := identity.New(store)
	ctx := context.Background()
	req, key := registration(t)
	if _, err := service.Bootstrap(ctx, req); err != nil {
		t.Fatal(err)
	}
	c, err := service.NewChallenge(ctx, req.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = service.Authenticate(ctx, c.ID, "invalid"); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatal("wrong proof accepted")
	}
	signature := sign(t, key, identity.ChallengePayload(c))
	var success atomic.Int32
	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			if _, err := service.Authenticate(ctx, c.ID, signature); err == nil {
				success.Add(1)
			} else if !errors.Is(err, identity.ErrUnauthorized) {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	if success.Load() != 1 {
		t.Fatal("challenge redeemed more than once")
	}
	expired := identity.Challenge{ID: strings.Repeat("a", 43), Nonce: "old", DeviceID: req.DeviceID, ExpiresAt: time.Now().Add(-time.Second)}
	if err := store.PutChallenge(ctx, expired, time.Now().Add(-time.Minute)); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Authenticate(ctx, expired.ID, sign(t, key, identity.ChallengePayload(expired))); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatal("expired challenge accepted")
	}
	first := login(t, service, req.DeviceID, key)
	second := login(t, service, req.DeviceID, key)
	if _, err := service.Authorize(ctx, first.AccessToken); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatal("old token still active")
	}
	session, err := service.Authorize(ctx, second.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Session(ctx, session.TokenHash, session.ExpiresAt); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatal("expired token accepted")
	}
	if err := service.Logout(ctx, session); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Authorize(ctx, second.AccessToken); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatal("logout failed")
	}
	if err := store.Cleanup(ctx, time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
}

func TestChallengeLimit(t *testing.T) {
	store, _ := database(t)
	service := identity.New(store)
	req, _ := registration(t)
	if _, err := service.Bootstrap(context.Background(), req); err != nil {
		t.Fatal(err)
	}
	for range 5 {
		if _, err := service.NewChallenge(context.Background(), req.DeviceID); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := service.NewChallenge(context.Background(), req.DeviceID); !errors.Is(err, identity.ErrRateLimited) {
		t.Fatal("unbounded challenges")
	}
}

func TestHTTPAndWebSocketAuthentication(t *testing.T) {
	store, _ := database(t)
	var logs bytes.Buffer
	handler := httpapi.New(store, slog.New(slog.NewJSONHandler(&logs, nil))).Handler()
	host := httptest.NewServer(handler)
	defer host.Close()
	request := func(method, path, token string, body any, status int) []byte {
		t.Helper()
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		req, err := http.NewRequest(method, host.URL+path, bytes.NewReader(encoded))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		response, err := host.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		data, _ := io.ReadAll(response.Body)
		if response.StatusCode != status {
			t.Fatalf("%s %s got %d expected %d", method, path, response.StatusCode, status)
		}
		if response.Header.Get("Cache-Control") != "no-store" {
			t.Fatal("auth responses must not be cached")
		}
		return data
	}
	request("GET", "/healthz", "", nil, 200)
	request("GET", "/readyz", "", nil, 200)
	req, key := registration(t)
	request("POST", "/v1/identity/bootstrap", "", req, 200)
	var c identity.Challenge
	json.Unmarshal(request("POST", "/v1/auth/challenge", "", map[string]string{"deviceId": req.DeviceID}, 200), &c)
	var token identity.Token
	json.Unmarshal(request("POST", "/v1/auth/token", "", map[string]string{"challengeId": c.ID, "signature": sign(t, key, identity.ChallengePayload(c))}, 200), &token)
	request("GET", "/v1/identity", req.IdentityID, nil, 401)
	request("GET", "/v1/identity", token.AccessToken, nil, 200)
	request("GET", "/v1/identity?access_token=forbidden", "", nil, 400)
	request("PATCH", "/v1/identity", token.AccessToken, map[string]string{"displayName": "New name"}, 200)
	request("POST", "/v1/auth/challenge", "", map[string]string{"deviceId": req.DeviceID, "unknown": "field"}, 400)
	request("POST", "/v1/identity/bootstrap", "", map[string]string{"publicKey": strings.Repeat("a", 9000)}, 400)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	wsURL := "ws" + strings.TrimPrefix(host.URL, "http") + "/v1/signaling"
	if conn, response, err := websocket.Dial(ctx, wsURL, nil); err == nil {
		conn.CloseNow()
		t.Fatal("unauthenticated WebSocket accepted")
	} else if response == nil || response.StatusCode != 401 {
		t.Fatal("expected unauthorized handshake")
	}
	headers := http.Header{"Authorization": []string{"Bearer " + token.AccessToken}}
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{HTTPHeader: headers, Subprotocols: []string{"zisee.v1"}})
	if err != nil {
		t.Fatal("authenticated WebSocket rejected")
	}
	defer conn.CloseNow()
	_, ready, err := conn.Read(ctx)
	if err != nil || !bytes.Contains(ready, []byte("session.ready")) {
		t.Fatal("session not ready")
	}
	if err := conn.Write(ctx, websocket.MessageText, []byte(`{"v":1,"type":"ping","id":"test"}`)); err != nil {
		t.Fatal(err)
	}
	_, pong, err := conn.Read(ctx)
	if err != nil || !bytes.Contains(pong, []byte(`"type":"pong"`)) {
		t.Fatal("heartbeat failed")
	}
	request("DELETE", "/v1/auth/session", token.AccessToken, nil, 204)
	if err := conn.Write(ctx, websocket.MessageText, []byte(`{"v":1,"type":"ping"}`)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := conn.Read(ctx); websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
		t.Fatal("revoked WebSocket still usable")
	}
	request("GET", "/v1/identity", token.AccessToken, nil, 401)
	if strings.Contains(logs.String(), token.AccessToken) || strings.Contains(logs.String(), req.DisplayName) {
		t.Fatal("private fields leaked to logs")
	}
}
