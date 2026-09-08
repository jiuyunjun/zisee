package integration

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"zisee/server/internal/call"
	"zisee/server/internal/httpapi"
	"zisee/server/internal/identity"
	"zisee/server/internal/postgres"
)

func TestMediaConsentRolesReplayAndCleanup(t *testing.T) {
	store, dsn := database(t)
	ctx := context.Background()
	other, err := postgres.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	a, _ := callUser(t, store, 1)
	b, _ := callUser(t, store, 2)
	outsider, _ := callUser(t, store, 3)
	invite, err := store.CreateInvite(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	c, err := store.RedeemInvite(ctx, b, invite.Token)
	if err != nil {
		t.Fatal(err)
	}
	sdp := "v=0\r\na=fake-test-only\r\n"
	if _, err = store.SendDescription(ctx, b, c.ID, "offer-1", "offer", sdp); !errors.Is(err, call.ErrTransition) {
		t.Fatal("sent before consent", err)
	}
	if _, err = store.ActOnCall(ctx, a, c.ID, "accept"); err != nil {
		t.Fatal(err)
	}
	if _, err = store.SendDescription(ctx, a, c.ID, "wrong-role", "offer", sdp); !errors.Is(err, call.ErrTransition) {
		t.Fatal("wrong offer role", err)
	}
	if _, err = store.SendDescription(ctx, a, c.ID, "too-early", "answer", sdp); !errors.Is(err, call.ErrTransition) {
		t.Fatal("answer before offer", err)
	}
	if _, err = store.SendDescription(ctx, b, c.ID, "invalid", "offer", "private"); !errors.Is(err, identity.ErrInvalid) {
		t.Fatal("invalid SDP", err)
	}
	if _, err = store.SendDescription(ctx, b, c.ID, "large", "offer", "v=0\r\n"+strings.Repeat("x", 49152)); !errors.Is(err, identity.ErrInvalid) {
		t.Fatal("large SDP", err)
	}
	for i := 0; i < 2; i++ {
		if seq, err := store.SendDescription(ctx, b, c.ID, "offer-1", "offer", sdp); err != nil || seq != 1 {
			t.Fatal("offer retry", err)
		}
	}
	if _, err = store.SendDescription(ctx, b, c.ID, "changed", "offer", sdp); !errors.Is(err, call.ErrTransition) {
		t.Fatal("offer overwritten", err)
	}
	snapshot, err := other.SyncDescriptions(ctx, a, c.ID, 0)
	if err != nil || len(snapshot.Descriptions) != 1 || snapshot.Descriptions[0].SDP != sdp {
		t.Fatal("cross-instance delivery", err)
	}
	if snapshot, err = other.SyncDescriptions(ctx, b, c.ID, 0); err != nil || len(snapshot.Descriptions) != 0 {
		t.Fatal("echoed own SDP", err)
	}
	if snapshot, err = other.SyncDescriptions(ctx, a, c.ID, 1); err != nil || len(snapshot.Descriptions) != 0 {
		t.Fatal("cursor replay", err)
	}
	if _, err = other.SyncDescriptions(ctx, outsider, c.ID, 0); !errors.Is(err, call.ErrNotFound) {
		t.Fatal("outsider read", err)
	}
	if _, err = other.SendDescription(ctx, outsider, c.ID, "outsider", "answer", sdp); !errors.Is(err, call.ErrNotFound) {
		t.Fatal("outsider write", err)
	}
	if seq, err := other.SendDescription(ctx, a, c.ID, "answer-1", "answer", sdp); err != nil || seq != 2 {
		t.Fatal("answer", err)
	}
	if snapshot, err = store.SyncDescriptions(ctx, b, c.ID, 0); err != nil || len(snapshot.Descriptions) != 1 || snapshot.Descriptions[0].Type != "answer" {
		t.Fatal("answer delivery", err)
	}

	candidates := []call.Candidate{{SDP: "candidate:1 1 udp 1 192.0.2.1 1234 typ host", Mid: "0", Index: 0}}
	if err := store.SendCandidates(ctx, b, c.ID, candidates); err != nil {
		t.Fatal(err)
	}
	candidates = append(candidates, call.Candidate{SDP: "candidate:2 1 udp 1 192.0.2.2 1234 typ relay", Mid: "0", Index: 0})
	if err := store.SendCandidates(ctx, b, c.ID, candidates); err != nil {
		t.Fatal(err)
	}
	if err := store.SendCandidates(ctx, b, c.ID, candidates[:1]); err != nil {
		t.Fatal("old retry", err)
	}
	trickled, err := store.SyncDescriptions(ctx, a, c.ID, 1)
	if err != nil || len(trickled.Descriptions) != 0 || len(trickled.Candidates) != 2 {
		t.Fatal("late candidates after SDP cursor", err, trickled)
	}
	ownIce, err := store.SyncDescriptions(ctx, b, c.ID, 2)
	if err != nil || len(ownIce.Candidates) != 0 {
		t.Fatal("echoed candidates", err)
	}
	changed := append([]call.Candidate(nil), candidates...)
	changed[0].SDP = "candidate:changed"
	if err := store.SendCandidates(ctx, b, c.ID, changed); !errors.Is(err, call.ErrTransition) {
		t.Fatal("rewrote candidates", err)
	}
	if err = store.Cleanup(ctx, time.Now().Add(3*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if snapshot, err = store.SyncDescriptions(ctx, b, c.ID, 0); err != nil || len(snapshot.Descriptions) != 0 {
		t.Fatal("SDP retention", err)
	}
	if _, err = store.ActOnCall(ctx, a, c.ID, "end"); err != nil {
		t.Fatal(err)
	}
	if err := store.SendCandidates(ctx, b, c.ID, candidates); !errors.Is(err, call.ErrTransition) {
		t.Fatal("candidates after end", err)
	}
	if _, err = store.SendDescription(ctx, b, c.ID, "after-end", "offer", sdp); !errors.Is(err, call.ErrTransition) {
		t.Fatal("send after end", err)
	}
}

func TestMediaWebSocketLargeSDPAndTerminalSnapshot(t *testing.T) {
	store, _ := database(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	a, _ := callUser(t, store, 1)
	b, token := callUser(t, store, 2)
	invite, err := store.CreateInvite(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	c, err := store.RedeemInvite(ctx, b, invite.Token)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = store.ActOnCall(ctx, a, c.ID, "accept"); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(httpapi.New(store, slog.New(slog.NewTextHandler(io.Discard, nil))).Handler())
	defer server.Close()
	ws, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(server.URL, "http")+"/v1/signaling", &websocket.DialOptions{Subprotocols: []string{"zisee.v1"}, HTTPHeader: http.Header{"Authorization": []string{"Bearer " + token}}})
	if err != nil {
		t.Fatal(err)
	}
	defer ws.CloseNow()
	ws.SetReadLimit(65536)
	if _, _, err = ws.Read(ctx); err != nil {
		t.Fatal(err)
	}
	msg := map[string]any{"v": 1, "type": "media.send", "id": "large-offer", "callId": c.ID, "description": map[string]string{"type": "offer", "sdp": "v=0\r\n" + strings.Repeat("a=test\r\n", 1000)}}
	data, _ := json.Marshal(msg)
	if err = ws.Write(ctx, websocket.MessageText, data); err != nil {
		t.Fatal(err)
	}
	_, data, err = ws.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var reply struct {
		Type     string             `json:"type"`
		Sequence int                `json:"sequence"`
		Snapshot call.MediaSnapshot `json:"snapshot"`
	}
	if err = json.Unmarshal(data, &reply); err != nil || reply.Type != "media.ack" || reply.Sequence != 1 {
		t.Fatal("missing ack", err)
	}

	data, _ = json.Marshal(map[string]any{"v": 1, "type": "media.ice", "id": "ice-batch", "callId": c.ID, "candidates": []call.Candidate{{SDP: "candidate:1 1 udp 1 192.0.2.1 1234 typ host", Mid: "0", Index: 0}}})
	if err = ws.Write(ctx, websocket.MessageText, data); err != nil {
		t.Fatal(err)
	}
	_, data, err = ws.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &reply); err != nil || reply.Type != "media.ack" {
		t.Fatal("ICE ack", err, string(data))
	}
	trickled, err := store.SyncDescriptions(ctx, a, c.ID, 1)
	if err != nil || len(trickled.Candidates) != 1 {
		t.Fatal("websocket ICE delivery", err)
	}
	if _, err = store.ActOnCall(ctx, a, c.ID, "end"); err != nil {
		t.Fatal(err)
	}
	data, _ = json.Marshal(map[string]any{"v": 1, "type": "media.sync", "id": "terminal", "callId": c.ID, "after": 0})
	if err = ws.Write(ctx, websocket.MessageText, data); err != nil {
		t.Fatal(err)
	}
	_, data, err = ws.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &reply); err != nil || reply.Snapshot.Call.State != "ended" || len(reply.Snapshot.Descriptions) != 0 {
		t.Fatal("terminal snapshot", err)
	}
}
