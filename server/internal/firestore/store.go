// Package firestore implements the identity, call and media stores on Cloud
// Firestore.
//
// Postgres enforced uniqueness, foreign keys, check constraints and cascade
// deletes in the schema. Firestore has none of those, so every invariant is
// re-established here:
//
//   - one invitation per creator: the invitation is keyed by creator id and a
//     separate token document points back to it, both written in one transaction
//   - one device per identity, one session per device: the transaction reads the
//     existing document before writing
//   - caller != callee and the four legal call states: checked in Go
//   - exactly two descriptions per call: the document id is callId:sequence
//   - cascade delete of descriptions: done explicitly when a call ends
//
// Firestore transactions are optimistic and retried, so reading a document
// inside the transaction gives the serialization that FOR UPDATE gave before.
package firestore

import (
	"context"
	"encoding/hex"
	"errors"
	"time"

	"cloud.google.com/go/firestore"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"zisee/server/internal/identity"
)

const (
	identities = "identities"
	devices    = "devices"
	challenges = "authChallenges"
	sessions   = "sessions"
	invites    = "callInvites"
	inviteKeys = "callInviteTokens"
	calls      = "calls"
	media      = "mediaDescriptions"

	maxChallengesPerDevice = 5
)

type Store struct{ client *firestore.Client }

func Open(ctx context.Context, projectID, databaseID string) (*Store, error) {
	if projectID == "" {
		return nil, errors.New("firestore project id is required")
	}
	var client *firestore.Client
	var err error
	if databaseID == "" || databaseID == "(default)" {
		client, err = firestore.NewClient(ctx, projectID)
	} else {
		client, err = firestore.NewClientWithDatabase(ctx, projectID, databaseID)
	}
	if err != nil {
		return nil, err
	}
	return &Store{client: client}, nil
}

func (s *Store) Close() { s.client.Close() }

// Ping proves the backend answers. Firestore has no schema to verify, so a
// bounded read of a reserved document stands in for the Postgres schema probe.
func (s *Store) Ping(ctx context.Context) error {
	_, err := s.client.Collection("healthz").Doc("probe").Get(ctx)
	if status.Code(err) == codes.NotFound {
		return nil
	}
	return err
}

// Migrate exists so the migrate command works against either backend. Firestore
// needs no DDL; composite indexes are declared in firestore.indexes.json.
func (s *Store) Migrate(ctx context.Context) error { return s.Ping(ctx) }

func notFound(err error) bool { return status.Code(err) == codes.NotFound }

func key(hash []byte) string { return hex.EncodeToString(hash) }

type identityDoc struct {
	DisplayName string    `firestore:"displayName"`
	CreatedAt   time.Time `firestore:"createdAt"`
	UpdatedAt   time.Time `firestore:"updatedAt"`
}

type deviceDoc struct {
	IdentityID         string    `firestore:"identityId"`
	PublicKey          []byte    `firestore:"publicKey"`
	RevokedAt          time.Time `firestore:"revokedAt"`
	PushProvider       string    `firestore:"pushProvider,omitempty"`
	PushToken          string    `firestore:"pushToken,omitempty"`
	PushTokenUpdatedAt time.Time `firestore:"pushTokenUpdatedAt,omitempty"`
}

type challengeDoc struct {
	DeviceID  string    `firestore:"deviceId"`
	Nonce     string    `firestore:"nonce"`
	ExpiresAt time.Time `firestore:"expiresAt"`
}

type sessionDoc struct {
	DeviceID  string    `firestore:"deviceId"`
	ExpiresAt time.Time `firestore:"expiresAt"`
}

func (d deviceDoc) active() bool { return d.RevokedAt.IsZero() }

func (s *Store) Register(ctx context.Context, value identity.Identity, device identity.Device) (identity.Identity, error) {
	result := value
	err := s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		identityRef := s.client.Collection(identities).Doc(value.ID)
		snapshot, err := tx.Get(identityRef)
		switch {
		case notFound(err):
			// New identity: it and its first device are created together.
			if err := tx.Set(identityRef, identityDoc{
				DisplayName: value.DisplayName, CreatedAt: value.CreatedAt, UpdatedAt: value.UpdatedAt,
			}); err != nil {
				return err
			}
			result = value
			return tx.Set(s.client.Collection(devices).Doc(device.ID),
				deviceDoc{IdentityID: value.ID, PublicKey: device.PublicKey})
		case err != nil:
			return err
		}
		// Existing identity: an identical signed retry is idempotent, but it can
		// neither rename the identity nor attach a second device.
		existing, err := s.activeDevice(ctx, tx, value.ID)
		if err != nil {
			return err
		}
		if existing.ID != device.ID || !equalKeys(existing.PublicKey, device.PublicKey) {
			return identity.ErrConflict
		}
		var stored identityDoc
		if err := snapshot.DataTo(&stored); err != nil {
			return err
		}
		result = identity.Identity{ID: value.ID, DisplayName: stored.DisplayName,
			CreatedAt: stored.CreatedAt, UpdatedAt: stored.UpdatedAt}
		return nil
	})
	if err != nil {
		return identity.Identity{}, err
	}
	return result, nil
}

// activeDevice enforces the former devices.identity_id UNIQUE constraint.
func (s *Store) activeDevice(ctx context.Context, tx *firestore.Transaction, identityID string) (identity.Device, error) {
	query := s.client.Collection(devices).Where("identityId", "==", identityID).Limit(2)
	documents, err := tx.Documents(query).GetAll()
	if err != nil {
		return identity.Device{}, err
	}
	found := identity.Device{}
	for _, document := range documents {
		var stored deviceDoc
		if err := document.DataTo(&stored); err != nil {
			return identity.Device{}, err
		}
		if !stored.active() {
			continue
		}
		if found.ID != "" {
			// Two live devices means an earlier write broke the invariant.
			return identity.Device{}, identity.ErrConflict
		}
		found = identity.Device{ID: document.Ref.ID, IdentityID: stored.IdentityID, PublicKey: stored.PublicKey}
	}
	if found.ID == "" {
		return identity.Device{}, identity.ErrConflict
	}
	return found, nil
}

func equalKeys(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func (s *Store) Device(ctx context.Context, id string) (identity.Device, error) {
	snapshot, err := s.client.Collection(devices).Doc(id).Get(ctx)
	if notFound(err) {
		return identity.Device{}, identity.ErrUnauthorized
	}
	if err != nil {
		return identity.Device{}, err
	}
	var stored deviceDoc
	if err := snapshot.DataTo(&stored); err != nil {
		return identity.Device{}, err
	}
	if !stored.active() {
		return identity.Device{}, identity.ErrUnauthorized
	}
	return identity.Device{ID: id, IdentityID: stored.IdentityID, PublicKey: stored.PublicKey}, nil
}

func (s *Store) PutChallenge(ctx context.Context, c identity.Challenge, now time.Time) error {
	return s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		device, err := s.client.Collection(devices).Doc(c.DeviceID).Get(ctx)
		if notFound(err) {
			return identity.ErrUnauthorized
		}
		if err != nil {
			return err
		}
		var stored deviceDoc
		if err := stored2(device, &stored); err != nil {
			return err
		}
		if !stored.active() {
			return identity.ErrUnauthorized
		}
		// Bound outstanding challenges per device, dropping the expired ones first.
		query := s.client.Collection(challenges).Where("deviceId", "==", c.DeviceID)
		documents, err := tx.Documents(query).GetAll()
		if err != nil {
			return err
		}
		live := 0
		for _, document := range documents {
			var existing challengeDoc
			if err := document.DataTo(&existing); err != nil {
				return err
			}
			if existing.ExpiresAt.After(now) {
				live++
				continue
			}
			if err := tx.Delete(document.Ref); err != nil {
				return err
			}
		}
		if live >= maxChallengesPerDevice {
			return identity.ErrRateLimited
		}
		return tx.Set(s.client.Collection(challenges).Doc(c.ID),
			challengeDoc{DeviceID: c.DeviceID, Nonce: c.Nonce, ExpiresAt: c.ExpiresAt})
	})
}

func stored2(snapshot *firestore.DocumentSnapshot, target any) error { return snapshot.DataTo(target) }

func (s *Store) Challenge(ctx context.Context, id string, now time.Time) (identity.Challenge, error) {
	snapshot, err := s.client.Collection(challenges).Doc(id).Get(ctx)
	if notFound(err) {
		return identity.Challenge{}, identity.ErrUnauthorized
	}
	if err != nil {
		return identity.Challenge{}, err
	}
	var stored challengeDoc
	if err := snapshot.DataTo(&stored); err != nil {
		return identity.Challenge{}, err
	}
	if !stored.ExpiresAt.After(now) {
		return identity.Challenge{}, identity.ErrUnauthorized
	}
	return identity.Challenge{ID: id, Nonce: stored.Nonce, ExpiresAt: stored.ExpiresAt, DeviceID: stored.DeviceID}, nil
}

func (s *Store) Redeem(ctx context.Context, c identity.Challenge, session identity.Session, now time.Time) error {
	return s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		challengeRef := s.client.Collection(challenges).Doc(c.ID)
		snapshot, err := tx.Get(challengeRef)
		if notFound(err) {
			return identity.ErrUnauthorized
		}
		if err != nil {
			return err
		}
		var stored challengeDoc
		if err := snapshot.DataTo(&stored); err != nil {
			return err
		}
		// The challenge is single use and bound to the device that requested it.
		if stored.DeviceID != session.DeviceID || stored.Nonce != c.Nonce || !stored.ExpiresAt.After(now) {
			return identity.ErrUnauthorized
		}
		device, err := tx.Get(s.client.Collection(devices).Doc(session.DeviceID))
		if notFound(err) {
			return identity.ErrUnauthorized
		}
		if err != nil {
			return err
		}
		var storedDevice deviceDoc
		if err := device.DataTo(&storedDevice); err != nil {
			return err
		}
		if !storedDevice.active() {
			return identity.ErrUnauthorized
		}
		// One active token per device: reauthentication revokes the previous one.
		existing, err := tx.Documents(s.client.Collection(sessions).
			Where("deviceId", "==", session.DeviceID)).GetAll()
		if err != nil {
			return err
		}
		if err := tx.Delete(challengeRef); err != nil {
			return err
		}
		for _, document := range existing {
			if err := tx.Delete(document.Ref); err != nil {
				return err
			}
		}
		return tx.Set(s.client.Collection(sessions).Doc(key(session.TokenHash)),
			sessionDoc{DeviceID: session.DeviceID, ExpiresAt: session.ExpiresAt})
	})
}

func (s *Store) Session(ctx context.Context, hash []byte, now time.Time) (identity.Session, error) {
	snapshot, err := s.client.Collection(sessions).Doc(key(hash)).Get(ctx)
	if notFound(err) {
		return identity.Session{}, identity.ErrUnauthorized
	}
	if err != nil {
		return identity.Session{}, err
	}
	var stored sessionDoc
	if err := snapshot.DataTo(&stored); err != nil {
		return identity.Session{}, err
	}
	if !stored.ExpiresAt.After(now) {
		return identity.Session{}, identity.ErrUnauthorized
	}
	device, err := s.Device(ctx, stored.DeviceID)
	if err != nil {
		return identity.Session{}, err
	}
	return identity.Session{IdentityID: device.IdentityID, DeviceID: device.ID,
		TokenHash: hash, ExpiresAt: stored.ExpiresAt}, nil
}

func (s *Store) Identity(ctx context.Context, id string) (identity.Identity, error) {
	snapshot, err := s.client.Collection(identities).Doc(id).Get(ctx)
	if notFound(err) {
		return identity.Identity{}, identity.ErrUnauthorized
	}
	if err != nil {
		return identity.Identity{}, err
	}
	var stored identityDoc
	if err := snapshot.DataTo(&stored); err != nil {
		return identity.Identity{}, err
	}
	return identity.Identity{ID: id, DisplayName: stored.DisplayName,
		CreatedAt: stored.CreatedAt, UpdatedAt: stored.UpdatedAt}, nil
}

func (s *Store) Rename(ctx context.Context, id, name string, now time.Time) (identity.Identity, error) {
	var result identity.Identity
	err := s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		ref := s.client.Collection(identities).Doc(id)
		snapshot, err := tx.Get(ref)
		if notFound(err) {
			return identity.ErrUnauthorized
		}
		if err != nil {
			return err
		}
		var stored identityDoc
		if err := snapshot.DataTo(&stored); err != nil {
			return err
		}
		stored.DisplayName, stored.UpdatedAt = name, now
		result = identity.Identity{ID: id, DisplayName: name, CreatedAt: stored.CreatedAt, UpdatedAt: now}
		return tx.Set(ref, stored)
	})
	if err != nil {
		return identity.Identity{}, err
	}
	return result, nil
}

func (s *Store) DeleteSession(ctx context.Context, hash []byte) error {
	_, err := s.client.Collection(sessions).Doc(key(hash)).Delete(ctx)
	return err
}

// Cleanup mirrors the Postgres retention windows. Firestore has no cascade, so
// descriptions belonging to removed calls are deleted explicitly.
func (s *Store) Cleanup(ctx context.Context, now time.Time) error {
	batch := s.client.BulkWriter(ctx)
	deletes := 0
	drop := func(query firestore.Query) error {
		documents, err := query.Limit(500).Documents(ctx).GetAll()
		if err != nil {
			return err
		}
		for _, document := range documents {
			if _, err := batch.Delete(document.Ref); err != nil {
				return err
			}
			deletes++
		}
		return nil
	}
	collection := s.client.Collection(media).Where("createdAt", "<", now.Add(-2*time.Minute))
	if err := drop(collection); err != nil {
		return err
	}
	if err := drop(s.client.Collection(invites).Where("expiresAt", "<=", now)); err != nil {
		return err
	}
	if err := drop(s.client.Collection(inviteKeys).Where("expiresAt", "<=", now)); err != nil {
		return err
	}
	if err := drop(s.client.Collection(challenges).Where("expiresAt", "<=", now)); err != nil {
		return err
	}
	if err := drop(s.client.Collection(sessions).Where("expiresAt", "<=", now)); err != nil {
		return err
	}
	// Calls outlive their deadline by a day so a finished call can still be read.
	stale, err := s.client.Collection(calls).Where("expiresAt", "<", now.Add(-24*time.Hour)).
		Limit(500).Documents(ctx).GetAll()
	if err != nil {
		return err
	}
	for _, document := range stale {
		descriptions, err := s.client.Collection(media).Where("callId", "==", document.Ref.ID).Documents(ctx).GetAll()
		if err != nil {
			return err
		}
		for _, description := range descriptions {
			if _, err := batch.Delete(description.Ref); err != nil {
				return err
			}
		}
		if _, err := batch.Delete(document.Ref); err != nil {
			return err
		}
		deletes++
	}
	batch.End()
	return nil
}
