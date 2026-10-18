-- The user's original photo (before background removal), backed up so a restore keeps it.
-- Stored in the user bucket under user-raw/{uid}/items/{itemId}/original.{ext}; served on a stable link like the others.
ALTER TABLE mirror_item ADD COLUMN original_key text;
ALTER TABLE mirror_item ADD COLUMN original_version varchar(16);
