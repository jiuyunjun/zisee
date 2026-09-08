package postgres

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"

	"github.com/jackc/pgx/v5"
	"zisee/server/internal/call"
)

func randomCallToken() string {
	var b [32]byte
	// Go 1.26 crypto/rand.Read fills the buffer or terminates on entropy failure.
	_, _ = rand.Read(b[:])
	return base64.RawURLEncoding.EncodeToString(b[:])
}

const callColumns = `id,caller_id,callee_id,
 CASE WHEN expires_at<=clock_timestamp() AND state IN ('ringing','accepted') THEN 'expired' ELSE state END,expires_at`

func scanCall(row pgx.Row) (call.Call, error) {
	var c call.Call
	err := row.Scan(&c.ID, &c.CallerID, &c.CalleeID, &c.State, &c.ExpiresAt)
	if errors.Is(err, pgx.ErrNoRows) {
		err = call.ErrNotFound
	}
	return c, err
}

func (s *Store) CreateInvite(ctx context.Context, actor string) (call.Invite, error) {
	token := randomCallToken()
	hash := sha256.Sum256([]byte(token))
	result := call.Invite{Token: token}
	// Replacing an invitation revokes its previous token. At most one row per identity.
	err := s.pool.QueryRow(ctx, `INSERT INTO call_invites(token_hash,creator_id,expires_at)
 VALUES($1,$2,clock_timestamp()+interval '10 minutes')
 ON CONFLICT(creator_id) DO UPDATE SET token_hash=EXCLUDED.token_hash,
 expires_at=EXCLUDED.expires_at,redeemed_by=NULL,call_id=NULL RETURNING expires_at`, hash[:], actor).Scan(&result.ExpiresAt)
	return result, err
}

func (s *Store) RedeemInvite(ctx context.Context, actor, token string) (call.Call, error) {
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(raw) != 32 || base64.RawURLEncoding.EncodeToString(raw) != token {
		return call.Call{}, call.ErrInvite
	}
	hash := sha256.Sum256([]byte(token))
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return call.Call{}, err
	}
	defer tx.Rollback(ctx)
	var creator string
	var redeemed, existing *string
	err = tx.QueryRow(ctx, `SELECT creator_id,redeemed_by,call_id FROM call_invites
 WHERE token_hash=$1 AND expires_at>clock_timestamp() FOR UPDATE`, hash[:]).Scan(&creator, &redeemed, &existing)
	if errors.Is(err, pgx.ErrNoRows) {
		return call.Call{}, call.ErrInvite
	}
	if err != nil {
		return call.Call{}, err
	}
	if creator == actor {
		return call.Call{}, call.ErrInvite
	}
	if redeemed != nil {
		if *redeemed != actor || existing == nil {
			return call.Call{}, call.ErrInvite
		}
		return scanCall(tx.QueryRow(ctx, `SELECT `+callColumns+` FROM calls WHERE id=$1`, *existing))
	}
	// Lock both identities in a consistent order across all instances and invitations.
	rows, err := tx.Query(ctx, `SELECT id FROM identities WHERE id=$1 OR id=$2 ORDER BY id FOR UPDATE`, actor, creator)
	if err != nil {
		return call.Call{}, err
	}
	for rows.Next() {
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return call.Call{}, err
	}
	var busy bool
	err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM calls WHERE
 (caller_id IN ($1,$2) OR callee_id IN ($1,$2)) AND state IN ('ringing','accepted') AND expires_at>clock_timestamp())`, actor, creator).Scan(&busy)
	if err != nil {
		return call.Call{}, err
	}
	if busy {
		return call.Call{}, call.ErrBusy
	}
	// The redeemer calls the invitation creator; only the creator may accept.
	c, err := scanCall(tx.QueryRow(ctx, `INSERT INTO calls(id,caller_id,callee_id,state,expires_at)
 VALUES($1,$2,$3,'ringing',clock_timestamp()+interval '60 seconds') RETURNING `+callColumns, randomCallToken(), actor, creator))
	if err != nil {
		return call.Call{}, err
	}
	if _, err = tx.Exec(ctx, `UPDATE call_invites SET redeemed_by=$2,call_id=$3 WHERE token_hash=$1`, hash[:], actor, c.ID); err != nil {
		return call.Call{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return call.Call{}, err
	}
	return c, nil
}

func (s *Store) GetCall(ctx context.Context, actor, id string) (call.Call, error) {
	return scanCall(s.pool.QueryRow(ctx, `SELECT `+callColumns+` FROM calls WHERE id=$1 AND (caller_id=$2 OR callee_id=$2)`, id, actor))
}

func (s *Store) CurrentCall(ctx context.Context, actor string) (*call.Call, error) {
	c, err := scanCall(s.pool.QueryRow(ctx, `SELECT `+callColumns+` FROM calls WHERE
 (caller_id=$1 OR callee_id=$1) AND state IN ('ringing','accepted') AND expires_at>clock_timestamp() LIMIT 1`, actor))
	if errors.Is(err, call.ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

func (s *Store) ActOnCall(ctx context.Context, actor, id, action string) (call.Call, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return call.Call{}, err
	}
	defer tx.Rollback(ctx)
	participants, err := scanCall(tx.QueryRow(ctx, `SELECT `+callColumns+` FROM calls WHERE id=$1 AND (caller_id=$2 OR callee_id=$2)`, id, actor))
	if err != nil {
		return call.Call{}, err
	}
	// Serialize acceptance/deadline extension with new calls involving either participant.
	// Re-read the call after acquiring identity locks so expiration is checked again.
	rows, err := tx.Query(ctx, `SELECT id FROM identities WHERE id=$1 OR id=$2 ORDER BY id FOR UPDATE`, participants.CallerID, participants.CalleeID)
	if err != nil {
		return call.Call{}, err
	}
	for rows.Next() {
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return call.Call{}, err
	}
	c, err := scanCall(tx.QueryRow(ctx, `SELECT `+callColumns+` FROM calls WHERE id=$1 AND (caller_id=$2 OR callee_id=$2) FOR UPDATE`, id, actor))
	if err != nil {
		return call.Call{}, err
	}
	target := ""
	switch action {
	case "accept":
		if actor == c.CalleeID && (c.State == "ringing" || c.State == "accepted") {
			target = "accepted"
		}
	case "reject":
		if actor == c.CalleeID && (c.State == "ringing" || c.State == "rejected") {
			target = "rejected"
		}
	case "end":
		if c.State == "ringing" || c.State == "accepted" || c.State == "ended" {
			target = "ended"
		}
	}
	if target == "" {
		return call.Call{}, call.ErrTransition
	}
	if c.State == target {
		return c, nil
	}
	c, err = scanCall(tx.QueryRow(ctx, `UPDATE calls SET state=$2,
 expires_at=CASE WHEN $2='accepted' THEN clock_timestamp()+interval '1 hour' ELSE expires_at END
 WHERE id=$1 RETURNING `+callColumns, id, target))
	if err != nil {
		return call.Call{}, err
	}
	if target == "ended" || target == "rejected" {
		if _, err = tx.Exec(ctx, `DELETE FROM media_descriptions WHERE call_id=$1`, id); err != nil {
			return call.Call{}, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return call.Call{}, err
	}
	return c, nil
}
