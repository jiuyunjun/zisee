package firestore

import (
	"context"
	"errors"
	"testing"
	"zisee/server/internal/call"
)

func TestRememberedContactsRequireAcceptanceAndCanBeRevoked(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	register(t, store, "zid_a", "Alice")
	register(t, store, "zid_b", "Bob")
	if _, err := store.CallContact(ctx, "zid_a", "zid_b"); !errors.Is(err, call.ErrNotFound) {
		t.Fatalf("stranger call: %v", err)
	}
	invite, err := store.CreateInvite(ctx, "zid_b")
	if err != nil {
		t.Fatal(err)
	}
	c, err := store.RedeemInvite(ctx, "zid_a", invite.Token)
	if err != nil {
		t.Fatal(err)
	}
	contacts, err := store.ListContacts(ctx, "zid_a")
	if err != nil || len(contacts) != 0 {
		t.Fatal("ringing must not create contact", err)
	}
	if _, err = store.ActOnCall(ctx, "zid_b", c.ID, "accept"); err != nil {
		t.Fatal(err)
	}
	for _, actor := range []string{"zid_a", "zid_b"} {
		contacts, err = store.ListContacts(ctx, actor)
		if err != nil || len(contacts) != 1 || contacts[0].IdentityID == actor {
			t.Fatal("missing mutual contact", err, contacts)
		}
	}
	if _, err = store.CallContact(ctx, "zid_a", "zid_b"); !errors.Is(err, call.ErrBusy) {
		t.Fatal("busy call permitted", err)
	}
	if _, err = store.ActOnCall(ctx, "zid_a", c.ID, "end"); err != nil {
		t.Fatal(err)
	}
	again, err := store.CallContact(ctx, "zid_b", "zid_a")
	if err != nil || again.CalleeID != "zid_a" || again.State != "ringing" {
		t.Fatal("redial", err, again)
	}
	if _, err = store.ActOnCall(ctx, "zid_a", again.ID, "reject"); err != nil {
		t.Fatal(err)
	}
	if err = store.RemoveContact(ctx, "zid_a", "zid_b"); err != nil {
		t.Fatal(err)
	}
	if _, err = store.CallContact(ctx, "zid_b", "zid_a"); !errors.Is(err, call.ErrNotFound) {
		t.Fatal("revoked relationship called", err)
	}
}
