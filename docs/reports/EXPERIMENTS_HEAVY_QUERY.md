# EXPERIMENTS: тяжёлый OLAP (адаптация прод-запроса → `events_raw`)
_Сгенерировано: 2026-04-05T20:54:25Z_
## Исходные параметры (прод)
- `topBy=USER_ID`, `chunkSize=50` → CTE `top_users`: топ-50 `user_id` по числу событий после фильтров.
- `chartBy=TIMESTAMP` → внешняя агрегация: `toStartOfHour(event_time)`.
- `types=REGISTRATION` → `record_type = 'REGISTRATION'`.
- `terminalTypes=ONEME` → `attrs['terminal_type'] = 'ONEME'`.
- `userActiveStatus=1` → `attrs['user_active_status'] = '1'`.
- `queryString`: ORG_HASH (4 значения), GENDER=0, USER_DEVICE_TYPE=ANDROID → см. SQL.
- `minutes=0` → в песочнице: **`event_time >= now() - INTERVAL 90 DAY`** (ограничение скана; в проде уточнить семантику «0»).
## SQL (как выполнялся)
```sql
WITH filtered AS (
  SELECT user_id, event_time
  FROM default.events_raw
  WHERE record_type = 'REGISTRATION'
    AND attrs['terminal_type'] = 'ONEME'
    AND attrs['user_active_status'] = '1'
    AND attrs['gender'] = '0'
    AND attrs['user_device_type'] = 'ANDROID'
    AND attrs['org_hash'] IN (
      '-7207924629501764854',
      '-4231934642844376041',
      '1136490941434531770',
      '4369003663347448391'
    )
    AND event_time >= now() - INTERVAL 90 DAY
),
top_users AS (
  SELECT user_id
  FROM filtered
  GROUP BY user_id
  ORDER BY count() DESC
  LIMIT 50
)
SELECT
  f.user_id,
  toStartOfHour(f.event_time) AS ts,
  count() AS events
FROM filtered AS f
WHERE f.user_id IN (SELECT user_id FROM top_users)
GROUP BY f.user_id, ts
ORDER BY events DESC
LIMIT 5000
FORMAT Null
```
## Методика
- **Idle:** 10 последовательных прогонов, между запросами пауза ~0.35 с; каждый с уникальным `query_id` для `system.query_log`.
- **Нагрузка:** 4 потока `save-batch`, batch **2000**, sleep **0** (как в phase2), прогрев **3 с**, затем 10 прогонов того же запроса.
- **Латентность:** время до завершения HTTP-запроса (клиент), плюс `query_duration_ms` из лога (сервер) — ниже.
- **read_rows / read_bytes:** из `system.query_log` (`type = 'QueryFinish'`) по `query_id`.
## Результаты: латентность (клиент, мс)
| Фаза | avg | p50 | p95 |
|---|---:|---:|---:|
| Idle | 165.72 | 163.84 | 175.47 |
| Под записью | 522.87 | 526.98 | 644.92 |
## Результаты: `query_duration_ms` из `system.query_log` (мс)
| Фаза | avg | p50 | p95 |
|---|---:|---:|---:|
| Idle | 161.5 | 159.0 | 172.0 |
| Под записью | 508.4 | 509.0 | 630.0 |
## Результаты: read_rows / read_bytes (`system.query_log`)
| Фаза | read_rows (avg) | read_bytes (avg) |
|---|---:|---:|
| Idle | 1257087 | 427779659 |
| Под записью | 1271272 | 429964945 |

Запись за окно нагрузки: **244000** строк, ошибок батчей: **0**.

## Приложение: прогоны по штучно

### Idle

| # | client_ms | read_rows | read_bytes | query_duration_ms |
|---:|---:|---:|---:|---:|
| 1 | 161.05 | 1257087 | 427779659 | 157 |
| 2 | 163.84 | 1257087 | 427779659 | 159 |
| 3 | 171.41 | 1257087 | 427779659 | 167 |
| 4 | 175.47 | 1257087 | 427779659 | 172 |
| 5 | 161.06 | 1257087 | 427779659 | 157 |
| 6 | 163.84 | 1257087 | 427779659 | 159 |
| 7 | 166.72 | 1257087 | 427779659 | 162 |
| 8 | 162.58 | 1257087 | 427779659 | 159 |
| 9 | 156.05 | 1257087 | 427779659 | 152 |
| 10 | 175.12 | 1257087 | 427779659 | 171 |

### Под записью

| # | client_ms | read_rows | read_bytes | query_duration_ms |
|---:|---:|---:|---:|---:|
| 1 | 469.52 | 1271272 | 429964945 | 457 |
| 2 | 512.95 | 1271272 | 429964945 | 499 |
| 3 | 644.92 | 1271272 | 429964945 | 630 |
| 4 | 518.1 | 1271272 | 429964945 | 502 |
| 5 | 530.75 | 1271272 | 429964945 | 509 |
| 6 | 555.64 | 1271272 | 429964945 | 545 |
| 7 | 493.85 | 1271272 | 429964945 | 480 |
| 8 | 447.99 | 1271272 | 429964945 | 433 |
| 9 | 528.0 | 1271272 | 429964945 | 513 |
| 10 | 526.98 | 1271272 | 429964945 | 516 |

## EXPLAIN indexes=1
### Idle (до нагрузочных прогонов)
```
CreatingSets (Create sets before main query execution)
  Expression (Project names)
    Limit (preliminary LIMIT)
      Sorting (Sorting for ORDER BY)
        Expression ((Before ORDER BY + Projection))
          Aggregating
            Expression (Before GROUP BY)
              Expression (((WHERE + (Change column names to column identifiers + (Project names + Projection))) + (WHERE + Change column names to column identifiers)))
                ReadFromMergeTree (default.events_raw)
                Indexes:
                  MinMax
                    Keys:
                      event_time
                    Condition: (event_time in [1767646452, +Inf))
                    Parts: 95/95
                    Granules: 334/334
                  Partition
                    Keys:
                      toDate(event_time)
                    Condition: (toDate(event_time) in [20458, +Inf))
                    Parts: 95/95
                    Granules: 334/334
                  PrimaryKey
                    Keys:
                      user_id
                      record_type
                      event_time
                    Condition: and((user_id in 50-element set), and((event_time in [1767646452, +Inf)), (record_type in ['REGISTRATION', 'REGISTRATION'])))
                    Parts: 91/95
                    Granules: 92/334
                    Search Algorithm: generic exclusion search
                  Ranges: 91

```
### Под нагрузкой записи (~3 с после старта 4 потоков)
```
CreatingSets (Create sets before main query execution)
  Expression (Project names)
    Limit (preliminary LIMIT)
      Sorting (Sorting for ORDER BY)
        Expression ((Before ORDER BY + Projection))
          Aggregating
            Expression (Before GROUP BY)
              Expression (((WHERE + (Change column names to column identifiers + (Project names + Projection))) + (WHERE + Change column names to column identifiers)))
                ReadFromMergeTree (default.events_raw)
                Indexes:
                  MinMax
                    Keys:
                      event_time
                    Condition: (event_time in [1767646457, +Inf))
                    Parts: 97/97
                    Granules: 340/340
                  Partition
                    Keys:
                      toDate(event_time)
                    Condition: (toDate(event_time) in [20458, +Inf))
                    Parts: 97/97
                    Granules: 340/340
                  PrimaryKey
                    Keys:
                      user_id
                      record_type
                      event_time
                    Condition: and((user_id in 50-element set), and((event_time in [1767646457, +Inf)), (record_type in ['REGISTRATION', 'REGISTRATION'])))
                    Parts: 91/97
                    Granules: 92/340
                    Search Algorithm: generic exclusion search
                  Ranges: 91

```
## Заметки
- В синтетических данных прототипа ключи `attrs['org_hash']`, `terminal_type` и т.д. могут почти не встречаться: план и объёмы чтения отражают **структуру запроса**, а не полноту прод-атрибутов.
- Для сопоставления с продом имеет смысл повторить эксперимент на дампе/реплике с реальным распределением `attrs`.
