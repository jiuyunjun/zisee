package firestore

import (
	"cloud.google.com/go/firestore"
	"context"
	"time"
	"zisee/server/internal/call"
)

func (s *Store) contactRef(owner, peer string) *firestore.DocumentRef {
	return s.client.Collection("contacts").Doc(owner).Collection("peers").Doc(peer)
}

func (s *Store) ListContacts(ctx context.Context, actor string) ([]call.Contact, error) {
	docs, err := s.client.Collection("contacts").Doc(actor).Collection("peers").Limit(20).Documents(ctx).GetAll()
	if err != nil {
		return nil, err
	}
	result := []call.Contact{}
	for _, d := range docs {
		identity, err := s.client.Collection(identities).Doc(d.Ref.ID).Get(ctx)
		if notFound(err) {
			continue
		}
		if err != nil {
			return nil, err
		}
		var value identityDoc
		if err = identity.DataTo(&value); err != nil {
			return nil, err
		}
		result = append(result, call.Contact{IdentityID: d.Ref.ID, DisplayName: value.DisplayName})
	}
	return result, nil
}

func (s *Store) RemoveContact(ctx context.Context, actor, peer string) error {
	if actor == peer || !validContactID(peer) {
		return call.ErrNotFound
	}
	batch := s.client.Batch()
	batch.Delete(s.contactRef(actor, peer))
	batch.Delete(s.contactRef(peer, actor))
	_, err := batch.Commit(ctx)
	return err
}

func validContactID(id string) bool {
	if len(id) < 5 || len(id) > 128 {
		return false
	}
	for _, r := range id {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_' || r == '-') {
			return false
		}
	}
	return true
}

func (s *Store) CallContact(ctx context.Context, actor, peer string) (call.Call, error) {
	if actor == peer || !validContactID(peer) {
		return call.Call{}, call.ErrNotFound
	}
	now := time.Now().UTC()
	id := randomCallToken()
	var result call.Call
	err := s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		for _, ref := range []*firestore.DocumentRef{s.contactRef(actor, peer), s.contactRef(peer, actor)} {
			_, err := tx.Get(ref)
			if notFound(err) {
				return call.ErrNotFound
			}
			if err != nil {
				return err
			}
		}
		busy, err := s.anyLiveCall(ctx, tx, []string{actor, peer}, now)
		if err != nil {
			return err
		}
		if busy {
			return call.ErrBusy
		}
		value := callDoc{CallerID: actor, CalleeID: peer, Participants: []string{actor, peer}, State: "ringing", ExpiresAt: now.Add(ringingTTL)}
		if err := tx.Set(s.client.Collection(calls).Doc(id), value); err != nil {
			return err
		}
		result = value.toCall(id, now)
		return nil
	})
	return result, err
}
