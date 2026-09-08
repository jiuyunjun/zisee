package identity

import (
	"context"
	"errors"
	"time"
)

var (
	ErrInvalid      = errors.New("invalid_request")
	ErrUnauthorized = errors.New("unauthorized")
	ErrConflict     = errors.New("identity_conflict")
	ErrRateLimited  = errors.New("rate_limited")
)

const ChallengeTTL = 2 * time.Minute
const SessionTTL = 15 * time.Minute

type Identity struct {
	ID          string    `json:"identityId"`
	DisplayName string    `json:"displayName"`
	CreatedAt   time.Time `json:"createdAt"`
	UpdatedAt   time.Time `json:"updatedAt"`
}

type Registration struct {
	IdentityID  string `json:"identityId"`
	DeviceID    string `json:"deviceId"`
	DisplayName string `json:"displayName"`
	PublicKey   string `json:"publicKey"`
	Signature   string `json:"signature"`
}

type Device struct {
	ID         string
	IdentityID string
	PublicKey  []byte
}

type Challenge struct {
	ID        string    `json:"challengeId"`
	Nonce     string    `json:"nonce"`
	ExpiresAt time.Time `json:"expiresAt"`
	DeviceID  string    `json:"-"`
}

type Session struct {
	IdentityID string
	DeviceID   string
	TokenHash  []byte
	ExpiresAt  time.Time
}

type Token struct {
	AccessToken string    `json:"accessToken"`
	TokenType   string    `json:"tokenType"`
	ExpiresAt   time.Time `json:"expiresAt"`
}

// Implementations persist state and make registration and challenge redemption atomic.
type Store interface {
	Register(context.Context, Identity, Device) (Identity, error)
	Device(context.Context, string) (Device, error)
	PutChallenge(context.Context, Challenge, time.Time) error
	Challenge(context.Context, string, time.Time) (Challenge, error)
	Redeem(context.Context, Challenge, Session, time.Time) error
	Session(context.Context, []byte, time.Time) (Session, error)
	Identity(context.Context, string) (Identity, error)
	Rename(context.Context, string, string, time.Time) (Identity, error)
	DeleteSession(context.Context, []byte) error
	Cleanup(context.Context, time.Time) error
	Ping(context.Context) error
}
