-- =============================================================================
-- ClickHouse DDL for event-storage
-- =============================================================================
-- Tables:
--   events_raw          — primary MergeTree storage
--   events_raw_kafka    — Kafka Engine (ingestion from topic events.raw)
--   events_raw_mv       — Materialized View: Kafka → events_raw
--
-- Schema mirrors UserActivitySchema (Java/Python):
--   Promoted columns:   event_time, user_id, record_type, chat_id, message_id
--   Dynamic attributes: attrs Map(String, String)
--   Hot attributes:     attr_lang, attr_source, attr_score (materialized)
-- =============================================================================

CREATE DATABASE IF NOT EXISTS default;

-- ── Primary storage ──────────────────────────────────────────────────────────

DROP TABLE IF EXISTS default.events_raw;

CREATE TABLE IF NOT EXISTS default.events_raw
(
    -- Promoted columns (participate in ORDER BY / projections)
    event_time    DateTime                CODEC(DoubleDelta, LZ4),
    ingest_time   DateTime                CODEC(DoubleDelta, LZ4),
    record_type   LowCardinality(String)  CODEC(ZSTD(1)),
    event_id      UUID                    CODEC(LZ4),
    user_id       UInt64 DEFAULT 0        CODEC(T64, LZ4),
    chat_id       UInt64 DEFAULT 0        CODEC(T64, LZ4),
    message_id    UInt64 DEFAULT 0        CODEC(T64, LZ4),

    -- Dynamic attributes — all type-specific fields from UserActivitySchema
    -- Keys: lowercase attribute names (e.g. 'lang', 'source', 'moderation_score')
    -- Values: string-serialized (booleans as '0'/'1', longs as decimal strings)
    attrs         Map(String, String)     CODEC(ZSTD(3)),

    -- Hot attributes — materialized from attrs for fast filtering/aggregation
    -- These are defined in application.yml → app.hot-attributes.columns
    attr_lang     LowCardinality(String)  MATERIALIZED attrs['lang'],
    attr_source   LowCardinality(String)  MATERIALIZED attrs['source'],
    attr_score    Int32                   MATERIALIZED toInt32OrZero(attrs['moderation_score'])
)
ENGINE = MergeTree
PARTITION BY toDate(event_time)
ORDER BY (user_id, record_type, event_time)
SETTINGS index_granularity = 8192, min_bytes_for_wide_part = 0, min_rows_for_wide_part = 0;

-- OLAP projection: analytics by record_type + time
ALTER TABLE default.events_raw
    ADD PROJECTION IF NOT EXISTS by_record_type
    (SELECT * ORDER BY (record_type, event_time));

-- Skip index: bloom filter on chat_id for point lookups
ALTER TABLE default.events_raw
    ADD INDEX IF NOT EXISTS idx_chat_id chat_id TYPE bloom_filter GRANULARITY 4;

-- ── Kafka Engine (ingestion) ─────────────────────────────────────────────────

DROP TABLE IF EXISTS default.events_raw_kafka;

CREATE TABLE IF NOT EXISTS default.events_raw_kafka
(
    event_time   DateTime,
    ingest_time  DateTime,
    record_type  String,
    event_id     String,           -- UUID arrives as string, converted in MV
    user_id      UInt64,
    chat_id      UInt64,
    message_id   UInt64,
    attrs        Map(String, String)
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list   = 'host.docker.internal:9092',
    kafka_topic_list    = 'events.raw',
    kafka_group_name    = 'clickhouse-events-consumer',
    kafka_format        = 'JSONEachRow',
    kafka_num_consumers = 1,
    kafka_skip_broken_messages = 10;

-- ── Materialized View: Kafka → MergeTree ─────────────────────────────────────

DROP VIEW IF EXISTS default.events_raw_mv;

CREATE MATERIALIZED VIEW IF NOT EXISTS default.events_raw_mv
TO default.events_raw
AS SELECT
    event_time,
    ingest_time,
    toLowCardinality(record_type)   AS record_type,
    toUUID(event_id)                AS event_id,
    user_id,
    chat_id,
    message_id,
    attrs
FROM default.events_raw_kafka;
