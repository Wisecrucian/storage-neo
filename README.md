# Event Storage (Kafka + ClickHouse)

Repository for local development of event storage:

- API service (`event-storage`)
- Kafka stack (`kafka`)
- ClickHouse (`clickhouse`)
- Monitoring (`event-storage/monitoring`)

## Quick run

```bash
# 1) Infra
cd kafka && docker compose up -d
cd ../clickhouse && docker compose up -d

# 2) App
cd ../event-storage && docker compose up -d --build

# 3) Init ClickHouse tables
docker exec -i clickhouse clickhouse-client < ../clickhouse/init.d/01-create-tables.sql
docker exec -i clickhouse clickhouse-client < ../clickhouse/init.d/02-create-events-raw.sql
```

If Docker build is unstable, run app locally:

```bash
cd event-storage
./gradlew :event-storage-server:bootRun --args='--server.port=8082'
```

## Endpoints

- Event API: `http://localhost:8081`
- Storage UI: `http://localhost:8081`
- Kafka UI: `http://localhost:8080`
- ClickHouse HTTP: `http://localhost:8123`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin/admin`)

## Smoke test

```bash
curl -X POST "http://localhost:8081/api/events/send" \
  -H "Content-Type: application/json" \
  -d '{"eventType":"smoke","userId":"u1","timestamp":"2026-03-21T10:00:00Z"}'

curl "http://localhost:8081/api/storage/events?limit=5"
```

## Main docs

- `docs/CLAUDE_QUICKSTART.md` - shortest project context.
- `docs/claude/` - token-efficient instructions/prompts for Claude.
- `docs/context/` - architecture and context docs.
- `docs/reports/` - experiment reports.
- `experiments/scripts/` - experiment runners.
- `experiments/results/` - json artifacts.
- `experiments/logs/` - run logs.
- `event-storage/README.md` - service details, monitoring, checklist.
- `clickhouse/init.d/` - ClickHouse schemas and Kafka/MV DDL.
- `perf/` - offline load testing tools.

## Offline recovery runbook (short)

```bash
# 1) Check Docker daemon
docker info

# 2) Start/restart ClickHouse
cd clickhouse
docker compose up -d
docker compose ps
curl http://localhost:8123/ping

# 3) Re-apply schemas if needed
docker exec -i clickhouse clickhouse-client < /Users/max/Downloads/ITMO/вкр/clickhouse/init.d/01-create-tables.sql
docker exec -i clickhouse clickhouse-client < /Users/max/Downloads/ITMO/вкр/clickhouse/init.d/02-create-events-raw.sql
```
