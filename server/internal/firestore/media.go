package firestore

import (
	"context"
	"fmt"
	"strings"
	"time"

	"cloud.google.com/go/firestore"
	"zisee/server/internal/call"
	"zisee/server/internal/identity"
)

// descriptionWindow matches the Postgres retention: a description is delivered
// and kept only briefly after the initial negotiation.
const descriptionWindow = 2 * time.Minute

type descriptionDoc struct {
	Candidates []call.Candidate `firestore:"candidates"`
	CallID     string           `firestore:"callId"`
	Sequence   int              `firestore:"sequence"`
	MessageID  string           `firestore:"messageId"`
	SDP        string           `firestore:"sdp"`
	CreatedAt  time.Time        `firestore:"createdAt"`
}

// descriptionID encodes the former PRIMARY KEY (call_id, sequence), which is
// what limits a call to one offer and one answer.
func descriptionID(callID string, sequence int) string { return fmt.Sprintf("%s:%d", callID, sequence) }

func (s *Store) SendDescription(ctx context.Context, actor, id, messageID, kind, sdp string, generation ...int) (int, error) {
	if len(messageID) < 1 || len(messageID) > 64 || len(sdp) > 49152 || !strings.HasPrefix(sdp, "v=0\r\n") || strings.ContainsRune(sdp, 0) {
		return 0, identity.ErrInvalid
	}
	sequence := 0
	switch kind {
	case "offer":
		sequence = 1
	case "answer":
		sequence = 2
	default:
		return 0, identity.ErrInvalid
	}
	now := time.Now().UTC()
	err := s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		snapshot, err := tx.Get(s.client.Collection(calls).Doc(id))
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
		// Only the caller offers and only the callee answers, and only once the
		// call is accepted.
		if current.State != "accepted" || (sequence == 1 && current.CallerID != actor) || (sequence == 2 && current.CalleeID != actor) {
			return call.ErrTransition
		}
		if call.Generation(generation) != stored.MediaGeneration {
			return call.ErrGeneration
		}
		ref := s.client.Collection(media).Doc(generationDescriptionID(id, sequence, stored.MediaGeneration))
		existing, err := tx.Get(ref)
		if err != nil && !notFound(err) {
			return err
		}
		if err == nil {
			// A retry of the same message is idempotent; anything else is a
			// second negotiation, which this version does not support.
			var previous descriptionDoc
			if err := previous.decode(existing); err != nil {
				return err
			}
			if previous.MessageID != messageID || previous.SDP != sdp {
				return call.ErrTransition
			}
			return nil
		}
		// Refuse a first description once the delivery window has passed.
		started := stored.MediaStartedAt
		if started.IsZero() {
			started = current.ExpiresAt.Add(-acceptedTTL)
		}
		if !started.After(now.Add(-descriptionWindow)) {
			return call.ErrTransition
		}
		if sequence == 2 {
			offer, err := tx.Get(s.client.Collection(media).Doc(generationDescriptionID(id, 1, stored.MediaGeneration)))
			if notFound(err) {
				return call.ErrTransition
			}
			if err != nil {
				return err
			}
			_ = offer
		}
		return tx.Set(ref, descriptionDoc{CallID: id, Sequence: sequence,
			MessageID: messageID, SDP: sdp, CreatedAt: now})
	})
	if err != nil {
		return 0, err
	}
	return sequence, nil
}

func (d *descriptionDoc) decode(snapshot *firestore.DocumentSnapshot) error {
	return snapshot.DataTo(d)
}

func (s *Store) SyncDescriptions(ctx context.Context, actor, id string, after int) (call.MediaSnapshot, error) {
	if after < 0 || after > 2 {
		return call.MediaSnapshot{}, identity.ErrInvalid
	}
	now := time.Now().UTC()
	snapshot, err := s.client.Collection(calls).Doc(id).Get(ctx)
	if notFound(err) {
		return call.MediaSnapshot{}, call.ErrNotFound
	}
	if err != nil {
		return call.MediaSnapshot{}, err
	}
	var stored callDoc
	if err := stored.decode(snapshot); err != nil {
		return call.MediaSnapshot{}, err
	}
	if stored.CallerID != actor && stored.CalleeID != actor {
		return call.MediaSnapshot{}, call.ErrNotFound
	}
	current := stored.toCall(id, now)
	result := call.MediaSnapshot{Generation: stored.MediaGeneration, Call: current, Descriptions: []call.Description{}}
	if current.State != "accepted" {
		return result, nil
	}
	// Each participant receives only the peer's description.
	peerSequence := 1
	if actor == current.CallerID {
		peerSequence = 2
	}

	document, err := s.client.Collection(media).Doc(generationDescriptionID(id, peerSequence, stored.MediaGeneration)).Get(ctx)
	if notFound(err) {
		return result, nil
	}
	if err != nil {
		return result, err
	}
	var description descriptionDoc
	if err := description.decode(document); err != nil {
		return result, err
	}
	if !description.CreatedAt.After(now.Add(-descriptionWindow)) {
		return result, nil
	}
	kind := "offer"
	if description.Sequence == 2 {
		kind = "answer"
	}
	result.Candidates = description.Candidates
	if peerSequence > after {
		result.Descriptions = append(result.Descriptions, call.Description{
			Sequence: description.Sequence, Type: kind, SDP: description.SDP})
		result.Candidates = nil // Deliver candidates after the SDP cursor acknowledges this description.
	}
	return result, nil
}

func (s *Store) SendCandidates(ctx context.Context, actor, id string, next []call.Candidate, generation ...int) error {
	if err := call.ValidateCandidates(next, nil); err != nil {
		return err
	}
	return s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		snapshot, err := tx.Get(s.client.Collection(calls).Doc(id))
		if notFound(err) {
			return call.ErrNotFound
		}
		if err != nil {
			return err
		}
		var stored callDoc
		if err = stored.decode(snapshot); err != nil {
			return err
		}
		if stored.CallerID != actor && stored.CalleeID != actor {
			return call.ErrNotFound
		}
		if stored.toCall(id, time.Now().UTC()).State != "accepted" {
			return call.ErrTransition
		}
		seq := 1
		if actor == stored.CalleeID {
			seq = 2
		}
		if call.Generation(generation) != stored.MediaGeneration {
			return call.ErrGeneration
		}
		ref := s.client.Collection(media).Doc(generationDescriptionID(id, seq, stored.MediaGeneration))
		snapshot, err = tx.Get(ref)
		if notFound(err) {
			return call.ErrTransition
		}
		if err != nil {
			return err
		}
		var d descriptionDoc
		if err = d.decode(snapshot); err != nil {
			return err
		}
		if !d.CreatedAt.After(time.Now().Add(-descriptionWindow)) {
			return call.ErrTransition
		}
		if err = call.ValidateCandidates(next, d.Candidates); err != nil {
			return err
		}
		if len(next) <= len(d.Candidates) {
			return nil
		}
		return tx.Update(ref, []firestore.Update{{Path: "candidates", Value: next}})
	})
}

func generationDescriptionID(id string, seq, generation int) string {
	if generation == 0 {
		return descriptionID(id, seq)
	}
	return fmt.Sprintf("%s:g%d:%d", id, generation, seq)
}
func (s *Store) RestartMedia(ctx context.Context, actor, id string, expected int) (int, error) {
	if expected < 0 || expected >= call.MaxMediaGeneration {
		return 0, identity.ErrInvalid
	}
	result := 0
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
		if err = stored.decode(snapshot); err != nil {
			return err
		}
		if stored.CallerID != actor && stored.CalleeID != actor {
			return call.ErrNotFound
		}
		now := time.Now().UTC()
		if stored.toCall(id, now).State != "accepted" {
			return call.ErrTransition
		}
		result = stored.MediaGeneration
		if expected > result {
			return call.ErrGeneration
		}
		started := stored.MediaStartedAt
		if started.IsZero() {
			started = stored.ExpiresAt.Add(-acceptedTTL)
		}
		if expected < result || now.Sub(started) < 5*time.Second {
			return nil
		}
		result++
		return tx.Update(ref, []firestore.Update{{Path: "mediaGeneration", Value: result}, {Path: "mediaStartedAt", Value: now}})
	})
	return result, err
}
