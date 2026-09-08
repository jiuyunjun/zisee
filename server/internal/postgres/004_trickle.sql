ALTER TABLE media_descriptions ADD COLUMN candidates jsonb NOT NULL DEFAULT '[]'::jsonb;
