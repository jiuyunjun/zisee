package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"zisee/server/internal/identity"
)

type Server struct {
	auth     *identity.Service
	store    identity.Store
	log      *slog.Logger
	sockets  chan struct{}
	mu       sync.Mutex
	window   time.Time
	requests int
}

func New(store identity.Store, log *slog.Logger) *Server {
	return &Server{auth: identity.New(store), store: store, log: log, sockets: make(chan struct{}, 64)}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if s.store.Ping(ctx) != nil {
			writeError(w, http.StatusServiceUnavailable, "not_ready")
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
	})
	mux.HandleFunc("POST /v1/identity/bootstrap", s.bootstrap)
	mux.HandleFunc("POST /v1/auth/challenge", s.challenge)
	mux.HandleFunc("POST /v1/auth/token", s.token)
	mux.HandleFunc("GET /v1/identity", s.me)
	mux.HandleFunc("PATCH /v1/identity", s.rename)
	mux.HandleFunc("DELETE /v1/auth/session", s.logout)
	mux.HandleFunc("GET /v1/signaling", s.signaling)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		// Native clients use Authorization headers. Credentials in URLs can leak in proxy logs.
		if r.URL.RawQuery != "" {
			writeError(w, http.StatusBadRequest, "query_not_allowed")
			return
		}
		if r.Header.Get("Origin") != "" {
			writeError(w, http.StatusForbidden, "browser_origin_not_allowed")
			return
		}
		if r.URL.Path != "/healthz" && r.URL.Path != "/readyz" && !s.allow() {
			w.Header().Set("Retry-After", "1")
			writeError(w, http.StatusTooManyRequests, "rate_limited")
			return
		}
		if r.URL.Path != "/v1/signaling" {
			ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
			defer cancel()
			r = r.WithContext(ctx)
		}
		mux.ServeHTTP(w, r)
	})
}

// A fixed per-instance ceiling bounds CPU/DB pressure without trusting forwarded IP headers.
// Production also needs distributed/edge quotas; this is not a global abuse-control service.
func (s *Server) allow() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	if now.Sub(s.window) >= time.Second {
		s.window, s.requests = now, 0
	}
	s.requests++
	return s.requests <= 30
}

func decode(w http.ResponseWriter, r *http.Request, value any) bool {
	kind, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || kind != "application/json" {
		writeError(w, http.StatusUnsupportedMediaType, "json_required")
		return false
	}
	body := http.MaxBytesReader(w, r.Body, 8192)
	decoder := json.NewDecoder(body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return false
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	// Client disconnects are expected; no request contents or credentials are logged.
	if err := json.NewEncoder(w).Encode(value); err != nil {
		return
	}
}

func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

func (s *Server) fail(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, identity.ErrInvalid):
		writeError(w, http.StatusBadRequest, "invalid_request")
	case errors.Is(err, identity.ErrUnauthorized):
		writeError(w, http.StatusUnauthorized, "unauthorized")
	case errors.Is(err, identity.ErrConflict):
		writeError(w, http.StatusConflict, "identity_conflict")
	case errors.Is(err, identity.ErrRateLimited):
		w.Header().Set("Retry-After", "120")
		writeError(w, http.StatusTooManyRequests, "rate_limited")
	default:
		s.log.Error("request_failed")
		writeError(w, http.StatusServiceUnavailable, "temporarily_unavailable")
	}
}

func (s *Server) bootstrap(w http.ResponseWriter, r *http.Request) {
	var req identity.Registration
	if !decode(w, r, &req) {
		return
	}
	value, err := s.auth.Bootstrap(r.Context(), req)
	if err != nil {
		s.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}

func (s *Server) challenge(w http.ResponseWriter, r *http.Request) {
	var req struct {
		DeviceID string `json:"deviceId"`
	}
	if !decode(w, r, &req) {
		return
	}
	value, err := s.auth.NewChallenge(r.Context(), req.DeviceID)
	if err != nil {
		s.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}

func (s *Server) token(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ChallengeID string `json:"challengeId"`
		Signature   string `json:"signature"`
	}
	if !decode(w, r, &req) {
		return
	}
	value, err := s.auth.Authenticate(r.Context(), req.ChallengeID, req.Signature)
	if err != nil {
		s.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}

func (s *Server) authorize(w http.ResponseWriter, r *http.Request) (identity.Session, string, bool) {
	fields := strings.Fields(r.Header.Get("Authorization"))
	if len(r.Header.Values("Authorization")) != 1 || len(fields) != 2 || !strings.EqualFold(fields[0], "Bearer") {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return identity.Session{}, "", false
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	session, err := s.auth.Authorize(ctx, fields[1])
	if err != nil {
		s.fail(w, err)
		return identity.Session{}, "", false
	}
	return session, fields[1], true
}

func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	session, _, ok := s.authorize(w, r)
	if !ok {
		return
	}
	value, err := s.auth.Me(r.Context(), session)
	if err != nil {
		s.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}

func (s *Server) rename(w http.ResponseWriter, r *http.Request) {
	session, _, ok := s.authorize(w, r)
	if !ok {
		return
	}
	var req struct {
		DisplayName string `json:"displayName"`
	}
	if !decode(w, r, &req) {
		return
	}
	value, err := s.auth.Rename(r.Context(), session, req.DisplayName)
	if err != nil {
		s.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	session, _, ok := s.authorize(w, r)
	if !ok {
		return
	}
	if err := s.auth.Logout(r.Context(), session); err != nil {
		s.fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) signaling(w http.ResponseWriter, r *http.Request) {
	session, token, ok := s.authorize(w, r)
	if !ok {
		return
	}
	if !strings.Contains(r.Header.Get("Sec-WebSocket-Protocol"), "zisee.v1") {
		writeError(w, http.StatusBadRequest, "subprotocol_required")
		return
	}
	select {
	case s.sockets <- struct{}{}:
		defer func() { <-s.sockets }()
	default:
		writeError(w, http.StatusServiceUnavailable, "connection_limit")
		return
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{"zisee.v1"}})
	if err != nil {
		return
	} // Accept already writes the handshake error response.
	defer conn.CloseNow()
	if conn.Subprotocol() != "zisee.v1" {
		conn.Close(websocket.StatusPolicyViolation, "subprotocol_required")
		return
	}
	conn.SetReadLimit(4096)
	ctx, cancel := context.WithDeadline(r.Context(), session.ExpiresAt)
	defer cancel()
	if err := wsWrite(ctx, conn, map[string]any{"v": 1, "type": "session.ready", "expiresAt": session.ExpiresAt}); err != nil {
		return
	}
	for {
		readCtx, readCancel := context.WithTimeout(ctx, 45*time.Second)
		kind, data, err := conn.Read(readCtx)
		readCancel()
		if err != nil {
			return
		}
		if kind != websocket.MessageText {
			conn.Close(websocket.StatusUnsupportedData, "text_required")
			return
		}
		checkCtx, checkCancel := context.WithTimeout(ctx, 5*time.Second)
		_, err = s.auth.Authorize(checkCtx, token)
		checkCancel()
		if err != nil {
			conn.Close(websocket.StatusPolicyViolation, "reauthenticate")
			return
		}
		var message struct {
			Version int    `json:"v"`
			Type    string `json:"type"`
			ID      string `json:"id"`
		}
		if json.Unmarshal(data, &message) != nil || message.Version != 1 || len(message.ID) > 64 {
			conn.Close(websocket.StatusPolicyViolation, "invalid_message")
			return
		}
		// Authenticated transport foundation only. No unvalidated arbitrary peer relay.
		if message.Type != "ping" {
			conn.Close(websocket.StatusPolicyViolation, "unsupported_message")
			return
		}
		if err := wsWrite(ctx, conn, map[string]any{"v": 1, "type": "pong", "id": message.ID}); err != nil {
			return
		}
		// Prevent an authenticated client from flooding database checks and responses.
		timer := time.NewTimer(100 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}
}

func wsWrite(ctx context.Context, conn *websocket.Conn, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return conn.Write(ctx, websocket.MessageText, data)
}
