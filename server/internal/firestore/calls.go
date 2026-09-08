package firestore

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"time"

	"cloud.google.com/go/firestore"
	"zisee/server/internal/call"
)

const (
	inviteTTL   = 10 * time.Minute
	ringingTTL  = 60 * time.Second
	acceptedTTL = time.Hour
)

func randomCallToken() string {
	var b [32]byte
	// Go 1.26 crypto/rand.Read fills the buffer or terminates on entropy failure.
	_, _ = rand.Read(b[:])
	return base64.RawURLEncoding.EncodeToString(b[:])
}

type inviteDoc struct {
	CreatorID  string    `firestore:"creatorId"`
	TokenHash  string    `firestore:"tokenHash"`
	ExpiresAt  time.Time `firestore:"expiresAt"`
	RedeemedBy string    `firestore:"redeemedBy"`
	CallID     string    `firestore:"callId"`
}

// inviteKeyDoc maps a token hash back to its creator. Keying invitations by
// creator is what replaces the former creator_id UNIQUE constraint.
type inviteKeyDoc struct {
	CreatorID string    `firestore:"creatorId"`
	ExpiresAt time.Time `firestore:"expiresAt"`
}

type callDoc struct {
	MediaGeneration int       `firestore:"mediaGeneration"`
	MediaStartedAt  time.Time `firestore:"mediaStartedAt"`
	CallerID        string    `firestore:"callerId"`
	CalleeID        string    `firestore:"calleeId"`
	// Participants exists so one array-contains query can find either side.
	Participants []string  `firestore:"participants"`
	State        string    `firestore:"state"`
	ExpiresAt    time.Time `firestore:"expiresAt"`
}

func (d callDoc) toCall(id string, now time.Time) call.Call {
	state := d.State
	if !d.ExpiresAt.After(now) && (state == "ringing" || state == "accepted") {
		state = "expired"
	}
	return call.Call{ID: id, CallerID: d.CallerID, CalleeID: d.CalleeID, State: state, ExpiresAt: d.ExpiresAt}
}

func (s *Store) CreateInvite(ctx context.Context, actor string) (call.Invite, error) {
	token := randomCallToken()
	sum := sha256.Sum256([]byte(token))
	hash := key(sum[:])
	now := time.Now().UTC()
	result := call.Invite{Token: token, ExpiresAt: now.Add(inviteTTL)}
	err := s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		ref := s.client.Collection(invites).Doc(actor)
		// Replacing an invitation revokes the previous token, so its lookup
		// document has to go in the same transaction.
		previous, err := tx.Get(ref)
		if err != nil && !notFound(err) {
			return err
		}
		if err == nil {
			var stored inviteDoc
			if err := previous.DataTo(&stored); err != nil {
				return err
			}
			if stored.TokenHash != "" && stored.TokenHash != hash {
				if err := tx.Delete(s.client.Collection(inviteKeys).Doc(stored.TokenHash)); err != nil {
					return err
				}
			}
		}
		if err := tx.Set(ref, inviteDoc{CreatorID: actor, TokenHash: hash, ExpiresAt: result.ExpiresAt}); err != nil {
			return err
		}
		return tx.Set(s.client.Collection(inviteKeys).Doc(hash),
			inviteKeyDoc{CreatorID: actor, ExpiresAt: result.ExpiresAt})
	})
	if err != nil {
		return call.Invite{}, err
	}
	return result, nil
}

func (s *Store) RedeemInvite(ctx context.Context, actor, token string) (call.Call, error) {
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(raw) != 32 || base64.RawURLEncoding.EncodeToString(raw) != token {
		return call.Call{}, call.ErrInvite
	}
	sum := sha256.Sum256([]byte(token))
	hash := key(sum[:])
	now := time.Now().UTC()
	var result call.Call
	err = s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		lookup, err := tx.Get(s.client.Collection(inviteKeys).Doc(hash))
		if notFound(err) {
			return call.ErrInvite
		}
		if err != nil {
			return err
		}
		var pointer inviteKeyDoc
		if err := pointer.decode(lookup); err != nil {
			return err
		}
		inviteRef := s.client.Collection(invites).Doc(pointer.CreatorID)
		snapshot, err := tx.Get(inviteRef)
		if notFound(err) {
			return call.ErrInvite
		}
		if err != nil {
			return err
		}
		var stored inviteDoc
		if err := snapshot.DataTo(&stored); err != nil {
			return err
		}
		// The pointer may outlive a replaced invitation; the invitation wins.
		if stored.TokenHash != hash || !stored.ExpiresAt.After(now) || stored.CreatorID == actor {
			return call.ErrInvite
		}
		if stored.RedeemedBy != "" {
			// A repeated redemption by the same device returns the same call.
			if stored.RedeemedBy != actor || stored.CallID == "" {
				return call.ErrInvite
			}
			existing, err := tx.Get(s.client.Collection(calls).Doc(stored.CallID))
			if notFound(err) {
				return call.ErrNotFound
			}
			if err != nil {
				return err
			}
			var storedCall callDoc
			if err := storedCall.decode(existing); err != nil {
				return err
			}
			result = storedCall.toCall(stored.CallID, now)
			return nil
		}
		// Neither participant may already be in a live call.
		busy, err := s.anyLiveCall(ctx, tx, []string{actor, stored.CreatorID}, now)
		if err != nil {
			return err
		}
		if busy {
			return call.ErrBusy
		}
		if actor == stored.CreatorID {
			return call.ErrInvite
		}
		// The redeemer calls the invitation creator; only the creator may accept.
		id := randomCallToken()
		document := callDoc{CallerID: actor, CalleeID: stored.CreatorID,
			Participants: []string{actor, stored.CreatorID}, State: "ringing", ExpiresAt: now.Add(ringingTTL)}
		if err := tx.Set(s.client.Collection(calls).Doc(id), document); err != nil {
			return err
		}
		stored.RedeemedBy, stored.CallID = actor, id
		if err := tx.Set(inviteRef, stored); err != nil {
			return err
		}
		result = document.toCall(id, now)
		return nil
	})
	if err != nil {
		return call.Call{}, err
	}
	return result, nil
}

func (d *inviteKeyDoc) decode(snapshot *firestore.DocumentSnapshot) error { return snapshot.DataTo(d) }
func (d *callDoc) decode(snapshot *firestore.DocumentSnapshot) error      { return snapshot.DataTo(d) }

// anyLiveCall replaces the Postgres busy check. Reading inside the transaction
// is what stops two invitations from racing into overlapping calls.
func (s *Store) anyLiveCall(ctx context.Context, tx *firestore.Transaction, actors []string, now time.Time) (bool, error) {
	for _, actor := range actors {
		query := s.client.Collection(calls).
			Where("participants", "array-contains", actor).
			Where("expiresAt", ">", now)
		documents, err := tx.Documents(query).GetAll()
		if err != nil {
			return false, err
		}
		for _, document := range documents {
			var stored callDoc
			if err := stored.decode(document); err != nil {
				return false, err
			}
			if stored.State == "ringing" || stored.State == "accepted" {
				return true, nil
			}
		}
	}
	return false, nil
}

func (s *Store) GetCall(ctx context.Context, actor, id string) (call.Call, error) {
	now := time.Now().UTC()
	snapshot, err := s.client.Collection(calls).Doc(id).Get(ctx)
	if notFound(err) {
		return call.Call{}, call.ErrNotFound
	}
	if err != nil {
		return call.Call{}, err
	}
	var stored callDoc
	if err := stored.decode(snapshot); err != nil {
		return call.Call{}, err
	}
	if stored.CallerID != actor && stored.CalleeID != actor {
		// Non-participants must not learn that the call exists.
		return call.Call{}, call.ErrNotFound
	}
	return stored.toCall(id, now), nil
}

func (s *Store) CurrentCall(ctx context.Context, actor string) (*call.Call, error) {
	now := time.Now().UTC()
	documents, err := s.client.Collection(calls).
		Where("participants", "array-contains", actor).
		Where("expiresAt", ">", now).Documents(ctx).GetAll()
	if err != nil {
		return nil, err
	}
	for _, document := range documents {
		var stored callDoc
		if err := stored.decode(document); err != nil {
			return nil, err
		}
		if stored.State == "ringing" || stored.State == "accepted" {
			result := stored.toCall(document.Ref.ID, now)
			return &result, nil
		}
	}
	return nil, nil
}

func (s *Store) ActOnCall(ctx context.Context, actor, id, action string) (call.Call, error) {
	now := time.Now().UTC()
	var result call.Call
	err := s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		ref := s.client.Collection(calls).Doc(id)
		snapshot, err := tx.Get(ref)
		if notFound(err) {
			return call.ErrNotFound
		}
		if err != nil {
			return err
		}
		var stored callDoc
		if err := stored.decode(snapshot); err != nil {
			return err
		}
		if stored.CallerID != actor && stored.CalleeID != actor {
			return call.ErrNotFound
		}
		current := stored.toCall(id, now)
		target := ""
		switch action {
		case "accept":
			if actor == current.CalleeID && (current.State == "ringing" || current.State == "accepted") {
				target = "accepted"
			}
		case "reject":
			if actor == current.CalleeID && (current.State == "ringing" || current.State == "rejected") {
				target = "rejected"
			}
		case "end":
			if current.State == "ringing" || current.State == "accepted" || current.State == "ended" {
				target = "ended"
			}
		}
		if target == "" {
			return call.ErrTransition
		}
		if current.State == target {
			result = current
			return nil
		}
		// Descriptions are read before any write: Firestore forbids the reverse.
		var obsolete []*firestore.DocumentRef
		if target == "ended" || target == "rejected" {
			documents, err := tx.Documents(s.client.Collection(media).Where("callId", "==", id)).GetAll()
			if err != nil {
				return err
			}
			for _, document := range documents {
				obsolete = append(obsolete, document.Ref)
			}
		}
		stored.State = target
		if target == "accepted" {
			stored.ExpiresAt = now.Add(acceptedTTL)
			for _, pair := range [][2]string{{stored.CallerID, stored.CalleeID}, {stored.CalleeID, stored.CallerID}} {
				if err := tx.Set(s.contactRef(pair[0], pair[1]), map[string]any{"createdAt": now}); err != nil {
					return err
				}
			}
		}
		if err := tx.Set(ref, stored); err != nil {
			return err
		}
		// No cascade in Firestore: a finished call drops its descriptions here.
		for _, doomed := range obsolete {
			if err := tx.Delete(doomed); err != nil {
				return err
			}
		}
		result = stored.toCall(id, now)
		return nil
	})
	if err != nil {
		return call.Call{}, err
	}
	return result, nil
}
