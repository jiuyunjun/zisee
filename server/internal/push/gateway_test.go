package push

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
)

type fakeRegistry struct {
	mu      sync.Mutex
	targets []Target
	cleared []string
}

func (f *fakeRegistry) SetPushToken(context.Context, string, string, string, string) error {
	return nil
}
func (f *fakeRegistry) PushTargets(context.Context, string) ([]Target, error) {
	return f.targets, nil
}
func (f *fakeRegistry) ClearPushToken(_ context.Context, deviceID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.cleared = append(f.cleared, deviceID)
	return nil
}

type fakeSender struct {
	mu   sync.Mutex
	sent []Target
	errs map[string]error
}

func (f *fakeSender) Send(_ context.Context, target Target, _ Invite) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent = append(f.sent, target)
	return f.errs[target.DeviceID]
}

func discardLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestGatewayNotifyClearsUnregisteredAndKeepsGoing(t *testing.T) {
	registry := &fakeRegistry{targets: []Target{
		{DeviceID: "d1", Provider: "fcm", Token: "t1"},
		{DeviceID: "d2", Provider: "fcm", Token: "t2"},
		{DeviceID: "d3", Provider: "fcm", Token: "t3"},
	}}
	sender := &fakeSender{errs: map[string]error{
		"d2": ErrUnregistered,
		"d3": errors.New("transient"),
	}}
	NewGateway(sender, registry, discardLogger()).Notify(context.Background(), "zid_callee", testInvite())

	if len(sender.sent) != 3 {
		t.Fatalf("expected all 3 targets attempted, got %d", len(sender.sent))
	}
	if len(registry.cleared) != 1 || registry.cleared[0] != "d2" {
		t.Fatalf("expected only d2 cleared, got %v", registry.cleared)
	}
}

func TestGatewayNotifySwallowsRegistryError(t *testing.T) {
	// PushTargets failing must not panic or propagate — the call still rings.
	NewGateway(&fakeSender{}, brokenRegistry{}, discardLogger()).Notify(context.Background(), "x", testInvite())
}

type brokenRegistry struct{}

func (brokenRegistry) SetPushToken(context.Context, string, string, string, string) error { return nil }
func (brokenRegistry) PushTargets(context.Context, string) ([]Target, error) {
	return nil, errors.New("db down")
}
func (brokenRegistry) ClearPushToken(context.Context, string) error { return nil }
