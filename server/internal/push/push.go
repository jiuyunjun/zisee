// Package push turns a "call is ringing" event into a high-priority wake-up
// notification on the callee's registered devices.
//
// FCM is only a wake channel (see docs/architecture/CALL_DELIVERY.md §7): the
// message carries just enough to identify the call, never SDP or ICE. Delivery
// is best-effort — a failed push must never fail the call request that
// triggered it.
package push

import (
	"context"
	"errors"
	"log/slog"
	"time"
)

// ErrUnregistered means the provider rejected the token permanently (the app
// was uninstalled, data cleared, or the token rotated). The caller clears it.
var ErrUnregistered = errors.New("push_token_unregistered")

// Invite is the wake-up payload. Keep it small: caller_name is included so the
// device can ring without a network round-trip (§10), the avatar loads later.
type Invite struct {
	CallID     string
	CallerID   string
	CallerName string
	MediaType  string
	IssuedAt   time.Time
	ExpiresAt  time.Time
}

// Target is one registered device to wake.
type Target struct {
	DeviceID string
	Provider string
	Token    string
}

// Sender delivers one Invite to one Target. Send returns ErrUnregistered when
// the token is permanently invalid.
type Sender interface {
	Send(ctx context.Context, target Target, invite Invite) error
}

// Registry stores and retrieves per-device push tokens. The store implements it.
type Registry interface {
	SetPushToken(ctx context.Context, identityID, deviceID, provider, token string) error
	PushTargets(ctx context.Context, identityID string) ([]Target, error)
	ClearPushToken(ctx context.Context, deviceID string) error
}

// Gateway fans an Invite out to every device the callee has registered.
type Gateway struct {
	sender   Sender
	registry Registry
	log      *slog.Logger
}

func NewGateway(sender Sender, registry Registry, log *slog.Logger) *Gateway {
	return &Gateway{sender: sender, registry: registry, log: log}
}

// Notify wakes every registered device of calleeID. It logs and swallows all
// errors: the call is already ringing on the server regardless of push.
func (g *Gateway) Notify(ctx context.Context, calleeID string, invite Invite) {
	targets, err := g.registry.PushTargets(ctx, calleeID)
	if err != nil {
		g.log.Error("push_targets_failed", "call", invite.CallID)
		return
	}
	for _, target := range targets {
		switch err := g.sender.Send(ctx, target, invite); {
		case err == nil:
			g.log.Info("push_sent", "call", invite.CallID, "device", target.DeviceID)
		case errors.Is(err, ErrUnregistered):
			if clearErr := g.registry.ClearPushToken(ctx, target.DeviceID); clearErr != nil {
				g.log.Error("push_token_clear_failed", "device", target.DeviceID)
			} else {
				g.log.Info("push_token_cleared", "device", target.DeviceID)
			}
		default:
			g.log.Error("push_send_failed", "call", invite.CallID, "device", target.DeviceID)
		}
	}
}
