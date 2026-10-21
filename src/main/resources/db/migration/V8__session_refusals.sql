-- Refusals (the model's LOW_CONFIDENCE answer) are recorded, so the refusal lane can be measured (METRICS.md).
-- status = LOW_CONFIDENCE while the latest answer is a refusal (an outfit after it sets ACTIVE again);
-- refusal_count keeps how many refusals the session had, whatever happened next.
ALTER TABLE ai_session DROP CONSTRAINT ck_ai_session_status;
ALTER TABLE ai_session ADD CONSTRAINT ck_ai_session_status
    CHECK (status IN ('ACTIVE', 'LOW_CONFIDENCE', 'COMPLETED', 'ABANDONED'));
ALTER TABLE ai_session ADD COLUMN refusal_count integer NOT NULL DEFAULT 0;
