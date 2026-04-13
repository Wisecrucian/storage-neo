# Record Storage Prototype (ClickHouse)

## 1) SQL table

```sql
CREATE TABLE IF NOT EXISTS default.events_raw
(
    event_time DateTime,
    ingest_time DateTime,
    event_type String,
    event_id String,
    user_id Nullable(UInt64),
    chat_id Nullable(UInt64),
    message_id Nullable(UInt64),
    attrs JSON
)
ENGINE = MergeTree
PARTITION BY toDate(event_time)
ORDER BY (event_type, event_time);
```

Apply:

```bash
docker exec -i clickhouse clickhouse-client < ../clickhouse/init.d/02-create-events-raw.sql
```

## 2) Java classes (added)

- Domain: `Record`, `ModerationFilter`, `AggregationQuery`, `AggregationResult`
- API contracts: `RecordRepository`, `RecordQueryService`
- ClickHouse layer: `ClickHouseClient`, `ClickHouseMapper`, `ClickHouseRecordRepository`, `ClickHouseRecordQueryService`
- Metrics: `RecordStorageMetrics`
- Test/load helper: `RecordDataGenerator`
- REST API: `RecordStorageController`

## 3) Batch insert example

HTTP insert with JSONEachRow:

```sql
INSERT INTO default.events_raw
(event_time, ingest_time, event_type, event_id, user_id, chat_id, message_id, attrs)
FORMAT JSONEachRow
{"event_time":"2026-03-21T12:00:00Z","ingest_time":"2026-03-21T12:00:01Z","event_type":"message_sent","event_id":"evt-1","user_id":1001,"chat_id":42,"message_id":777001,"attrs":{"source":"mobile","lang":"ru"}}
{"event_time":"2026-03-21T12:00:02Z","ingest_time":"2026-03-21T12:00:03Z","event_type":"moderation_flag","event_id":"evt-2","user_id":1002,"chat_id":42,"message_id":777002,"attrs":{"score":93}}
```

In code: `ClickHouseRecordRepository#saveAll(List<Record>)` splits input into 500-2000 sized flushes and writes each flush in a single insert.

## 4) Query examples

Moderation (OLTP-like):

```sql
SELECT event_time, ingest_time, event_type, event_id, user_id, chat_id, message_id, attrs
FROM default.events_raw
WHERE user_id = 1001 AND chat_id = 42
ORDER BY event_time DESC
LIMIT 100;
```

Analytics (OLAP):

```sql
SELECT
  toStartOfMinute(event_time) AS bucket,
  event_type,
  count() AS count,
  count() / 60.0 AS rate
FROM default.events_raw
WHERE event_time >= now() - INTERVAL 1 HOUR
GROUP BY bucket, event_type
ORDER BY bucket DESC, count DESC
LIMIT 1000;
```

Advanced analytics scenarios for field testing:

- `events_by_type_timeseries` - load profile by minute and event type.
- `top_users_by_volume` - top user/event_type pairs by volume.
- `flagged_users_spike` - users with high `moderation_flag` density.
- `attrs_source_lang_breakdown` - distribution by `attrs.source` and `attrs.lang`.

Run via API:

```bash
curl "http://localhost:8082/api/records/analytics/run?scenario=top_users_by_volume&limit=200"
curl "http://localhost:8082/api/records/analytics/run?scenario=flagged_users_spike&limit=200"
curl "http://localhost:8082/api/records/analytics/run?scenario=attrs_source_lang_breakdown&limit=200"
```

## 5) Metrics

Emitted:

- Write: `records_written_total`, `batch_size`, `write_latency_ms`, `write_errors_total`, `writes_success_total`, `writes_failed_total`
- Read: `read_requests_total{read_type=moderation|analytics}`, `read_latency_ms{read_type=...}`
- Batching: `batch_queue_size`, `batch_flush_size`, `batch_flush_latency`, `batch_wait_time`
- Rate: `records_ingest_rate`

p95/p99 (PromQL examples):

```promql
histogram_quantile(0.95, sum(rate(write_latency_ms_seconds_bucket[5m])) by (le))
histogram_quantile(0.99, sum(rate(write_latency_ms_seconds_bucket[5m])) by (le))
histogram_quantile(0.95, sum(rate(read_latency_ms_seconds_bucket{read_type="moderation"}[5m])) by (le))
histogram_quantile(0.99, sum(rate(read_latency_ms_seconds_bucket{read_type="analytics"}[5m])) by (le))
```

## 6) Data generator + load simulation

- Generate and save: `POST /api/records/generate-and-save?count=1000`
- Load simulation: `POST /api/records/simulate-load?rounds=20&perRound=1000`
- Manual batch save: `POST /api/records/save-batch`
