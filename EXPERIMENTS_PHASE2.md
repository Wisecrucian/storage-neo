# EXPERIMENTS PHASE 2

Формат: **гипотеза → метод → результат → вывод**.

_Сгенерировано: 2026-04-03T16:06:24Z_

---

## Exp1 — Read degradation vs write load

**Гипотеза:** при росте потока записи latency чтений растёт из‑за конкуренции за CPU/IO и фоновых merges.

**Метод:** калибровка 1–4 writer-потоков (batch=2000 → Kafka); для целевых уровней стабилизация ~35 с, измерение фактического RPS за 10 с; параллельно point-read и OLAP-read; snapshot parts + compression ratio.

### Результаты

| Target rec/s | Threads | Achieved rec/s | Point p50/p95/p99 ms | OLAP p50/p95 ms | Parts | Ratio |
|---:|---:|---:|---|---|---:|---:|
| 0 | 0 | 0 | 30.01/70.99/84.61 | 192.49/267.72 | 29 | 9.15 |
| 5000 | 1 | 15400.0 | 64.29/91.28/97.82 | 446.27/864.85 | 25 | 9.049 |
| 10000 | 1 | 15200.0 | 101.45/221.02/849.85 | 633.64/916.32 | 22 | 8.883 |
| 15000 | 1 | 15600.0 | 66.2/130.22/132.97 | 486.56/781.37 | 30 | 8.778 |
| 20000 | 3 | 21000.0 | 85.04/148.83/154.19 | 419.87/500.83 | 31 | 8.656 |

**Вывод:** зависимость point vs OLAP от нагрузки записи — по таблице; рост parts или падение ratio указывает на давление merge.

---

## Exp2 — Parts timeline (sustained write + recovery)

**Гипотеза:** длительная высокая запись увеличивает число активных parts и может ухудшить ratio до фона merge; idle снижает parts.

**Метод:** 4 потока × 5 мин; замер каждые 10 с; затем 2 мин без записи.

### Результаты (фрагмент)

| t | Parts | Ratio | Rows |
|:---|---:|---:|---:|
| … | 51 | 8.512 | 37991512 |
| … | 59 | 8.498 | 38120902 |
| … | 48 | 8.477 | 38330582 |
| … | 54 | 8.457 | 38526884 |
| … | 55 | 8.436 | 38745066 |
| … | 46 | 8.408 | 39006702 |
| … | 50 | 8.383 | 39268338 |
| … | 45 | 8.357 | 39529974 |
| … | 61 | 8.309 | 40053246 |
| … | 59 | 8.285 | 40314882 |

**Вывод:** сравните пик parts в write phase vs idle phase.

---

## Exp3 — Projection cost on inserts

**Гипотеза:** проекция увеличивает стоимость ingest/merge-path, снижая sustained throughput.

**Метод:** DROP PROJECTION → 30 с write (4 threads, batch 2000) → ADD + MATERIALIZE PROJECTION.

### Результат

{
  "with_projection": {
    "throughput_rec_s": 20805.0,
    "records_ok": 632000,
    "http_errors": 0
  },
  "without_projection": {
    "throughput_rec_s": 20241.0,
    "records_ok": 614000,
    "http_errors": 0
  }
}

**Вывод:** сравните throughput_rec_s with vs without.

---

## Exp4 — Map vs materialized columns

**Гипотеза:** materialized columns ускоряют типовые GROUP BY / фильтры относительно доступа к Map.

**Метод:** по 10 прогонов каждого запроса; добавлен `attr_source` как MATERIALIZED.

### Результат

{
  "group_lang_map": {
    "avg_ms": 760.05,
    "p50_ms": 657.01,
    "p95_ms": 1453.12,
    "p99_ms": 1453.12
  },
  "group_lang_mat": {
    "avg_ms": 79.18,
    "p50_ms": 77.96,
    "p95_ms": 105.19,
    "p99_ms": 105.19
  },
  "group_source_map": {
    "avg_ms": 656.67,
    "p50_ms": 627.11,
    "p95_ms": 903.18,
    "p99_ms": 903.18
  },
  "group_source_mat": {
    "avg_ms": 430.03,
    "p50_ms": 458.14,
    "p95_ms": 518.03,
    "p99_ms": 518.03
  },
  "filter_lang_map": {
    "avg_ms": 609.25,
    "p50_ms": 602.65,
    "p95_ms": 738.59,
    "p99_ms": 738.59
  },
  "filter_lang_mat": {
    "avg_ms": 75.49,
    "p50_ms": 77.4,
    "p95_ms": 124.18,
    "p99_ms": 124.18
  }
}

**Вывод:** сравните avg_ms / p95_ms для map vs mat пар.

---

## Exp5 — Bloom filter on chat_id

**Гипотеза:** bloom skip index снижает число гранул и время для предиката по chat_id.

**Метод:** 10× latency; EXPLAIN indexes=1; DROP INDEX → повтор → восстановить + MATERIALIZE.

### Результат

{
  "chat_id": 18,
  "with_bloom": {
    "avg_ms": 64.26,
    "p50_ms": 62.45,
    "p95_ms": 84.43,
    "p99_ms": 84.43,
    "explain_excerpt": "Expression ((Project names + Projection))\n  Aggregating\n    Expression (Before GROUP BY)\n      Filter ((WHERE + Change column names to column identifiers))\n        ReadFromMergeTree (default.events_raw)\n        Indexes:\n          MinMax\n            Condition: true\n            Parts: 25/25\n            Granules: 5256/5256\n          Partition\n            Condition: true\n            Parts: 25/25\n            Granules: 5256/5256\n          PrimaryKey\n            Condition: true\n            Parts: 25/25\n            Granules: 5256/5256\n          Skip\n            Name: idx_chat_id\n            Description: bloom_filter GRANULARITY 4\n            Parts: 24/25\n            Granules: 2830/5256\n          Ranges: 117\n"
  },
  "without_bloom": {
    "avg_ms": 57.61,
    "p50_ms": 54.65,
    "p95_ms": 77.33,
    "p99_ms": 77.33,
    "explain_excerpt": "Expression ((Project names + Projection))\n  Aggregating\n    Expression (Before GROUP BY)\n      Filter ((WHERE + Change column names to column identifiers))\n        ReadFromMergeTree (default.events_raw)\n        Indexes:\n          MinMax\n            Condition: true\n            Parts: 25/25\n            Granules: 5256/5256\n          Partition\n            Condition: true\n            Parts: 25/25\n            Granules: 5256/5256\n          PrimaryKey\n            Condition: true\n            Parts: 25/25\n            Granules: 5256/5256\n          Ranges: 25\n"
  }
}

**Вывод:** сравните latency и текст EXPLAIN (Granules).

---

## Exp6 — Kafka path vs direct INSERT

**Гипотеза:** буферизация Kafka даёт иной throughput/latency профиль, чем синхронный bulk INSERT в MergeTree.

**Метод:** 30 с Kafka (Java API); 30 с прямой INSERT SELECT numbers(N).

### Результат

{
  "kafka_java_api": {
    "throughput_rec_s": 19012.0,
    "records": 582000,
    "http_errors": 0
  },
  "direct_http_insert_ch": {
    "throughput_rec_s": 380000.0,
    "rows_inserted_approx": 11400000,
    "note": "INSERT SELECT into events_raw, bypass Kafka; not identical to legacy Java INSERT"
  }
}

**Вывод:** прямой INSERT часто выше RPS на CH-native пути, но не эквивалентен продуктовому Java INSERT; Kafka путь отделяет backpressure.
