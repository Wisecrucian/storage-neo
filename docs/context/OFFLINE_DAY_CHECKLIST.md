# Offline Day Checklist

Use this checklist when you plan to work without internet for ~24 hours.

## 0) Before going offline (once)

- [ ] Docker Desktop is installed and starts normally.
- [ ] Java 17+ is available (`java -version`).
- [ ] Python 3 is available (`python3 --version`).
- [ ] Required images are pre-pulled:

```bash
cd /Users/max/Downloads/ITMO/вкр/kafka && docker compose pull
cd /Users/max/Downloads/ITMO/вкр/clickhouse && docker compose pull
cd /Users/max/Downloads/ITMO/вкр/event-storage && docker compose pull
cd /Users/max/Downloads/ITMO/вкр/event-storage/monitoring && docker compose pull
```

- [ ] Baseline infra smoke test completed once online.

## 1) Start of offline day

### 1.1 Start infra

```bash
cd /Users/max/Downloads/ITMO/вкр/kafka && docker compose up -d
cd /Users/max/Downloads/ITMO/вкр/clickhouse && docker compose up -d
cd /Users/max/Downloads/ITMO/вкр/event-storage/monitoring && docker compose up -d
```

### 1.2 Start app

Preferred local mode:

```bash
cd /Users/max/Downloads/ITMO/вкр/event-storage
./gradlew :event-storage-server:bootRun --args='--server.port=8082'
```

Alternative Docker mode:

```bash
cd /Users/max/Downloads/ITMO/вкр/event-storage
docker compose up -d --build
```

### 1.3 Initialize ClickHouse schemas

```bash
docker exec -i clickhouse clickhouse-client < /Users/max/Downloads/ITMO/вкр/clickhouse/init.d/01-create-tables.sql
docker exec -i clickhouse clickhouse-client < /Users/max/Downloads/ITMO/вкр/clickhouse/init.d/02-create-events-raw.sql
```

## 2) Quick health checks

- [ ] ClickHouse ping: `http://localhost:8123/ping` -> `Ok.`
- [ ] API health: `http://localhost:8082/actuator/health` (or `8081` in Docker mode)
- [ ] API metrics: `http://localhost:8082/actuator/prometheus`
- [ ] Storage status: `http://localhost:8082/api/storage/status`
- [ ] Grafana: `http://localhost:3000` (`admin/admin`)

## 3) Smoke tests (copy/paste)

### 3.1 Existing event pipeline

```bash
curl -X POST "http://localhost:8082/api/events/send" \
  -H "Content-Type: application/json" \
  -d '{"eventType":"smoke","userId":"u1","timestamp":"2026-03-21T10:00:00Z"}'

curl "http://localhost:8082/api/storage/events?limit=5"
```

### 3.2 Record storage prototype

```bash
curl -X POST "http://localhost:8082/api/records/generate-and-save?count=1000"
curl "http://localhost:8082/api/records/moderation?limit=20"
curl "http://localhost:8082/api/records/analytics/run?scenario=events_by_type_timeseries&limit=20"
```

## 4) Analytics scenarios to test on the road

- [ ] `events_by_type_timeseries` - load/rate dynamics by event type
- [ ] `top_users_by_volume` - heavy users and activity concentration
- [ ] `flagged_users_spike` - moderation-heavy users
- [ ] `attrs_source_lang_breakdown` - custom attrs segmentation

Examples:

```bash
curl "http://localhost:8082/api/records/analytics/run?scenario=top_users_by_volume&limit=100"
curl "http://localhost:8082/api/records/analytics/run?scenario=flagged_users_spike&limit=100"
curl "http://localhost:8082/api/records/analytics/run?scenario=attrs_source_lang_breakdown&limit=100"
```

## 5) Monitoring checks

- [ ] Grafana dashboard `Event Storage Records` shows:
  - `records_written_total` / ingest rate
  - write/read latency
  - batch queue/size/flush metrics
- [ ] Grafana `Event Storage Business` has live lines after traffic
- [ ] If no data yet, wait 10-30 seconds for Prometheus scrape

## 6) Offline load testing

```bash
cd /Users/max/Downloads/ITMO/вкр
python3 perf/generate_events.py --count 50000
python3 perf/replay_events.py --file perf/events.ndjson --base-url http://localhost:8082 --concurrency 16 --max-events 20000
bash perf/run-heavy.sh http://localhost:8082
```

- [ ] Results saved under `perf/results/*.json`

## 7) Recovery playbook (if something goes down)

### 7.1 ClickHouse down

```bash
cd /Users/max/Downloads/ITMO/вкр/clickhouse
docker compose up -d
docker compose ps
curl -m 5 http://localhost:8123/ping
docker exec -i clickhouse clickhouse-client -q "SELECT version()"
```

If tables are missing:

```bash
docker exec -i clickhouse clickhouse-client < /Users/max/Downloads/ITMO/вкр/clickhouse/init.d/01-create-tables.sql
docker exec -i clickhouse clickhouse-client < /Users/max/Downloads/ITMO/вкр/clickhouse/init.d/02-create-events-raw.sql
```

### 7.2 Docker hangs

- [ ] Restart Docker Desktop.
- [ ] Wait until `docker info` succeeds.
- [ ] Re-run `docker compose up -d` for kafka/clickhouse/monitoring.

### 7.3 Empty dashboard

- [ ] Confirm metrics endpoint is alive: `/actuator/prometheus`
- [ ] Generate traffic with `generate-and-save` + analytics calls
- [ ] Wait for scrape interval (10-30s)

## 8) End of day (optional)

- [ ] Save test artifacts (`perf/results/*.json`)
- [ ] Export key Grafana screenshots (optional)
- [ ] Stop infra if needed:

```bash
cd /Users/max/Downloads/ITMO/вкр/event-storage/monitoring && docker compose down
cd /Users/max/Downloads/ITMO/вкр/event-storage && docker compose down
cd /Users/max/Downloads/ITMO/вкр/clickhouse && docker compose down
cd /Users/max/Downloads/ITMO/вкр/kafka && docker compose down
```
