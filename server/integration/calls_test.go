package integration

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/jackc/pgx/v5"
	"zisee/server/internal/call"
	"zisee/server/internal/httpapi"
	"zisee/server/internal/identity"
	"zisee/server/internal/postgres"
)

func callUser(t *testing.T, store *postgres.Store, n int) (string, string) {
	t.Helper()
	req, key := registration(t)
	req.IdentityID = fmt.Sprintf("zid_01991000-0000-7000-8000-%012d", n)
	req.DeviceID = fmt.Sprintf("zdev_abcdefghijklmnopqrstuv%02d", n)
	req.Signature = sign(t, key, identity.BootstrapPayload(req))
	svc := identity.New(store)
	if _, err := svc.Bootstrap(context.Background(), req); err != nil {
		t.Fatal(err)
	}
	return req.IdentityID, login(t, svc, req.DeviceID, key).AccessToken
}

func TestCallsConcurrentRedemptionAndTransitions(t *testing.T) {
	store, dsn := database(t)
	ctx := context.Background()
	other, err := postgres.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	a, _ := callUser(t, store, 1)
	b, _ := callUser(t, store, 2)
	c, _ := callUser(t, store, 3)
	invite, err := store.CreateInvite(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.RedeemInvite(ctx, a, invite.Token); !errors.Is(err, call.ErrInvite) {
		t.Fatalf("self redeem: %v", err)
	}
	var wg sync.WaitGroup
	results := make(chan call.Call, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			instance := store
			if i%2 == 1 {
				instance = other
			}
			v, e := instance.RedeemInvite(ctx, b, invite.Token)
			if e != nil {
				t.Error(e)
				return
			}
			results <- v
		}(i)
	}
	wg.Wait()
	close(results)
	var current call.Call
	for v := range results {
		if current.ID != "" && current.ID != v.ID {
			t.Fatal("duplicate calls")
		}
		current = v
	}
	if current.ID == "" || current.CallerID != b || current.CalleeID != a || current.State != "ringing" {
		t.Fatal("wrong call")
	}
	if _, err = other.RedeemInvite(ctx, c, invite.Token); !errors.Is(err, call.ErrInvite) {
		t.Fatalf("reuse: %v", err)
	}
	if _, err = other.GetCall(ctx, c, current.ID); !errors.Is(err, call.ErrNotFound) {
		t.Fatalf("outsider: %v", err)
	}
	if _, err = other.ActOnCall(ctx, b, current.ID, "accept"); !errors.Is(err, call.ErrTransition) {
		t.Fatalf("caller accept: %v", err)
	}
	busyInvite, err := store.CreateInvite(ctx, c)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = other.RedeemInvite(ctx, b, busyInvite.Token); !errors.Is(err, call.ErrBusy) {
		t.Fatalf("busy: %v", err)
	}
	accepted, err := other.ActOnCall(ctx, a, current.ID, "accept")
	if err != nil || accepted.State != "accepted" {
		t.Fatalf("accept: %v", err)
	}
	retry, err := store.ActOnCall(ctx, a, current.ID, "accept")
	if err != nil || !retry.ExpiresAt.Equal(accepted.ExpiresAt) {
		t.Fatal("retry extended deadline")
	}
	if _, err = store.ActOnCall(ctx, a, current.ID, "reject"); !errors.Is(err, call.ErrTransition) {
		t.Fatal("accepted call rejected")
	}
	if _, err = other.ActOnCall(ctx, b, current.ID, "end"); err != nil {
		t.Fatal(err)
	}
	if _, err = store.ActOnCall(ctx, a, current.ID, "end"); err != nil {
		t.Fatal(err)
	}
	if v, err := store.CurrentCall(ctx, a); err != nil || v != nil {
		t.Fatal("ended call still active")
	}
	next, err := store.RedeemInvite(ctx, b, busyInvite.Token)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.ActOnCall(ctx, c, next.ID, "reject"); err != nil {
		t.Fatal(err)
	}
}

func TestCallsCompetingInvitations(t *testing.T) {
	store, dsn := database(t)
	ctx := context.Background()
	other, err := postgres.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	a, _ := callUser(t, store, 1)
	b, _ := callUser(t, store, 2)
	c, _ := callUser(t, store, 3)
	one, err := store.CreateInvite(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	two, err := store.CreateInvite(ctx, c)
	if err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	results := make(chan error, 2)
	go func() { <-start; _, e := store.RedeemInvite(ctx, b, one.Token); results <- e }()
	go func() { <-start; _, e := other.RedeemInvite(ctx, b, two.Token); results <- e }()
	close(start)
	success, busy := 0, 0
	for i := 0; i < 2; i++ {
		err := <-results
		if err == nil {
			success++
		} else if errors.Is(err, call.ErrBusy) {
			busy++
		} else {
			t.Fatal(err)
		}
	}
	if success != 1 || busy != 1 {
		t.Fatalf("success=%d busy=%d", success, busy)
	}
}

func TestCallsExpiryAndInviteRotation(t *testing.T) {
	store, dsn := database(t)
	ctx := context.Background()
	a, _ := callUser(t, store, 1)
	b, _ := callUser(t, store, 2)
	old, err := store.CreateInvite(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	fresh, err := store.CreateInvite(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.RedeemInvite(ctx, b, old.Token); !errors.Is(err, call.ErrInvite) {
		t.Fatal("old token valid")
	}
	current, err := store.RedeemInvite(ctx, b, fresh.Token)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	if _, err = conn.Exec(ctx, `UPDATE calls SET expires_at=clock_timestamp()-interval '1 second' WHERE id=$1`, current.ID); err != nil {
		t.Fatal(err)
	}
	if _, err = store.ActOnCall(ctx, a, current.ID, "accept"); !errors.Is(err, call.ErrTransition) {
		t.Fatal("expired call accepted")
	}
	got, err := store.GetCall(ctx, a, current.ID)
	if err != nil || got.State != "expired" {
		t.Fatal("expiry missing")
	}
	if active, err := store.CurrentCall(ctx, a); err != nil || active != nil {
		t.Fatal("expired call active")
	}
	if _, err = conn.Exec(ctx, `UPDATE call_invites SET expires_at=clock_timestamp()-interval '1 second'`); err != nil {
		t.Fatal(err)
	}
	if _, err = store.RedeemInvite(ctx, b, fresh.Token); !errors.Is(err, call.ErrInvite) {
		t.Fatal("expired invite valid")
	}
	if err = store.Cleanup(ctx, time.Now().Add(25*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if _, err = store.GetCall(ctx, a, current.ID); !errors.Is(err, call.ErrNotFound) {
		t.Fatal("retention cleanup failed")
	}
}

func TestCallHTTPAndWebSocketOwnership(t *testing.T) {
	store, _ := database(t)
	_, aToken := callUser(t, store, 1)
	_, bToken := callUser(t, store, 2)
	_, cToken := callUser(t, store, 3)
	server := httptest.NewServer(httpapi.New(store, slog.New(slog.NewTextHandler(io.Discard, nil))).Handler())
	defer server.Close()
	request := func(method, path, token, body string, status int, out any) {
		t.Helper()
		req, err := http.NewRequest(method, server.URL+path, bytes.NewBufferString(body))
		if err != nil {
			t.Fatal(err)
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != status {
			t.Fatalf("%s %s: status %d", method, path, resp.StatusCode)
		}
		if out != nil {
			if err = json.NewDecoder(resp.Body).Decode(out); err != nil {
				t.Fatal(err)
			}
		}
	}
	request("POST", "/v1/invites", "", "", 401, nil)
	var invite call.Invite
	request("POST", "/v1/invites", aToken, "", 200, &invite)
	payload, _ := json.Marshal(map[string]string{"token": invite.Token})
	var current call.Call
	request("POST", "/v1/invites/redeem", bToken, string(payload), 200, &current)
	request("GET", "/v1/calls/"+current.ID, cToken, "", 404, nil)
	request("POST", "/v1/calls/"+current.ID+"/actions", bToken, `{"action":"accept"}`, 409, nil)
	request("POST", "/v1/calls/"+current.ID+"/actions", aToken, `{"action":"accept"}`, 200, nil)
	for _, token := range []string{aToken, cToken} {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		ws, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(server.URL, "http")+"/v1/signaling", &websocket.DialOptions{Subprotocols: []string{"zisee.v1"}, HTTPHeader: http.Header{"Authorization": []string{"Bearer " + token}}})
		if err != nil {
			cancel()
			t.Fatal(err)
		}
		if _, _, err = ws.Read(ctx); err != nil {
			t.Fatal(err)
		}
		msg, _ := json.Marshal(map[string]any{"v": 1, "type": "call.sync", "id": "sync-1", "callId": current.ID})
		if err = ws.Write(ctx, websocket.MessageText, msg); err != nil {
			t.Fatal(err)
		}
		_, data, err := ws.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		var result struct {
			Type  string    `json:"type"`
			Error string    `json:"error"`
			Call  call.Call `json:"call"`
		}
		if err = json.Unmarshal(data, &result); err != nil {
			t.Fatal(err)
		}
		if token == aToken {
			if result.Type != "call.snapshot" || result.Call.State != "accepted" {
				t.Fatal("missing snapshot")
			}
		} else if result.Error != "call_not_found" || result.Call.ID != "" {
			t.Fatal("call leaked")
		}
		ws.CloseNow()
		cancel()
	}
}
