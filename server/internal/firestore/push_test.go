package firestore

import (
	"context"
	"errors"
	"testing"

	"zisee/server/internal/identity"
)

func TestPushTokenRegistrationAndTargets(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	device := register(t, store, "zid_pa", "A")
	register(t, store, "zid_pb", "B")

	// No token yet: no wake-up target.
	targets, err := store.PushTargets(ctx, "zid_pa")
	if err != nil {
		t.Fatalf("targets: %v", err)
	}
	if len(targets) != 0 {
		t.Fatalf("expected no targets, got %v", targets)
	}

	// A session cannot register a token onto another identity's device.
	if err := store.SetPushToken(ctx, "zid_pb", device.ID, "fcm", "stolen"); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatalf("cross-identity register must be unauthorized, got %v", err)
	}
	if err := store.SetPushToken(ctx, "zid_pa", "zdev_missing", "fcm", "x"); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatalf("unknown device must be unauthorized, got %v", err)
	}

	if err := store.SetPushToken(ctx, "zid_pa", device.ID, "fcm", "tok-1"); err != nil {
		t.Fatalf("set: %v", err)
	}
	targets, err = store.PushTargets(ctx, "zid_pa")
	if err != nil {
		t.Fatalf("targets: %v", err)
	}
	if len(targets) != 1 || targets[0].Token != "tok-1" || targets[0].Provider != "fcm" || targets[0].DeviceID != device.ID {
		t.Fatalf("unexpected targets %+v", targets)
	}

	// Rotation replaces the token in place.
	if err := store.SetPushToken(ctx, "zid_pa", device.ID, "fcm", "tok-2"); err != nil {
		t.Fatalf("rotate: %v", err)
	}
	targets, _ = store.PushTargets(ctx, "zid_pa")
	if len(targets) != 1 || targets[0].Token != "tok-2" {
		t.Fatalf("rotation not applied: %+v", targets)
	}

	// Clearing an unregistered token drops the target and is idempotent.
	if err := store.ClearPushToken(ctx, device.ID); err != nil {
		t.Fatalf("clear: %v", err)
	}
	if err := store.ClearPushToken(ctx, device.ID); err != nil {
		t.Fatalf("clear must be idempotent: %v", err)
	}
	if err := store.ClearPushToken(ctx, "zdev_missing"); err != nil {
		t.Fatalf("clear missing must be a no-op: %v", err)
	}
	targets, _ = store.PushTargets(ctx, "zid_pa")
	if len(targets) != 0 {
		t.Fatalf("expected no targets after clear, got %+v", targets)
	}
}
