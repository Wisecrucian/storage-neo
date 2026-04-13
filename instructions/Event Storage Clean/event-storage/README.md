# Event Storage

Minimal event storage service:

- REST API for ingest (`/api/events/send`)
- Kafka as ingest buffer
- ClickHouse as analytical storage
- Minimal web UI at `/` for send + read latest events

## Run

From repository root:

```bash
# 1. Start Kafka and ClickHouse
cd kafka && docker compose up -d
cd ../clickhouse && docker compose up -d

# 2. Start API
cd ../event-storage && docker compose up -d --build

# 3. Initialize ClickHouse schema
docker exec -i clickhouse clickhouse-client < ../clickhouse/init.d/01-create-tables.sql
docker exec -i clickhouse clickhouse-client < ../clickhouse/init.d/02-create-events-raw.sql
```

If running server locally (recommended fallback):

```bash
cd event-storage
./gradlew :event-storage-server:bootRun --args='--server.port=8082'
```

## URLs

- API + UI: `http://localhost:8081`
- API health: `http://localhost:8081/actuator/health`
- Prometheus metrics: `http://localhost:8081/actuator/prometheus`
- Kafka UI: `http://localhost:8080`
- ClickHouse ping: `http://localhost:8123/ping`

## API

### Send event

`POST /api/events/send?topic=events`

Body:

```json
{
  "eventType": "user_login",
  "userId": "user-123",
  "timestamp": "2026-03-21T10:00:00Z"
}
```

### Read latest from ClickHouse

`GET /api/storage/events?limit=20`

### Search by custom field

`GET /api/storage/search?field=sessionId&value=sess-1&limit=50`

Supports nested JSON path with dot notation:

`GET /api/storage/search?field=nested.key&value=value`

### Check ClickHouse connection

`GET /api/storage/status`

## Monitoring

```bash
cd monitoring
docker compose up -d
```

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin/admin`)
- Kafka exporter: `http://localhost:9308/metrics`
- ClickHouse exporter: `http://localhost:9116/metrics`

Pre-provisioned Grafana dashboards:

- `Event Storage Overview`
- `Event Storage Ingestion`
- `Event Storage Infra`
- `Event Storage Business`
- `Event Storage Records`

Business metrics written from code:

- `event_storage_business_events_total{event_type=...}`
- `event_storage_business_purchase_count_total`
- `event_storage_business_purchase_amount_total`

## Record storage prototype (ClickHouse events_raw)

SQL schema:

```bash
docker exec -i clickhouse clickhouse-client < ../clickhouse/init.d/02-create-events-raw.sql
```

Repository/query API:

- `POST /api/records/save-batch` - batch write (`List<Record>`, 500-2000 rows per flush)
- `GET /api/records/moderation` - moderation reads by `userId/chatId/messageId`
- `GET /api/records/analytics` - OLAP aggregations by `event_type` and time bucket
- `POST /api/records/generate-and-save?count=1000` - test data generator
- `POST /api/records/simulate-load?rounds=20&perRound=1000` - load simulation
- `GET /api/records/analytics/scenarios` - list of advanced analytics scenarios
- `GET /api/records/analytics/run?scenario=...` - execute scenario query

Example write payload:

```json
[
  {
    "eventTime": "2026-03-21T12:00:00Z",
    "ingestTime": "2026-03-21T12:00:01Z",
    "eventType": "message_sent",
    "eventId": "evt-1",
    "userId": 1001,
    "chatId": 42,
    "messageId": 777001,
    "attrs": {
      "source": "mobile",
      "lang": "ru"
    }
  }
]
```

Example moderation query:

```bash
curl "http://localhost:8082/api/records/moderation?userId=1001&limit=50"
```

Example analytics query:

```bash
curl "http://localhost:8082/api/records/analytics?interval=minute&limit=200"
```

Advanced analytics scenarios for field testing:

- `events_by_type_timeseries` - dynamics and rate by event type.
- `top_users_by_volume` - top users by event volume.
- `flagged_users_spike` - users with high moderation flag density.
- `attrs_source_lang_breakdown` - traffic split by `attrs.source` and `attrs.lang`.

Example:

```bash
curl "http://localhost:8082/api/records/analytics/run?scenario=top_users_by_volume&limit=100"
```

Metrics exposed on `/actuator/prometheus`:

- write path: `records_written_total`, `batch_size`, `write_latency_ms`, `write_errors_total`, `writes_success_total`, `writes_failed_total`
- read path: `read_requests_total{read_type=moderation|analytics}`, `read_latency_ms`
- batching: `batch_queue_size`, `batch_flush_size`, `batch_flush_latency`, `batch_wait_time`
- ingest: `records_ingest_rate`

## UI custom fields

Open `http://localhost:8082` (or `http://localhost:8081` when running in Docker).

In "Send event" section:

- Fill base fields (`eventType`, `userId`)
- Put any extra JSON in "Custom fields JSON"
- Click `Send`

Custom keys are forwarded to Kafka and stored in ClickHouse raw `message`.

To search custom fields in UI:

- Fill `custom field` (for example: `sessionId`, `ipAddress`, `nested.key`)
- Fill `value`
- Click `Search custom field`

## ClickHouse exploration (JSON fields)

Run inside container:

```bash
docker exec -it clickhouse clickhouse-client
```

Useful queries:

```sql
-- Last raw event payloads
SELECT event_id, event_type, message
FROM default.events_storage
ORDER BY created_at DESC
LIMIT 10;

-- Filter by custom string field
SELECT event_id, user_id, timestamp
FROM default.events_storage
WHERE JSONExtractString(message, 'sessionId') = 'sess-1'
ORDER BY created_at DESC
LIMIT 20;

-- Nested field path
SELECT event_id
FROM default.events_storage
WHERE JSONExtractString(message, 'nested', 'key') = 'value'
LIMIT 20;

-- Numeric custom field
SELECT event_id, JSONExtractInt(message, 'score') AS score
FROM default.events_storage
WHERE JSONExtractInt(message, 'score') >= 40
ORDER BY score DESC
LIMIT 20;
```

## Offline checklist

Before going offline:

```bash
cd ../kafka && docker compose pull
cd ../clickhouse && docker compose pull
cd ../event-storage && docker compose pull
cd ../event-storage/monitoring && docker compose pull
```

Smoke test:

```bash
curl -X POST "http://localhost:8081/api/events/send" \
  -H "Content-Type: application/json" \
  -d '{"eventType":"smoke","userId":"u1","timestamp":"2026-03-21T10:00:00Z"}'

curl "http://localhost:8081/api/storage/events?limit=5"

# records prototype smoke
curl -X POST "http://localhost:8082/api/records/generate-and-save?count=1000"
curl "http://localhost:8082/api/records/moderation?limit=20"
curl "http://localhost:8082/api/records/analytics/run?scenario=events_by_type_timeseries&limit=20"
```

## Troubleshooting (offline day)

### ClickHouse is down

```bash
cd clickhouse
docker compose up -d
docker compose ps
curl -m 5 http://localhost:8123/ping
docker exec -i clickhouse clickhouse-client -q "SELECT version()"
```

Expected ping response: `Ok.`

### Docker commands hang / no response

1. Restart Docker Desktop.
2. Wait until `docker info` works.
3. Re-run `docker compose up -d` for `kafka`, `clickhouse`, `event-storage/monitoring`.

### Record dashboard has no data

1. Ensure app metrics endpoint is alive: `http://localhost:8082/actuator/prometheus`
2. Generate traffic:
   - `POST /api/records/generate-and-save?count=1000`
   - `GET /api/records/analytics/run?scenario=events_by_type_timeseries&limit=50`
3. Wait 10-30s for Prometheus scrape.

## Offline load-testing kit

From repository root:

```bash
# Generate dataset (NDJSON + CSV)
python3 perf/generate_events.py --count 50000

# Replay to API (no external Python deps)
python3 perf/replay_events.py --file perf/events.ndjson --base-url http://localhost:8082 --concurrency 16 --max-events 20000
```

Optional if `k6` is installed:

```bash
k6 run perf/k6-smoke.js
```

Heavy profiles:

```bash
# Heavy staged test
bash perf/run-heavy.sh http://localhost:8082

# Extreme staged test
bash perf/run-extreme.sh http://localhost:8082
```

Results are saved to `perf/results/*.json`.


