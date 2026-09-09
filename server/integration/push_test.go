package integration

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"zisee/server/internal/call"
	"zisee/server/internal/httpapi"
	"zisee/server/internal/push"
)

type recordingSender struct {
	mu   sync.Mutex
	sent []struct {
		target push.Target
		invite push.Invite
	}
}

func (s *recordingSender) Send(_ context.Context, target push.Target, invite push.Invite) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sent = append(s.sent, struct {
		target push.Target
		invite push.Invite
	}{target, invite})
	return nil
}

func (s *recordingSender) waitFor(t *testing.T, callID string) (push.Target, push.Invite) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		s.mu.Lock()
		for _, e := range s.sent {
			if e.invite.CallID == callID {
				s.mu.Unlock()
				return e.target, e.invite
			}
		}
		s.mu.Unlock()
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("no push recorded for call %s", callID)
	return push.Target{}, push.Invite{}
}

func TestPushWakesCalleeOnRing(t *testing.T) {
	store, _ := database(t)
	_, aToken := callUser(t, store, 1)
	_, bToken := callUser(t, store, 2)
	aDevice := fmt.Sprintf("zdev_abcdefghijklmnopqrstuv%02d", 1)

	sender := &recordingSender{}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := httptest.NewServer(httpapi.New(store, log).WithPush(push.NewGateway(sender, store, log)).Handler())
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
			b, _ := io.ReadAll(resp.Body)
			t.Fatalf("%s %s: status %d (%s)", method, path, resp.StatusCode, b)
		}
		if out != nil && json.NewDecoder(resp.Body).Decode(out) != nil {
			t.Fatalf("%s %s: bad body", method, path)
		}
	}

	// A device may only register its own push token.
	request("PUT", "/v1/devices/zdev_someoneelsedeadbeef00/push-token", aToken,
		`{"provider":"fcm","token":"atok"}`, http.StatusForbidden, nil)
	request("PUT", "/v1/devices/"+aDevice+"/push-token", aToken,
		`{"provider":"fcm","token":"atok"}`, http.StatusNoContent, nil)

	// A publishes an invite; B redeems it, so B is caller and A is callee.
	var invite call.Invite
	request("POST", "/v1/invites", aToken, "", http.StatusOK, &invite)
	payload, _ := json.Marshal(map[string]string{"token": invite.Token})
	var ring call.Call
	request("POST", "/v1/invites/redeem", bToken, string(payload), http.StatusOK, &ring)
	if ring.State != "ringing" || ring.CalleeID == "" {
		t.Fatalf("unexpected call %+v", ring)
	}

	target, woken := sender.waitFor(t, ring.ID)
	if target.Provider != "fcm" || target.Token != "atok" || target.DeviceID != aDevice {
		t.Fatalf("unexpected wake target %+v", target)
	}
	if woken.MediaType != "video" || woken.CallerName != "九云" || woken.ExpiresAt.IsZero() {
		t.Fatalf("unexpected wake invite %+v", woken)
	}
}
