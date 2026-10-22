-- Outfit-level "hidden" flag. An outfit worn on a past day cannot be deleted in the app, only hidden;
-- stored here so a restore on a new phone keeps it hidden. Mirrored like any other field (last writer wins).
ALTER TABLE mirror_outfit ADD COLUMN archived boolean NOT NULL DEFAULT false;
