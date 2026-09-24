ALTER TABLE users ADD COLUMN can_access_2d boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN can_access_3d boolean NOT NULL DEFAULT false;

-- Existing accounts retain their previous project access after this migration.
UPDATE users SET can_access_2d = true, can_access_3d = true;
