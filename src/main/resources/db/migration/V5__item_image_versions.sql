-- Images are served by the API on stable links (`?v=<version>`): the version changes when the image does,
-- so phones can cache each link for good. Set on upload (first 8 hex of the WebP's SHA-256); null = "0".
ALTER TABLE mirror_item ADD COLUMN cutout_version varchar(16);
ALTER TABLE mirror_item ADD COLUMN generated_version varchar(16);
