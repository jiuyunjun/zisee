package push

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"golang.org/x/oauth2"
)

func staticTokens() oauth2.TokenSource {
	return oauth2.StaticTokenSource(&oauth2.Token{AccessToken: "test-token", TokenType: "Bearer"})
}

func testInvite() Invite {
	return Invite{
		CallID: "call-1", CallerID: "zid_caller", CallerName: "九云", MediaType: "video",
		IssuedAt: time.Unix(1_700_000_000, 0), ExpiresAt: time.Unix(1_700_000_030, 0),
	}
}

func TestFCMSendBuildsHighPriorityDataMessage(t *testing.T) {
	var captured fcmMessage
	var auth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth = r.Header.Get("Authorization")
		body, _ := io.ReadAll(r.Body)
		if err := json.Unmarshal(body, &captured); err != nil {
			t.Errorf("decode: %v", err)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"name":"projects/p/messages/1"}`))
	}))
	defer server.Close()

	sender := newFCM(server.URL, staticTokens())
	if err := sender.Send(context.Background(), Target{DeviceID: "d1", Provider: "fcm", Token: "tok"}, testInvite()); err != nil {
		t.Fatalf("send: %v", err)
	}
	if auth != "Bearer test-token" {
		t.Fatalf("auth header %q", auth)
	}
	if captured.Message.Token != "tok" {
		t.Fatalf("token %q", captured.Message.Token)
	}
	if captured.Message.Android.Priority != "HIGH" || captured.Message.Android.TTL != "30s" {
		t.Fatalf("android config %+v", captured.Message.Android)
	}
	data := captured.Message.Data
	if data["type"] != "call_invite" || data["call_id"] != "call-1" || data["caller_name"] != "九云" ||
		data["media_type"] != "video" || data["call_version"] != "1" || data["expires_at"] == "" {
		t.Fatalf("data payload %+v", data)
	}
}

func TestFCMSendMapsUnregistered(t *testing.T) {
	cases := []struct {
		name   string
		status int
		body   string
	}{
		{"not found", http.StatusNotFound, `{"error":{"code":404,"status":"NOT_FOUND"}}`},
		{"bad request unregistered", http.StatusBadRequest, `{"error":{"details":[{"errorCode":"UNREGISTERED"}]}}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer server.Close()
			sender := newFCM(server.URL, staticTokens())
			err := sender.Send(context.Background(), Target{Provider: "fcm", Token: "tok"}, testInvite())
			if !errors.Is(err, ErrUnregistered) {
				t.Fatalf("want ErrUnregistered, got %v", err)
			}
		})
	}
}

func TestFCMSendSurfacesOtherErrors(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()
	sender := newFCM(server.URL, staticTokens())
	err := sender.Send(context.Background(), Target{Provider: "fcm", Token: "tok"}, testInvite())
	if err == nil || errors.Is(err, ErrUnregistered) {
		t.Fatalf("want transient error, got %v", err)
	}
}

func TestFCMSendRejectsNonFCMTarget(t *testing.T) {
	sender := newFCM("http://unused", staticTokens())
	if err := sender.Send(context.Background(), Target{Provider: "apns", Token: "tok"}, testInvite()); err == nil {
		t.Fatal("expected error for non-fcm provider")
	}
}
