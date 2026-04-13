# Experiments

## Scope
This file summarizes benchmark scenarios and key measured outcomes from the prototype.

Primary sources:
- `BASELINE_PHASE1.md`
- `SCALING_RESEARCH.md`

---

## 1) Baseline Mixed Workload (early state)
Scenario:
- 2 writer threads,
- batch size 500,
- 2 point-read threads,
- 1 analytics thread,
- duration: 2 minutes.

Results:
- Write throughput: **2,548 rec/s**
- Write flush latency: p50 **172 ms**, p95 **281 ms**, p99 **566 ms**
- Point reads latency: p50 **805 ms**, p95 **910 ms**
- Analytics latency: avg **~7.7 s**, max **25.3 s**

Conclusion:
- point-read path was too expensive for target moderation workload.

---

## 2) ORDER BY + Projection Refactor
Problem (before):
- table ordered as `(record_type, event_time, user_id)`,
- point query `user_id + record_type` read too many granules.

Change:
- reordered main table to `(user_id, record_type, event_time)`,
- added projection `by_record_type (record_type, event_time)` for analytics.

Measured effect:
- `WHERE user_id=X AND record_type=Y`: **643/3043 -> 9/178 granules**
- Point-read latency: **~805 ms -> ~191 ms p50**

Conclusion:
- main key now matches primary operational access pattern,
- analytics preserved via projection.

---

## 3) Kafka in Write Path (decoupling)
Problem (before):
- Java path depended on direct ClickHouse insert timing.

Change:
- Java publishes to Kafka,
- ClickHouse ingests via Kafka Engine + Materialized View.

Measured effect:
- Write throughput: **~2.5k -> ~22k rec/s**
- Write p50 latency (app-side): **~172 ms -> ~74 ms**

Conclusion:
- major throughput gain from decoupling API and storage internals.

---

## 4) Throughput Tuning (batch size)
Environment: single Java pod, Docker on Mac, 8 threads, 30s runs.

| Batch | Throughput | p50 latency | p95 latency | Kafka lag |
|---|---:|---:|---:|---:|
| 500  | 14,883 rec/s | 208 ms | 619 ms | ~135k |
| 1000 | 20,133 rec/s | 314 ms | 776 ms | ~127k |
| 2000 | 20,467 rec/s | 502 ms | 1,704 ms | 0 |
| 5000 | 22,333 rec/s | 789 ms | 4,101 ms | ~132k |

Takeaway:
- batch `2000` gave best balance (high throughput + stable lag),
- very large batch raises tail latency and lag risk.

---

## 5) Throughput Tuning (thread count)
Environment: batch size 2000, 30s runs.

| Threads | Throughput | p50 latency | p95 latency |
|---:|---:|---:|---:|
| 1  | 16,800 rec/s | 115 ms | 162 ms |
| 2  | 17,133 rec/s | 183 ms | 532 ms |
| 4  | 22,200 rec/s | 312 ms | 608 ms |
| 8  | 21,667 rec/s | 382 ms | 1,762 ms |
| 16 | 20,000 rec/s | 611 ms | 4,959 ms |
| 32 | 21,067 rec/s | 1,949 ms | 8,834 ms |

Takeaway:
- practical optimum: **4-8 threads**,
- beyond that throughput plateaus, latency worsens.

---

## 6) ClickHouse Consumers Scaling
Test: `kafka_num_consumers = 1` vs `4`.

Results:
- consumers=1: ~21,667 rec/s
- consumers=4: ~17,267 rec/s (on same host)

Interpretation:
- bottleneck was producer side (Java), not ClickHouse consumption.
- adding consumers without producer scaling can hurt on shared CPU.

---

## 7) UUID v4 vs UUID v7 (compression experiment)
Setup:
- two benchmark tables with identical schema,
- 5M rows each,
- `event_id` generated as v4 vs v7.

Results (`event_id` column):
- v4: compressed **78.38 MiB**, ratio **1.7x**
- v7: compressed **40.03 MiB**, ratio **3.34x**

Conclusion:
- UUID v7 reduced compressed size for event_id by ~49%.

---

## 8) Data Skipping and Hot Attributes
### Bloom filter on `chat_id`
- EXPLAIN granules reduced: **246 -> 80** for tested predicate.

### Materialized `attr_lang`
- `GROUP BY attrs['lang']`: ~136 ms
- `GROUP BY attr_lang`: ~67 ms
- speedup: roughly **2x** on that workload.

---

## 9) Projection Cost vs Benefit (current bench snapshot)
Projection `by_record_type`:
- compressed size: **392.83 MiB**
- corresponding base parts compressed size: **277.26 MiB**
- storage overhead: about **+42%** for covered data.

OLAP query test (`record_type + event_time`):
- with projection: avg **64.8 ms**
- without projection: avg **86.3 ms**
- speedup: about **1.33x**

Interpretation:
- projection adds storage overhead, but cuts read work and query latency for target analytics pattern.

---

## 10) Scenarios used in testing
- Synthetic writes with realistic record-type distribution.
- Mixed workload: concurrent writes + point reads + analytics reads.
- Isolated experiments:
  - batch/thread sweeps,
  - consumer-count sweeps,
  - compression A/B (UUID v4/v7),
  - skip index and materialized-column impact.

---

## 11) Current scaling model (from research)
- Single Java pod local ceiling: ~22k rec/s (Mac Docker).
- Estimated per-pod Linux ceiling: ~35-50k rec/s.
- Path to 50-100k rec/s:
  - 2-4 Java pods,
  - 8-16 Kafka partitions,
  - 2-4 ClickHouse Kafka consumers (aligned with partitions and host capacity).
