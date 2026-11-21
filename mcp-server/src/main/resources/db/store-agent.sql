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

CREATE TABLE IF NOT EXISTS store_demo_center (
    code text PRIMARY KEY,
    city text NOT NULL,
    name text NOT NULL,
    supported_brands text[] NOT NULL
);

CREATE TABLE IF NOT EXISTS store_demo_booking (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    center_code text NOT NULL REFERENCES store_demo_center(code),
    starts_at timestamptz NOT NULL,
    model_id text NOT NULL REFERENCES store_catalog(id),
    symptom text NOT NULL,
    user_id varchar(64) NOT NULL,
    UNIQUE (center_code, starts_at)
);

CREATE INDEX IF NOT EXISTS store_demo_booking_user ON store_demo_booking (user_id, starts_at DESC);

INSERT INTO store_demo_center (code, city, name, supported_brands) VALUES
    ('HRB-A', '哈尔滨', '松北演示维修站', ARRAY['Xiaomi','REDMI']),
    ('HRB-B', '哈尔滨', '南岗演示维修站', ARRAY['Xiaomi']),
    ('BJ-A', '北京', '海淀演示维修站', ARRAY['Xiaomi','REDMI']),
    ('BJ-B', '北京', '朝阳演示维修站', ARRAY['REDMI'])
ON CONFLICT (code) DO NOTHING;
