-- One push registration per device. Nullable: a device that never reported a
-- token, or whose token FCM rejected as UNREGISTERED, simply has no target.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS push_provider text;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS push_token text;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS push_token_updated_at timestamptz;
