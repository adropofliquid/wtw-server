-- App style words changed on 2026-10-18 (service/Vocab.kt holds the same mapping for incoming data).
-- Old words are rewritten in place; a list keeps its order and loses duplicates. Library vibes are re-tagged by
-- the seed (VibeTagger); this only maps the starters' old words so the vibe filter keeps matching until then.

CREATE FUNCTION wtw_v4_vibe(w text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE lower(btrim(w))
    WHEN 'clean cut' THEN 'Classic' WHEN 'sporty' THEN 'Sporty' WHEN 'chill mode' THEN 'Minimal'
    WHEN 'fresh fit' THEN 'Smart casual' WHEN 'glow up' THEN 'Colourful'
    WHEN 'classic' THEN 'Classic' WHEN 'minimal' THEN 'Minimal' WHEN 'smart casual' THEN 'Smart casual'
    WHEN 'streetwear' THEN 'Streetwear' WHEN 'colourful' THEN 'Colourful'
    ELSE btrim(w) END $$;

CREATE FUNCTION wtw_v4_occasion(w text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE lower(btrim(w))
    WHEN 'social' THEN 'Going out' WHEN 'date night' THEN 'Date night' WHEN 'work' THEN 'Work'
    WHEN 'casual' THEN 'Casual' WHEN 'active' THEN 'Active' WHEN 'going out' THEN 'Going out'
    WHEN 'travel' THEN 'Travel' WHEN 'not sure' THEN 'Not sure'
    ELSE btrim(w) END $$;

CREATE FUNCTION wtw_v4_comfort(w text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE lower(btrim(w))
    WHEN 'cozy' THEN 'Cosy' WHEN 'cosy' THEN 'Cosy' WHEN 'comfortable' THEN 'Relaxed' WHEN 'effortless' THEN 'Relaxed'
    WHEN 'relaxed' THEN 'Relaxed' WHEN 'sharp' THEN 'Smart' WHEN 'smart' THEN 'Smart' WHEN 'bold' THEN 'Bold'
    ELSE btrim(w) END $$;

-- Maps each element with f ('v' vibe, 'o' occasion), keeps first-seen order, drops blanks and duplicates.
CREATE FUNCTION wtw_v4_list(a text[], f char) RETURNS text[] LANGUAGE sql IMMUTABLE AS $$
  SELECT coalesce(array_agg(m ORDER BY i), '{}') FROM (
    SELECT m, min(i) AS i FROM (
      SELECT CASE f WHEN 'v' THEN wtw_v4_vibe(x) ELSE wtw_v4_occasion(x) END AS m, i
      FROM unnest(a) WITH ORDINALITY AS u(x, i) WHERE btrim(x) <> ''
    ) s GROUP BY m
  ) d $$;

UPDATE user_profile SET vibes = wtw_v4_list(vibes, 'v'), events = wtw_v4_list(events, 'o')
 WHERE vibes <> wtw_v4_list(vibes, 'v') OR events <> wtw_v4_list(events, 'o');
UPDATE ai_session SET vibes = wtw_v4_list(vibes, 'v'), day_type = wtw_v4_occasion(day_type), comfort = wtw_v4_comfort(comfort)
 WHERE vibes <> wtw_v4_list(vibes, 'v') OR day_type IS DISTINCT FROM wtw_v4_occasion(day_type)
    OR comfort IS DISTINCT FROM wtw_v4_comfort(comfort);
UPDATE ai_suggestion SET vibes = wtw_v4_list(vibes, 'v'), day_type = wtw_v4_occasion(day_type)
 WHERE vibes <> wtw_v4_list(vibes, 'v') OR day_type IS DISTINCT FROM wtw_v4_occasion(day_type);
UPDATE library_item SET vibes = wtw_v4_list(vibes, 'v') WHERE vibes <> wtw_v4_list(vibes, 'v');

DROP FUNCTION wtw_v4_list(text[], char);
DROP FUNCTION wtw_v4_vibe(text);
DROP FUNCTION wtw_v4_occasion(text);
DROP FUNCTION wtw_v4_comfort(text);
