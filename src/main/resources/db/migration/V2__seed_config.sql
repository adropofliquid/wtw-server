-- Live numbers and notification copy (brief 3.7). Read through a 5-minute cache; edit in SQL, no deploy.
INSERT INTO app_config (id, name, config_map) VALUES
('cfg_weekly_reset_notification', 'weekly_reset_notification',
 '{"title": "New week, fresh fits! 🎉", "body": "Time to put together something you''ll love wearing. ✨"}'),
('cfg_habit_builder_notification', 'habit_builder_notification',
 '{"title": "Ready for today''s outfit? ☀️", "body": "You looked great yesterday — let''s keep that energy going! 💪"}'),
('cfg_ledger', 'ledger',
 '{"free_weekly_credits": 7, "premium_weekly_credits": 100, "cost_outfit_suggestion": 1, "cost_ai_image": 2, "cost_outfit_description": 1, "free_daily_generations": 20, "premium_daily_generations": 200, "max_attempts_per_session": 10, "attributes_daily_cap": 200, "remove_bg_daily_cap": 300, "free_item_limit": 100, "packs": {}}')
ON CONFLICT (name) DO NOTHING;
