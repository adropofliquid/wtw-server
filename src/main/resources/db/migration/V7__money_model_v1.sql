-- Money model v1 (decided 2026-10-18): free 5 credits a week, premium 50, AI item image 3.
-- Only these three keys change; any other number tuned in SQL stays as it is.
-- Suggestion session (1, flat at start) and describe-outfit (1) are already right in V2.
UPDATE app_config
SET config_map = config_map || '{"free_weekly_credits": 5, "premium_weekly_credits": 50, "cost_ai_image": 3}'::jsonb
WHERE name = 'ledger';
