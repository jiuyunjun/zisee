package postgres

import (
	"context"

	"zisee/server/internal/identity"
	"zisee/server/internal/push"
)

// SetPushToken records the FCM token for the caller's own device. The identity
// and revoked_at guards make a stolen or stale session unable to point another
// identity's wake-up at an attacker device.
func (s *Store) SetPushToken(ctx context.Context, identityID, deviceID, provider, token string) error {
	tag, err := s.pool.Exec(ctx, `UPDATE devices
 SET push_provider=$3,push_token=$4,push_token_updated_at=clock_timestamp()
 WHERE id=$2 AND identity_id=$1 AND revoked_at IS NULL`, identityID, deviceID, provider, token)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return identity.ErrUnauthorized
	}
	return nil
}

func (s *Store) PushTargets(ctx context.Context, identityID string) ([]push.Target, error) {
	rows, err := s.pool.Query(ctx, `SELECT id,push_provider,push_token FROM devices
 WHERE identity_id=$1 AND push_token IS NOT NULL AND push_provider IS NOT NULL AND revoked_at IS NULL`, identityID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	targets := []push.Target{}
	for rows.Next() {
		var t push.Target
		if err = rows.Scan(&t.DeviceID, &t.Provider, &t.Token); err != nil {
			return nil, err
		}
		targets = append(targets, t)
	}
	return targets, rows.Err()
}

func (s *Store) ClearPushToken(ctx context.Context, deviceID string) error {
	_, err := s.pool.Exec(ctx, `UPDATE devices SET push_provider=NULL,push_token=NULL,push_token_updated_at=clock_timestamp() WHERE id=$1`, deviceID)
	return err
}
