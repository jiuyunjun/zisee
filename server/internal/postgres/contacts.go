package postgres

import (
	"context"
	"zisee/server/internal/call"
)

func (s *Store) ListContacts(ctx context.Context, actor string) ([]call.Contact, error) {
	rows, err := s.pool.Query(ctx, `SELECT i.id,i.display_name FROM contacts c JOIN identities i ON i.id=c.peer_id WHERE c.owner_id=$1 ORDER BY i.id LIMIT 20`, actor)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []call.Contact{}
	for rows.Next() {
		var c call.Contact
		if err = rows.Scan(&c.IdentityID, &c.DisplayName); err != nil {
			return nil, err
		}
		result = append(result, c)
	}
	return result, rows.Err()
}
func (s *Store) RemoveContact(ctx context.Context, actor, peer string) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM contacts WHERE (owner_id=$1 AND peer_id=$2) OR (owner_id=$2 AND peer_id=$1)`, actor, peer)
	return err
}
func (s *Store) CallContact(ctx context.Context, actor, peer string) (call.Call, error) {
	if actor == peer {
		return call.Call{}, call.ErrNotFound
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return call.Call{}, err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `SELECT id FROM identities WHERE id=$1 OR id=$2 ORDER BY id FOR UPDATE`, actor, peer)
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
	var count int
	// Lock relationship rows so deletion cannot race a new authorized call.
	rows, err = tx.Query(ctx, `SELECT owner_id FROM contacts WHERE (owner_id=$1 AND peer_id=$2) OR (owner_id=$2 AND peer_id=$1) ORDER BY owner_id FOR UPDATE`, actor, peer)
	if err != nil {
		return call.Call{}, err
	}
	for rows.Next() {
		count++
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return call.Call{}, err
	}
	if count != 2 {
		return call.Call{}, call.ErrNotFound
	}
	var busy bool
	err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM calls WHERE (caller_id IN ($1,$2) OR callee_id IN ($1,$2)) AND state IN ('ringing','accepted') AND expires_at>clock_timestamp())`, actor, peer).Scan(&busy)
	if err != nil {
		return call.Call{}, err
	}
	if busy {
		return call.Call{}, call.ErrBusy
	}
	c, err := scanCall(tx.QueryRow(ctx, `INSERT INTO calls(id,caller_id,callee_id,state,expires_at) VALUES($1,$2,$3,'ringing',clock_timestamp()+interval '60 seconds') RETURNING `+callColumns, randomCallToken(), actor, peer))
	if err != nil {
		return call.Call{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return call.Call{}, err
	}
	return c, nil
}
