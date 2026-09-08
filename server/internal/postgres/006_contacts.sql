CREATE TABLE IF NOT EXISTS contacts (
 owner_id text NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
 peer_id text NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
 PRIMARY KEY(owner_id, peer_id),
 CHECK(owner_id <> peer_id)
);
