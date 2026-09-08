// Firestore integration tests. They run only when FIRESTORE_EMULATOR_HOST
// points at an emulator, and never against a real project: every case writes
// and deletes data.
//
// Start one with:
//
//	docker run -d --name zisee-fs-emu -p 18085:8085 \
//	  gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators \
//	  gcloud emulators firestore start --host-port=0.0.0.0:8085 --project=zisee-test
//	FIRESTORE_EMULATOR_HOST=127.0.0.1:18085 go test ./internal/firestore/...
package firestore

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"cloud.google.com/go/firestore"
	"zisee/server/internal/call"
	"zisee/server/internal/identity"
)

func newStore(t *testing.T) *Store {
	t.Helper()
	if os.Getenv("FIRESTORE_EMULATOR_HOST") == "" {
		t.Skip("set FIRESTORE_EMULATOR_HOST to run Firestore tests")
	}
	// A unique project id per test keeps emulator data isolated.
	project := fmt.Sprintf("zisee-test-%d", time.Now().UnixNano())
	store, err := Open(context.Background(), project, "")
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(store.Close)
	return store
}

func register(t *testing.T, store *Store, id, name string) identity.Device {
	t.Helper()
	now := time.Now().UTC()
	device := identity.Device{ID: id + "-device", IdentityID: id, PublicKey: []byte(id + "-key")}
	value := identity.Identity{ID: id, DisplayName: name, CreatedAt: now, UpdatedAt: now}
	if _, err := store.Register(context.Background(), value, device); err != nil {
		t.Fatalf("register %s: %v", id, err)
	}
	return device
}

func TestRegisterIsIdempotentAndSingleDevice(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	device := register(t, store, "zid_a", "A")

	// The same signed registration replayed must not conflict or rename.
	again := identity.Identity{ID: "zid_a", DisplayName: "renamed", CreatedAt: time.Now().UTC(), UpdatedAt: time.Now().UTC()}
	result, err := store.Register(ctx, again, device)
	if err != nil {
		t.Fatalf("replay must be idempotent: %v", err)
	}
	if result.DisplayName != "A" {
		t.Fatal("replay must not rename the identity")
	}
	// A second device for the same identity replaces nothing: it is refused.
	other := identity.Device{ID: "zid_a-device-2", IdentityID: "zid_a", PublicKey: []byte("other")}
	if _, err := store.Register(ctx, again, other); !errors.Is(err, identity.ErrConflict) {
		t.Fatalf("second device must conflict, got %v", err)
	}
}

func TestChallengeLimitAndSingleUse(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	device := register(t, store, "zid_b", "B")
	now := time.Now().UTC()

	for i := range maxChallengesPerDevice {
		c := identity.Challenge{ID: fmt.Sprintf("challenge-%d", i), Nonce: "n", DeviceID: device.ID,
			ExpiresAt: now.Add(identity.ChallengeTTL)}
		if err := store.PutChallenge(ctx, c, now); err != nil {
			t.Fatalf("challenge %d: %v", i, err)
		}
	}
	over := identity.Challenge{ID: "challenge-over", Nonce: "n", DeviceID: device.ID, ExpiresAt: now.Add(identity.ChallengeTTL)}
	if err := store.PutChallenge(ctx, over, now); !errors.Is(err, identity.ErrRateLimited) {
		t.Fatalf("expected rate limit, got %v", err)
	}

	stored, err := store.Challenge(ctx, "challenge-0", now)
	if err != nil {
		t.Fatalf("challenge lookup: %v", err)
	}
	hash := sha256.Sum256([]byte("token-b"))
	session := identity.Session{IdentityID: "zid_b", DeviceID: device.ID,
		TokenHash: hash[:], ExpiresAt: now.Add(identity.SessionTTL)}
	if err := store.Redeem(ctx, stored, session, now); err != nil {
		t.Fatalf("redeem: %v", err)
	}
	// The challenge is consumed, so a replay must fail.
	if err := store.Redeem(ctx, stored, session, now); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatalf("challenge must be single use, got %v", err)
	}
	live, err := store.Session(ctx, hash[:], now)
	if err != nil || live.IdentityID != "zid_b" {
		t.Fatalf("session lookup: %v", err)
	}
	if _, err := store.Session(ctx, hash[:], now.Add(2*identity.SessionTTL)); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatal("expired session must be rejected")
	}
}

func TestRedeemRevokesPreviousSession(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	device := register(t, store, "zid_c", "C")
	now := time.Now().UTC()
	first := sha256.Sum256([]byte("token-1"))
	second := sha256.Sum256([]byte("token-2"))
	for index, hash := range [][32]byte{first, second} {
		c := identity.Challenge{ID: fmt.Sprintf("c-%d", index), Nonce: "n", DeviceID: device.ID,
			ExpiresAt: now.Add(identity.ChallengeTTL)}
		if err := store.PutChallenge(ctx, c, now); err != nil {
			t.Fatal(err)
		}
		stored, err := store.Challenge(ctx, c.ID, now)
		if err != nil {
			t.Fatal(err)
		}
		session := identity.Session{IdentityID: "zid_c", DeviceID: device.ID,
			TokenHash: hash[:], ExpiresAt: now.Add(identity.SessionTTL)}
		if err := store.Redeem(ctx, stored, session, now); err != nil {
			t.Fatal(err)
		}
	}
	// One active token per device: the first must be gone.
	if _, err := store.Session(ctx, first[:], now); !errors.Is(err, identity.ErrUnauthorized) {
		t.Fatal("reauthentication must revoke the previous token")
	}
	if _, err := store.Session(ctx, second[:], now); err != nil {
		t.Fatalf("latest token must stay valid: %v", err)
	}
}

func TestOneInvitePerCreator(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	register(t, store, "zid_host", "Host")
	register(t, store, "zid_guest", "Guest")

	first, err := store.CreateInvite(ctx, "zid_host")
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.CreateInvite(ctx, "zid_host")
	if err != nil {
		t.Fatal(err)
	}
	// Replacing an invitation revokes the previous token.
	if _, err := store.RedeemInvite(ctx, "zid_guest", first.Token); !errors.Is(err, call.ErrInvite) {
		t.Fatalf("replaced token must be refused, got %v", err)
	}
	created, err := store.RedeemInvite(ctx, "zid_guest", second.Token)
	if err != nil {
		t.Fatalf("current token must work: %v", err)
	}
	if created.CallerID != "zid_guest" || created.CalleeID != "zid_host" || created.State != "ringing" {
		t.Fatalf("unexpected call %+v", created)
	}
	// Redeeming again by the same identity returns the same call.
	same, err := store.RedeemInvite(ctx, "zid_guest", second.Token)
	if err != nil || same.ID != created.ID {
		t.Fatalf("repeat redemption must be idempotent: %v", err)
	}
}

func TestInviteCannotBeRedeemedByItsCreator(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	register(t, store, "zid_solo", "Solo")
	invite, err := store.CreateInvite(ctx, "zid_solo")
	if err != nil {
		t.Fatal(err)
	}
	// This is the CHECK (caller_id <> callee_id) constraint, now in Go.
	if _, err := store.RedeemInvite(ctx, "zid_solo", invite.Token); !errors.Is(err, call.ErrInvite) {
		t.Fatalf("self call must be refused, got %v", err)
	}
}

func TestBusyParticipantCannotStartASecondCall(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	for _, id := range []string{"zid_1", "zid_2", "zid_3"} {
		register(t, store, id, id)
	}
	invite, err := store.CreateInvite(ctx, "zid_1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.RedeemInvite(ctx, "zid_2", invite.Token); err != nil {
		t.Fatal(err)
	}
	// zid_1 is ringing, so a third party cannot start another call with it.
	next, err := store.CreateInvite(ctx, "zid_1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.RedeemInvite(ctx, "zid_3", next.Token); !errors.Is(err, call.ErrBusy) {
		t.Fatalf("expected busy, got %v", err)
	}
}

func TestConcurrentRedemptionCreatesOneCall(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	register(t, store, "zid_h", "H")
	for _, id := range []string{"zid_g1", "zid_g2"} {
		register(t, store, id, id)
	}
	invite, err := store.CreateInvite(ctx, "zid_h")
	if err != nil {
		t.Fatal(err)
	}
	// Two guests race for the same token; the transaction must admit only one.
	var wait sync.WaitGroup
	results := make([]error, 2)
	guests := []string{"zid_g1", "zid_g2"}
	wait.Add(2)
	for index, guest := range guests {
		go func() {
			defer wait.Done()
			_, results[index] = store.RedeemInvite(ctx, guest, invite.Token)
		}()
	}
	wait.Wait()
	successes := 0
	for _, err := range results {
		if err == nil {
			successes++
		}
	}
	if successes != 1 {
		t.Fatalf("expected exactly one winner, got %d (%v)", successes, results)
	}
}

func TestCallTransitionsAndDescriptionExchange(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	register(t, store, "zid_caller", "Caller")
	register(t, store, "zid_callee", "Callee")
	invite, err := store.CreateInvite(ctx, "zid_callee")
	if err != nil {
		t.Fatal(err)
	}
	created, err := store.RedeemInvite(ctx, "zid_caller", invite.Token)
	if err != nil {
		t.Fatal(err)
	}
	// Only the callee may accept.
	if _, err := store.ActOnCall(ctx, "zid_caller", created.ID, "accept"); !errors.Is(err, call.ErrTransition) {
		t.Fatalf("caller must not accept, got %v", err)
	}
	accepted, err := store.ActOnCall(ctx, "zid_callee", created.ID, "accept")
	if err != nil || accepted.State != "accepted" {
		t.Fatalf("accept: %v %+v", err, accepted)
	}

	sdp := "v=0\r\ns=-\r\n"
	// Only the caller offers.
	if _, err := store.SendDescription(ctx, "zid_callee", created.ID, "m1", "offer", sdp); !errors.Is(err, call.ErrTransition) {
		t.Fatalf("callee must not offer, got %v", err)
	}
	// The answer cannot precede the offer.
	if _, err := store.SendDescription(ctx, "zid_callee", created.ID, "m2", "answer", sdp); !errors.Is(err, call.ErrTransition) {
		t.Fatalf("answer before offer must fail, got %v", err)
	}
	if sequence, err := store.SendDescription(ctx, "zid_caller", created.ID, "m1", "offer", sdp); err != nil || sequence != 1 {
		t.Fatalf("offer: %v seq=%d", err, sequence)
	}
	// Replaying the same message id is idempotent; changing it is not.
	if _, err := store.SendDescription(ctx, "zid_caller", created.ID, "m1", "offer", sdp); err != nil {
		t.Fatalf("identical retry must succeed: %v", err)
	}
	if _, err := store.SendDescription(ctx, "zid_caller", created.ID, "m9", "offer", sdp); !errors.Is(err, call.ErrTransition) {
		t.Fatalf("second negotiation must fail, got %v", err)
	}
	// Each side sees only the peer's description.
	snapshot, err := store.SyncDescriptions(ctx, "zid_callee", created.ID, 0)
	if err != nil || len(snapshot.Descriptions) != 1 || snapshot.Descriptions[0].Type != "offer" {
		t.Fatalf("callee must receive the offer: %v %+v", err, snapshot.Descriptions)
	}
	own, err := store.SyncDescriptions(ctx, "zid_caller", created.ID, 0)
	if err != nil || len(own.Descriptions) != 0 {
		t.Fatalf("caller must not receive its own offer: %v %+v", err, own.Descriptions)
	}
	if _, err := store.SendDescription(ctx, "zid_callee", created.ID, "m2", "answer", sdp); err != nil {
		t.Fatalf("answer: %v", err)
	}

	candidates := []call.Candidate{{SDP: "candidate:1 1 udp 1 192.0.2.1 1234 typ host", Mid: "0", Index: 0}}
	if err := store.SendCandidates(ctx, "zid_caller", created.ID, candidates); err != nil {
		t.Fatal(err)
	}
	candidates = append(candidates, call.Candidate{SDP: "candidate:2 1 udp 1 192.0.2.2 1234 typ relay", Mid: "0", Index: 0})
	if err := store.SendCandidates(ctx, "zid_caller", created.ID, candidates); err != nil {
		t.Fatal(err)
	}
	if err := store.SendCandidates(ctx, "zid_caller", created.ID, candidates[:1]); err != nil {
		t.Fatal("old retry", err)
	}
	trickled, err := store.SyncDescriptions(ctx, "zid_callee", created.ID, 1)
	if err != nil || len(trickled.Descriptions) != 0 || len(trickled.Candidates) != 2 {
		t.Fatal("late candidates after SDP cursor", err, trickled)
	}
	ownIce, err := store.SyncDescriptions(ctx, "zid_caller", created.ID, 2)
	if err != nil || len(ownIce.Candidates) != 0 {
		t.Fatal("echoed candidates", err)
	}
	changed := append([]call.Candidate(nil), candidates...)
	changed[0].SDP = "candidate:changed"
	if err := store.SendCandidates(ctx, "zid_caller", created.ID, changed); !errors.Is(err, call.ErrTransition) {
		t.Fatal("rewrote candidates", err)
	}
	// Ending the call drops the descriptions: there is no cascade in Firestore.
	if _, err := store.ActOnCall(ctx, "zid_caller", created.ID, "end"); err != nil {
		t.Fatal(err)
	}
	if err := store.SendCandidates(ctx, "zid_caller", created.ID, candidates); !errors.Is(err, call.ErrTransition) {
		t.Fatal("candidates after end", err)
	}
	after, err := store.SyncDescriptions(ctx, "zid_callee", created.ID, 0)
	if err != nil {
		t.Fatal(err)
	}
	if after.Call.State != "ended" || len(after.Descriptions) != 0 {
		t.Fatalf("ended call must expose no descriptions: %+v", after)
	}
	for _, sequence := range []int{1, 2} {
		if _, err := store.client.Collection(media).Doc(descriptionID(created.ID, sequence)).Get(ctx); !notFound(err) {
			t.Fatalf("description %d must be deleted, got %v", sequence, err)
		}
	}
}

func TestNonParticipantCannotSeeTheCall(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	for _, id := range []string{"zid_x", "zid_y", "zid_z"} {
		register(t, store, id, id)
	}
	invite, err := store.CreateInvite(ctx, "zid_x")
	if err != nil {
		t.Fatal(err)
	}
	created, err := store.RedeemInvite(ctx, "zid_y", invite.Token)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.GetCall(ctx, "zid_z", created.ID); !errors.Is(err, call.ErrNotFound) {
		t.Fatalf("outsider must not read the call, got %v", err)
	}
	if _, err := store.ActOnCall(ctx, "zid_z", created.ID, "end"); !errors.Is(err, call.ErrNotFound) {
		t.Fatalf("outsider must not end the call, got %v", err)
	}
	if _, err := store.SendDescription(ctx, "zid_z", created.ID, "m", "offer", "v=0\r\n"); !errors.Is(err, call.ErrNotFound) {
		t.Fatalf("outsider must not send SDP, got %v", err)
	}
}

func TestCurrentCallAndExpiry(t *testing.T) {
	store := newStore(t)
	ctx := context.Background()
	register(t, store, "zid_p", "P")
	register(t, store, "zid_q", "Q")
	if current, err := store.CurrentCall(ctx, "zid_p"); err != nil || current != nil {
		t.Fatalf("no call expected: %v %+v", err, current)
	}
	invite, err := store.CreateInvite(ctx, "zid_p")
	if err != nil {
		t.Fatal(err)
	}
	created, err := store.RedeemInvite(ctx, "zid_q", invite.Token)
	if err != nil {
		t.Fatal(err)
	}
	current, err := store.CurrentCall(ctx, "zid_p")
	if err != nil || current == nil || current.ID != created.ID {
		t.Fatalf("ringing call must be current: %v %+v", err, current)
	}
	// A ringing call past its deadline reads as expired rather than ringing.
	ref := store.client.Collection(calls).Doc(created.ID)
	if _, err := ref.Update(ctx, []firestore.Update{{Path: "expiresAt", Value: time.Now().UTC().Add(-time.Minute)}}); err != nil {
		t.Fatal(err)
	}
	stale, err := store.GetCall(ctx, "zid_p", created.ID)
	if err != nil || stale.State != "expired" {
		t.Fatalf("expected expired, got %v %+v", err, stale)
	}
	if current, err := store.CurrentCall(ctx, "zid_p"); err != nil || current != nil {
		t.Fatalf("expired call must not be current: %v %+v", err, current)
	}
}
