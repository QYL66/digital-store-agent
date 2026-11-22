CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE OR REPLACE FUNCTION store_keyword_text(input text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN btrim(
    regexp_replace(
        regexp_replace(
            regexp_replace(
                regexp_replace(
                    regexp_replace(lower(normalize(coalesce(input, ''), NFKC)),
                        '([0-9]+)\s+(pro|max|ultra|plus|w)(?=$|[^a-z0-9])', '\1\2', 'g'),
                    '([0-9]+pro)\s+max(?=$|[^a-z0-9])', '\1max', 'g'),
                '([一-鿿])([a-z0-9])', '\1 \2', 'g'),
            '([a-z0-9])([一-鿿])', '\1 \2', 'g'),
        '\s+', ' ', 'g'));

CREATE INDEX IF NOT EXISTS idx_store_kw_lexemes ON t_knowledge_vector
    USING gin (to_tsvector('simple', store_keyword_text(content)));

CREATE INDEX IF NOT EXISTS idx_store_kw_trigrams ON t_knowledge_vector
    USING gin (store_keyword_text(content) gin_trgm_ops);
