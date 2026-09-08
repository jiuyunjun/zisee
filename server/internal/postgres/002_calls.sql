CREATE TABLE IF NOT EXISTS call_invites (
    token_hash bytea PRIMARY KEY,
    creator_id text NOT NULL UNIQUE REFERENCES identities(id),
    expires_at timestamptz NOT NULL,
    redeemed_by text REFERENCES identities(id),
    call_id text
);
CREATE TABLE IF NOT EXISTS calls (
    id text PRIMARY KEY,
    caller_id text NOT NULL REFERENCES identities(id),
    callee_id text NOT NULL REFERENCES identities(id),
    state text NOT NULL CHECK (state IN ('ringing','accepted','rejected','ended')),
    expires_at timestamptz NOT NULL,
    CHECK (caller_id <> callee_id)
);
CREATE INDEX IF NOT EXISTS calls_caller ON calls(caller_id, expires_at);
CREATE INDEX IF NOT EXISTS calls_callee ON calls(callee_id, expires_at);
