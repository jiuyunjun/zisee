package push

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

// fcmScope is the OAuth2 scope for the FCM HTTP v1 send endpoint.
const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

// inviteTTL bounds how long FCM keeps trying to deliver. A ring that the server
// times out at 60s is worthless after 30s of undelivered push (§9, §29).
const inviteTTL = 30 * time.Second

// FCM sends call invites through Firebase Cloud Messaging HTTP v1.
type FCM struct {
	endpoint string
	tokens   oauth2.TokenSource
	client   *http.Client
}

// NewFCM wires the sender to real FCM using Application Default Credentials —
// the same credential chain Firestore already uses. On Cloud Run that is the
// service account; locally it is `gcloud auth application-default login`.
func NewFCM(ctx context.Context, projectID string) (*FCM, error) {
	if projectID == "" {
		return nil, errors.New("fcm project id is required")
	}
	source, err := google.DefaultTokenSource(ctx, fcmScope)
	if err != nil {
		return nil, err
	}
	return newFCM(fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", projectID), source), nil
}

// newFCM is the injectable constructor used by tests.
func newFCM(endpoint string, tokens oauth2.TokenSource) *FCM {
	return &FCM{
		endpoint: endpoint,
		tokens:   tokens,
		client:   &http.Client{Timeout: 10 * time.Second},
	}
}

type fcmMessage struct {
	Message struct {
		Token   string            `json:"token"`
		Android fcmAndroid        `json:"android"`
		Data    map[string]string `json:"data"`
	} `json:"message"`
}

type fcmAndroid struct {
	Priority string `json:"priority"`
	TTL      string `json:"ttl"`
}

func (f *FCM) Send(ctx context.Context, target Target, invite Invite) error {
	if target.Provider != "fcm" || target.Token == "" {
		return fmt.Errorf("push: unsupported target provider %q", target.Provider)
	}
	var body fcmMessage
	body.Message.Token = target.Token
	body.Message.Android = fcmAndroid{Priority: "HIGH", TTL: fmt.Sprintf("%.0fs", inviteTTL.Seconds())}
	body.Message.Data = map[string]string{
		"type":         "call_invite",
		"call_id":      invite.CallID,
		"caller_id":    invite.CallerID,
		"caller_name":  invite.CallerName,
		"media_type":   invite.MediaType,
		"issued_at":    invite.IssuedAt.UTC().Format(time.RFC3339),
		"expires_at":   invite.ExpiresAt.UTC().Format(time.RFC3339),
		"call_version": "1",
	}
	encoded, err := json.Marshal(&body)
	if err != nil {
		return err
	}
	token, err := f.tokens.Token()
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.endpoint, bytes.NewReader(encoded))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	token.SetAuthHeader(req)
	resp, err := f.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	if unregistered(resp.StatusCode, payload) {
		return ErrUnregistered
	}
	return fmt.Errorf("push: fcm responded %d", resp.StatusCode)
}

// unregistered reports whether FCM rejected the token permanently. 404 always
// means the token is gone; 400 only when the FcmError code says so, since a
// malformed message body is also 400.
func unregistered(status int, payload []byte) bool {
	if status == http.StatusNotFound {
		return true
	}
	if status != http.StatusBadRequest {
		return false
	}
	var parsed struct {
		Error struct {
			Details []struct {
				ErrorCode string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	if json.Unmarshal(payload, &parsed) != nil {
		return false
	}
	for _, detail := range parsed.Error.Details {
		if strings.EqualFold(detail.ErrorCode, "UNREGISTERED") || strings.EqualFold(detail.ErrorCode, "INVALID_ARGUMENT") {
			return true
		}
	}
	return false
}
