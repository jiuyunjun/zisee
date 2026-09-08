package postgres

import (
	"context"
	"errors"
	"strings"

	"github.com/jackc/pgx/v5"
	"zisee/server/internal/call"
	"zisee/server/internal/identity"
)

func (s *Store) SendDescription(ctx context.Context, actor, id, messageID, kind, sdp string) (int, error) {
	if len(messageID) < 1 || len(messageID) > 64 || len(sdp) > 49152 || !strings.HasPrefix(sdp, "v=0\r\n") || strings.ContainsRune(sdp, 0) {
		return 0, identity.ErrInvalid
	}
	seq := 0
	switch kind {
	case "offer":
		seq = 1
	case "answer":
		seq = 2
	default:
		return 0, identity.ErrInvalid
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	c, err := scanCall(tx.QueryRow(ctx, `SELECT `+callColumns+` FROM calls WHERE id=$1 AND (caller_id=$2 OR callee_id=$2) FOR UPDATE`, id, actor))
	if err != nil {
		return 0, err
	}
	if c.State != "accepted" || (seq == 1 && c.CallerID != actor) || (seq == 2 && c.CalleeID != actor) {
		return 0, call.ErrTransition
	}
	var oldID, oldSDP string
	err = tx.QueryRow(ctx, `SELECT message_id,sdp FROM media_descriptions WHERE call_id=$1 AND sequence=$2`, id, seq).Scan(&oldID, &oldSDP)
	if err == nil {
		if oldID != messageID || oldSDP != sdp {
			return 0, call.ErrTransition
		}
		return seq, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return 0, err
	}
	// One initial negotiation only. Bound retained SDP and refuse restart after its delivery window.
	var allowed bool
	err = tx.QueryRow(ctx, `SELECT expires_at > clock_timestamp()+interval '58 minutes' FROM calls WHERE id=$1`, id).Scan(&allowed)
	if err != nil {
		return 0, err
	}
	if !allowed {
		return 0, call.ErrTransition
	}
	if seq == 2 {
		var offerExists bool
		if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM media_descriptions WHERE call_id=$1 AND sequence=1)`, id).Scan(&offerExists); err != nil {
			return 0, err
		}
		if !offerExists {
			return 0, call.ErrTransition
		}
	}
	if _, err = tx.Exec(ctx, `INSERT INTO media_descriptions(call_id,sequence,message_id,sdp) VALUES($1,$2,$3,$4)`, id, seq, messageID, sdp); err != nil {
		return 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return 0, err
	}
	return seq, nil
}

func (s *Store) SyncDescriptions(ctx context.Context, actor, id string, after int) (call.MediaSnapshot, error) {
	if after < 0 || after > 2 {
		return call.MediaSnapshot{}, identity.ErrInvalid
	}
	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly})
	if err != nil {
		return call.MediaSnapshot{}, err
	}
	defer tx.Rollback(ctx)
	c, err := scanCall(tx.QueryRow(ctx, `SELECT `+callColumns+` FROM calls WHERE id=$1 AND (caller_id=$2 OR callee_id=$2)`, id, actor))
	if err != nil {
		return call.MediaSnapshot{}, err
	}
	result := call.MediaSnapshot{Call: c, Descriptions: []call.Description{}}
	if c.State != "accepted" {
		return result, nil
	}
	// Each participant receives only the peer's description, in durable sequence order.
	peerSeq := 1
	if actor == c.CallerID {
		peerSeq = 2
	}
	rows, err := tx.Query(ctx, `SELECT sequence,sdp FROM media_descriptions WHERE call_id=$1 AND sequence=$2 AND sequence>$3 AND created_at>clock_timestamp()-interval '2 minutes'`, id, peerSeq, after)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		var d call.Description
		if err = rows.Scan(&d.Sequence, &d.SDP); err != nil {
			return result, err
		}
		d.Type = "offer"
		if d.Sequence == 2 {
			d.Type = "answer"
		}
		result.Descriptions = append(result.Descriptions, d)
	}
	return result, rows.Err()
}
