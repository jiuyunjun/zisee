package firestore

import (
	"context"
	"time"

	"cloud.google.com/go/firestore"
	"zisee/server/internal/identity"
	"zisee/server/internal/push"
)

func (s *Store) SetPushToken(ctx context.Context, identityID, deviceID, provider, token string) error {
	return s.client.RunTransaction(ctx, func(ctx context.Context, tx *firestore.Transaction) error {
		ref := s.client.Collection(devices).Doc(deviceID)
		snapshot, err := tx.Get(ref)
		if notFound(err) {
			return identity.ErrUnauthorized
		}
		if err != nil {
			return err
		}
		var stored deviceDoc
		if err := snapshot.DataTo(&stored); err != nil {
			return err
		}
		if !stored.active() || stored.IdentityID != identityID {
			return identity.ErrUnauthorized
		}
		return tx.Update(ref, []firestore.Update{
			{Path: "pushProvider", Value: provider},
			{Path: "pushToken", Value: token},
			{Path: "pushTokenUpdatedAt", Value: time.Now().UTC()},
		})
	})
}

func (s *Store) PushTargets(ctx context.Context, identityID string) ([]push.Target, error) {
	documents, err := s.client.Collection(devices).Where("identityId", "==", identityID).Documents(ctx).GetAll()
	if err != nil {
		return nil, err
	}
	targets := []push.Target{}
	for _, document := range documents {
		var stored deviceDoc
		if err := document.DataTo(&stored); err != nil {
			return nil, err
		}
		if !stored.active() || stored.PushToken == "" || stored.PushProvider == "" {
			continue
		}
		targets = append(targets, push.Target{DeviceID: document.Ref.ID, Provider: stored.PushProvider, Token: stored.PushToken})
	}
	return targets, nil
}

func (s *Store) ClearPushToken(ctx context.Context, deviceID string) error {
	_, err := s.client.Collection(devices).Doc(deviceID).Update(ctx, []firestore.Update{
		{Path: "pushProvider", Value: firestore.Delete},
		{Path: "pushToken", Value: firestore.Delete},
		{Path: "pushTokenUpdatedAt", Value: time.Now().UTC()},
	})
	if notFound(err) {
		return nil
	}
	return err
}
