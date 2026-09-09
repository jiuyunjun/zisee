package httpapi

import (
	"context"
	"net/http"
	"time"

	"zisee/server/internal/call"
	"zisee/server/internal/push"
)

func (s *Server) devicePushToken(w http.ResponseWriter, r *http.Request) {
	session, _, ok := s.authorize(w, r)
	if !ok {
		return
	}
	registry, ok := s.store.(push.Registry)
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "temporarily_unavailable")
		return
	}
	// A session may only register the token of the device it authenticated as:
	// the wake-up target is bound to the signing key, not a client-named device.
	if r.PathValue("deviceId") != session.DeviceID {
		writeError(w, http.StatusForbidden, "device_mismatch")
		return
	}
	var req struct {
		Provider string `json:"provider"`
		Token    string `json:"token"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.Provider != "fcm" || req.Token == "" || len(req.Token) > 4096 {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	if err := registry.SetPushToken(r.Context(), session.IdentityID, session.DeviceID, req.Provider, req.Token); err != nil {
		s.fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// wake fires a background push so a backgrounded or killed callee device rings.
// Delivery is best-effort and must not delay or fail the call request: the call
// is already ringing on the server (docs/architecture/CALL_DELIVERY.md §7).
func (s *Server) wake(c call.Call) {
	if s.push == nil || c.State != "ringing" {
		return
	}
	name := ""
	lookup, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	if caller, err := s.store.Identity(lookup, c.CallerID); err == nil {
		name = caller.DisplayName
	}
	cancel()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		s.push.Notify(ctx, c.CalleeID, push.Invite{
			CallID: c.ID, CallerID: c.CallerID, CallerName: name,
			MediaType: "video", IssuedAt: time.Now().UTC(), ExpiresAt: c.ExpiresAt,
		})
	}()
}
