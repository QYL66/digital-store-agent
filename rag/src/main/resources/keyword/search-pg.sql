WITH input AS (
    SELECT store_keyword_text(:query) AS phrase
),
search_terms AS (
    SELECT phrase,
           to_tsquery('simple', (SELECT string_agg(lexeme[1], ' | ' ORDER BY lexeme[1])
                                 FROM regexp_matches(phrase, '\m([a-z][a-z0-9]*[0-9][a-z0-9]*|[0-9]+[a-z0-9]*)\M', 'g') AS lexeme)) AS exact
    FROM input
)
SELECT v.id, v.content, v.collection_name,
       coalesce(ts_rank(to_tsvector('simple', store_keyword_text(v.content)), t.exact), 0)
           + word_similarity(t.phrase, store_keyword_text(v.content)) AS score
FROM t_knowledge_vector v
CROSS JOIN search_terms t
WHERE /* scope */ TRUE
  AND t.phrase <> ''
  AND (to_tsvector('simple', store_keyword_text(v.content)) @@ t.exact
       OR store_keyword_text(v.content) %> t.phrase)
ORDER BY score DESC, v.id
LIMIT :limit;
