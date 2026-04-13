# Architecture

## Goal
Build an event-storage layer that supports:
- high write throughput (append-heavy load),
- low-latency point reads for moderation workflows,
- analytical queries over time windows,
- predictable storage cost via compression.

## Components
- `event-storage-server` (Java 17, Spring Boot): REST API for writes/reads.
- `Kafka` (KRaft): durable buffer between API and ClickHouse.
- `ClickHouse`:
  - `events_raw_kafka` (Kafka Engine) for ingestion,
  - `events_raw_mv` (Materialized View) for transformation,
  - `events_raw` (MergeTree) as primary storage.
- `Prometheus + Grafana` for metrics and dashboards.

## Write Path
1. Client sends batch to `POST /api/records/save-batch`.
2. Java service validates/maps records and publishes JSON rows to Kafka topic `events.raw`.
3. ClickHouse `events_raw_kafka` consumes from topic.
4. `events_raw_mv` converts fields (`record_type`, `event_id`, etc.) and inserts into `events_raw`.
5. MergeTree stores parts, then merges in background.

Why this path:
- decouples API latency from ClickHouse insert internals,
- smooths bursts via Kafka buffering,
- reduces pressure from small direct inserts.

## Read Path
- Point/moderation reads query `events_raw` (optimized by primary sort key).
- Analytical reads by `record_type + time` use projection `by_record_type`.
- API can expose predefined analytics scenarios; ad-hoc SQL reads are executed in ClickHouse.

## Kafka -> ClickHouse Flow (DDL-level)
- Kafka table: `default.events_raw_kafka`
  - format: `JSONEachRow`
  - topic: `events.raw`
  - consumer group: `clickhouse-events-consumer`
- Materialized view: `default.events_raw_mv TO default.events_raw`
  - casts `record_type` to `LowCardinality(String)`
  - casts `event_id` to `UUID`

## Table Schema (current)
`default.events_raw`:
- `event_time DateTime CODEC(DoubleDelta, LZ4)`
- `ingest_time DateTime CODEC(DoubleDelta, LZ4)`
- `record_type LowCardinality(String) CODEC(ZSTD(1))`
- `event_id UUID CODEC(LZ4)`
- `user_id UInt64 DEFAULT 0 CODEC(T64, LZ4)`
- `chat_id UInt64 DEFAULT 0 CODEC(T64, LZ4)`
- `message_id UInt64 DEFAULT 0 CODEC(T64, LZ4)`
- `attrs Map(String, String) CODEC(ZSTD(3))`

Physical layout:
- `ENGINE = MergeTree`
- `PARTITION BY toDate(event_time)`
- `ORDER BY (user_id, record_type, event_time)`
- projection: `by_record_type (ORDER BY record_type, event_time)`

## Operational Notes
- This repository is a prototype/sandbox for architecture and measurements.
- Main product code is developed separately (NDA constraints).
