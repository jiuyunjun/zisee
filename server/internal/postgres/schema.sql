-- Apply with cmd/migrate using a migration role, not the runtime identity.
CREATE TABLE IF NOT EXISTS identities (
    id text PRIMARY KEY,
    display_name text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    id text PRIMARY KEY,
    identity_id text NOT NULL UNIQUE REFERENCES identities(id),
    public_key bytea NOT NULL,
    revoked_at timestamptz
);
CREATE TABLE IF NOT EXISTS auth_challenges (
    id text PRIMARY KEY,
    device_id text NOT NULL REFERENCES devices(id),
    nonce text NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS auth_challenges_expiry ON auth_challenges(expires_at);
CREATE INDEX IF NOT EXISTS auth_challenges_device ON auth_challenges(device_id);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash bytea PRIMARY KEY,
    device_id text NOT NULL REFERENCES devices(id),
    expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);
CREATE INDEX IF NOT EXISTS sessions_device ON sessions(device_id);
