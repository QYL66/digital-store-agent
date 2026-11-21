CREATE TABLE IF NOT EXISTS store_catalog (
    id text PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('phone', 'accessory', 'policy', 'fault', 'service_center')),
    name text NOT NULL,
    series text NOT NULL,
    source_url text NOT NULL,
    region text NOT NULL,
    checked_on date NOT NULL,
    details jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS store_catalog_kind_series ON store_catalog (kind, series);
