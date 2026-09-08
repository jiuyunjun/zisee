ALTER TABLE calls ADD COLUMN media_generation integer NOT NULL DEFAULT 0;
ALTER TABLE calls ADD COLUMN media_started_at timestamptz;
