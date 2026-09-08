package call

import (
	"context"
	"errors"
	"time"
)

var (
	ErrNotFound   = errors.New("call_not_found")
	ErrInvite     = errors.New("invite_unavailable")
	ErrBusy       = errors.New("participant_busy")
	ErrTransition = errors.New("invalid_call_transition")
)

type Invite struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expiresAt"`
}

type Call struct {
	ID        string    `json:"callId"`
	CallerID  string    `json:"callerId"`
	CalleeID  string    `json:"calleeId"`
	State     string    `json:"state"`
	ExpiresAt time.Time `json:"expiresAt"`
}

// Every method receives the authenticated identity, never a client-supplied actor.
type Store interface {
	CreateInvite(context.Context, string) (Invite, error)
	RedeemInvite(context.Context, string, string) (Call, error)
	GetCall(context.Context, string, string) (Call, error)
	CurrentCall(context.Context, string) (*Call, error)
	ActOnCall(context.Context, string, string, string) (Call, error)
}
