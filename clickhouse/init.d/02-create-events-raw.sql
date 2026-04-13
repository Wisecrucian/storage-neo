DROP TABLE IF EXISTS default.events_raw;

CREATE TABLE IF NOT EXISTS default.events_raw
(
    event_time    DateTime                CODEC(DoubleDelta, LZ4),
    ingest_time   DateTime                CODEC(DoubleDelta, LZ4),
    record_type   LowCardinality(String)  CODEC(ZSTD(1)),
    event_id      UUID                    CODEC(LZ4),
    user_id       UInt64 DEFAULT 0        CODEC(T64, LZ4),
    chat_id       UInt64 DEFAULT 0        CODEC(T64, LZ4),
    message_id    UInt64 DEFAULT 0        CODEC(T64, LZ4),

    attrs         Map(String, String)     CODEC(ZSTD(3))
)
ENGINE = MergeTree
PARTITION BY toDate(event_time)
ORDER BY (user_id, record_type, event_time)
SETTINGS index_granularity = 8192, min_bytes_for_wide_part = 0, min_rows_for_wide_part = 0;

-- OLAP: сканирование по типу события + временной ряд (аналитика, агрегации)
ALTER TABLE default.events_raw
    ADD PROJECTION IF NOT EXISTS by_record_type
    (SELECT * ORDER BY (record_type, event_time));
