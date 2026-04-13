# Minimal Context Pack

For most tasks, load only:

- `README.md`
- `docs/context/architecture.md`
- `docs/reports/EXPERIMENTS_HEAVY_QUERY.md` (if heavy query topic)
- `experiments/scripts/experiments_phase2.py` (if phase2 topic)
- `experiments/scripts/experiment_heavy_query.py` (if heavy query execution topic)

Optional (only if needed):
- `event-storage/README.md`
- `clickhouse/init.d/02-create-events-raw.sql`
- `clickhouse/init.d/03-kafka-engine.sql`

Avoid loading large JSON/log files unless task explicitly asks for raw data.
