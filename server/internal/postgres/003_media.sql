CREATE TABLE media_descriptions (
    call_id text NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
    sequence integer NOT NULL CHECK (sequence IN (1,2)),
    message_id text NOT NULL,
    sdp text NOT NULL CHECK (octet_length(sdp) <= 49152),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (call_id, sequence)
);
