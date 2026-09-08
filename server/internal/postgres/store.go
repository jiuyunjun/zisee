package postgres

import (
	"bytes"
	"context"
	_ "embed"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"zisee/server/internal/identity"
)

//go:embed schema.sql
var schema string

type Store struct{ pool *pgxpool.Pool }

func Open(ctx context.Context, url string) (*Store, error) {
	config, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, errors.New("invalid database configuration")
	}
	config.MaxConns = 5
	config.MinConns = 0
	config.ConnConfig.ConnectTimeout = 5 * time.Second
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, err
	}
	if err = pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return &Store{pool: pool}, nil
}

func (s *Store) Close() { s.pool.Close() }
func (s *Store) Ping(ctx context.Context) error {
	// Readiness requires the schema, not merely a reachable database server.
	_, err := s.pool.Exec(ctx, `SELECT i.id,d.id,c.id,s.token_hash FROM identities i
        JOIN devices d ON false JOIN auth_challenges c ON false JOIN sessions s ON false LIMIT 0`)
	return err
}

func (s *Store) Migrate(ctx context.Context) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	// Serialize concurrent migration jobs. Runtime startup never performs DDL.
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(1042746204)`); err != nil {
		return err
	}
	if _, err = tx.Exec(ctx, schema); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func dbError(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return identity.ErrUnauthorized
	}
	var pg *pgconn.PgError
	if errors.As(err, &pg) && pg.Code == "23505" {
		return identity.ErrConflict
	}
	return err
}

func scanIdentity(row pgx.Row) (identity.Identity, error) {
	var value identity.Identity
	err := row.Scan(&value.ID, &value.DisplayName, &value.CreatedAt, &value.UpdatedAt)
	return value, dbError(err)
}

func (s *Store) Register(ctx context.Context, value identity.Identity, device identity.Device) (identity.Identity, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return identity.Identity{}, err
	}
	defer tx.Rollback(ctx)
	tag, err := tx.Exec(ctx, `INSERT INTO identities(id,display_name,created_at,updated_at)
        VALUES($1,$2,$3,$4) ON CONFLICT(id) DO NOTHING`, value.ID, value.DisplayName, value.CreatedAt, value.UpdatedAt)
	if err != nil {
		return identity.Identity{}, dbError(err)
	}
	if tag.RowsAffected() == 0 {
		// An identical signed retry is idempotent, but cannot rename or attach another device.
		var oldID string
		var oldKey []byte
		err = tx.QueryRow(ctx, `SELECT id, public_key FROM devices WHERE identity_id=$1 AND revoked_at IS NULL`, value.ID).Scan(&oldID, &oldKey)
		if errors.Is(err, pgx.ErrNoRows) {
			return identity.Identity{}, identity.ErrConflict
		}
		if err != nil {
			return identity.Identity{}, err
		}
		if oldID != device.ID || !bytes.Equal(oldKey, device.PublicKey) {
			return identity.Identity{}, identity.ErrConflict
		}
	} else {
		if _, err = tx.Exec(ctx, `INSERT INTO devices(id,identity_id,public_key) VALUES($1,$2,$3)`, device.ID, value.ID, device.PublicKey); err != nil {
			return identity.Identity{}, dbError(err)
		}
	}
	result, err := scanIdentity(tx.QueryRow(ctx, `SELECT id,display_name,created_at,updated_at FROM identities WHERE id=$1`, value.ID))
	if err != nil {
		return result, err
	}
	return result, tx.Commit(ctx)
}

func (s *Store) Device(ctx context.Context, id string) (identity.Device, error) {
	var device identity.Device
	err := s.pool.QueryRow(ctx, `SELECT id,identity_id,public_key FROM devices WHERE id=$1 AND revoked_at IS NULL`, id).
		Scan(&device.ID, &device.IdentityID, &device.PublicKey)
	return device, dbError(err)
}

func (s *Store) PutChallenge(ctx context.Context, c identity.Challenge, now time.Time) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var id string
	// Bound outstanding challenges per device, serialized across Cloud Run instances.
	if err = tx.QueryRow(ctx, `SELECT id FROM devices WHERE id=$1 AND revoked_at IS NULL FOR UPDATE`, c.DeviceID).Scan(&id); err != nil {
		return dbError(err)
	}
	if _, err = tx.Exec(ctx, `DELETE FROM auth_challenges WHERE device_id=$1 AND expires_at <= $2`, id, now); err != nil {
		return err
	}
	var count int
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM auth_challenges WHERE device_id=$1`, id).Scan(&count); err != nil {
		return err
	}
	if count >= 5 {
		return identity.ErrRateLimited
	}
	if _, err = tx.Exec(ctx, `INSERT INTO auth_challenges(id,device_id,nonce,expires_at) VALUES($1,$2,$3,$4)`, c.ID, id, c.Nonce, c.ExpiresAt); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Store) Challenge(ctx context.Context, id string, now time.Time) (identity.Challenge, error) {
	var c identity.Challenge
	err := s.pool.QueryRow(ctx, `SELECT id,device_id,nonce,expires_at FROM auth_challenges WHERE id=$1 AND expires_at > $2`, id, now).
		Scan(&c.ID, &c.DeviceID, &c.Nonce, &c.ExpiresAt)
	return c, dbError(err)
}

func (s *Store) Redeem(ctx context.Context, c identity.Challenge, session identity.Session, now time.Time) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var id string
	if err = tx.QueryRow(ctx, `SELECT id FROM devices WHERE id=$1 AND revoked_at IS NULL FOR UPDATE`, session.DeviceID).Scan(&id); err != nil {
		return dbError(err)
	}
	tag, err := tx.Exec(ctx, `DELETE FROM auth_challenges WHERE id=$1 AND device_id=$2 AND nonce=$3 AND expires_at > $4`, c.ID, id, c.Nonce, now)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return identity.ErrUnauthorized
	}
	// One active token per device. Reauthentication revokes the previous token.
	if _, err = tx.Exec(ctx, `DELETE FROM sessions WHERE device_id=$1`, id); err != nil {
		return err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO sessions(token_hash,device_id,expires_at) VALUES($1,$2,$3)`, session.TokenHash, id, session.ExpiresAt); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Store) Session(ctx context.Context, hash []byte, now time.Time) (identity.Session, error) {
	result := identity.Session{TokenHash: hash}
	err := s.pool.QueryRow(ctx, `SELECT d.identity_id,d.id,s.expires_at FROM sessions s
        JOIN devices d ON d.id=s.device_id WHERE s.token_hash=$1 AND s.expires_at > $2 AND d.revoked_at IS NULL`, hash, now).
		Scan(&result.IdentityID, &result.DeviceID, &result.ExpiresAt)
	return result, dbError(err)
}

func (s *Store) Identity(ctx context.Context, id string) (identity.Identity, error) {
	return scanIdentity(s.pool.QueryRow(ctx, `SELECT id,display_name,created_at,updated_at FROM identities WHERE id=$1`, id))
}

func (s *Store) Rename(ctx context.Context, id, name string, now time.Time) (identity.Identity, error) {
	return scanIdentity(s.pool.QueryRow(ctx, `UPDATE identities SET display_name=$2,updated_at=$3 WHERE id=$1
        RETURNING id,display_name,created_at,updated_at`, id, name, now))
}

func (s *Store) DeleteSession(ctx context.Context, hash []byte) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM sessions WHERE token_hash=$1`, hash)
	return err
}

func (s *Store) Cleanup(ctx context.Context, now time.Time) error {
	if _, err := s.pool.Exec(ctx, `DELETE FROM auth_challenges WHERE expires_at <= $1`, now); err != nil {
		return err
	}
	_, err := s.pool.Exec(ctx, `DELETE FROM sessions WHERE expires_at <= $1`, now)
	return err
}
