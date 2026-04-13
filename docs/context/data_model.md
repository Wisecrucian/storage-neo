# Data Model

## Data Shape
Each event has:
- timestamps (`event_time`, `ingest_time`),
- event classification (`record_type`),
- identifiers (`event_id`, `user_id`, `chat_id`, `message_id`),
- dynamic attributes (`attrs`).

This supports mixed workloads: point lookups and time-based analytics.

## `attrs_raw` -> `Map(String, String)` (Map vs JSON)

## Historical approach
- Attributes were effectively treated as raw JSON/text (`attrs_raw`-style usage).
- Filtering and aggregation over dynamic keys required parsing/extracting on read.

## Current approach
- `attrs` stored as `Map(String, String)`.
- Benefits vs raw JSON string:
  - explicit type in schema,
  - native key access (`attrs['lang']`, `attrs['source']`),
  - better compression behavior with repeated keys/values,
  - cleaner evolution path for hot attributes.

Trade-off:
- `Map` keeps flexibility, but ad-hoc filters on rare keys can still be scan-heavy without additional optimization.

## Materialized Columns (hot attributes)
For frequently queried keys, extract from `attrs` into dedicated columns.

Examples used in project:
- `attr_lang` <- `attrs['lang']`
- `attr_source` <- `attrs['source']`
- `attr_score` <- `toInt32OrZero(attrs['score'])`

Why:
- avoids repeated map extraction/parsing on each query,
- improves aggregation/filter performance on common dimensions,
- allows secondary skip indexes on derived columns when useful.

Cost:
- extra disk for new column data,
- extra compute at insert/materialization time.

## Projections
Main table ordering is optimized for OLTP-style point reads:
- `ORDER BY (user_id, record_type, event_time)`.

For OLAP-style reads:
- projection `by_record_type` with `ORDER BY (record_type, event_time)`.

Effect:
- one physical layout for point lookups,
- one alternative layout for analytics,
- reduced read amplification for `record_type + time` queries.

## Partitioning
- `PARTITION BY toDate(event_time)`.

Why:
- natural time-bounded retention/maintenance operations,
- partition pruning for time-window queries,
- manageable part lifecycle (merge/mutation scope by date).

## Compression + Types (rationale)
- `DateTime`: `DoubleDelta + LZ4` (good for monotonic-ish time series).
- integer IDs: `T64 + LZ4` (effective for numeric columns).
- low-cardinality dimensions: `LowCardinality(String) + ZSTD`.
- dynamic attrs map: `ZSTD(3)`.

Important:
- random UUID v4 compresses poorly; v7 improves compressibility for `event_id` due to time-order structure.
