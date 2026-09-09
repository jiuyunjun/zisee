package httpapi

import (
	"errors"
	"net/http"
	"zisee/server/internal/call"
	"zisee/server/internal/identity"
)

func (s *Server) callRequest(w http.ResponseWriter, r *http.Request) {
	session, _, ok := s.authorize(w, r)
	if !ok {
		return
	}
	store, ok := s.store.(call.Store)
	if !ok {
		writeError(w, 503, "temporarily_unavailable")
		return
	}
	var value any
	var err error
	switch r.Pattern {
	case "POST /v1/invites":
		value, err = store.CreateInvite(r.Context(), session.IdentityID)
	case "POST /v1/invites/redeem":
		var req struct {
			Token string `json:"token"`
		}
		if !decode(w, r, &req) {
			return
		}
		var redeemed call.Call
		redeemed, err = store.RedeemInvite(r.Context(), session.IdentityID, req.Token)
		if err == nil {
			s.wake(redeemed)
		}
		value = redeemed
	case "GET /v1/calls/current":
		value, err = store.CurrentCall(r.Context(), session.IdentityID)
	case "GET /v1/calls/{callId}":
		value, err = store.GetCall(r.Context(), session.IdentityID, r.PathValue("callId"))
	case "POST /v1/calls/{callId}/actions":
		var req struct {
			Action string `json:"action"`
		}
		if !decode(w, r, &req) {
			return
		}
		value, err = store.ActOnCall(r.Context(), session.IdentityID, r.PathValue("callId"), req.Action)
	}
	if err != nil {
		s.callFail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}

func callError(err error) (int, string) {
	switch {
	case errors.Is(err, identity.ErrInvalid):
		return 400, "invalid_request"
	case errors.Is(err, call.ErrNotFound):
		return 404, "call_not_found"
	case errors.Is(err, call.ErrInvite):
		return 404, "invite_unavailable"
	case errors.Is(err, call.ErrBusy):
		return 409, "participant_busy"
	case errors.Is(err, call.ErrGeneration):
		return http.StatusConflict, "stale_media_generation"
	case errors.Is(err, call.ErrTransition):
		return 409, "invalid_call_transition"
	default:
		return 503, "temporarily_unavailable"
	}
}

func (s *Server) callFail(w http.ResponseWriter, err error) {
	status, code := callError(err)
	if status == 503 {
		s.log.Error("call_request_failed")
	}
	writeError(w, status, code)
}
