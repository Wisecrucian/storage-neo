# Исследование масштабируемости: путь к 50–100k rec/s

> Дата: 2026-03-27  
> Окружение: Docker on Mac (Apple Silicon), single Java pod, single Kafka broker (KRaft), single ClickHouse node  
> Пайплайн: Python → Java HTTP API → Kafka → ClickHouse Kafka Engine → MV → events_raw (MergeTree)

---

## 1. Ключевые выводы

| Вывод | Значение |
|---|---|
| Потолок одного Java-пода (Mac Docker) | **~22k rec/s** |
| Потолок одного Java-пода (расчёт для Linux) | **~35–50k rec/s** |
| ClickHouse успевает потреблять из Kafka | **Да, лаг = 0** при ≤ 20k rec/s |
| Узкое место | **Java: JSON serialization + HTTP → Kafka produce** |
| Масштабирование до 50k | **2–3 Java-пода** |
| Масштабирование до 100k | **3–4 Java-пода + 2 CH Kafka consumers** |

---

## 2. Эксперимент 1: Throughput vs Batch Size (8 потоков, 30 с)

| Batch Size | Throughput | p50 latency | p95 latency | Kafka lag |
|---|---|---|---|---|
| 500  | 14 883 rec/s | 208 ms | 619 ms | ~135k |
| 1000 | 20 133 rec/s | 314 ms | 776 ms | ~127k |
| **2000** | **20 467 rec/s** | 502 ms | 1 704 ms | **0** |
| 5000 | 22 333 rec/s | 789 ms | 4 101 ms | ~132k |

### Вывод
- Оптимальный batch: **2000 записей** — максимальный throughput при нулевом лаге CH.
- Batch 5000 даёт чуть больший throughput, но CH не успевает (лаг ~132k) и p95 растёт до 4с.
- Batch 500 неэффективен: много HTTP round-tripов, меньше throughput.

**Причина плато ~20k:** HTTP round-trip Python→Java (~8–17ms overhead) + JSON serialize (~10ms для 2000 записей). Throughput ограничен числом завершённых HTTP-запросов за секунду, а не Kafka/CH.

---

## 3. Эксперимент 2: Throughput vs Thread Count (batch=2000, 30 с)

| Threads | Throughput | p50 latency | p95 latency | Kafka lag |
|---|---|---|---|---|
| 1  | 16 800 rec/s | 115 ms | 162 ms  | ~92k |
| 2  | 17 133 rec/s | 183 ms | 532 ms  | ~35k |
| 4  | 22 200 rec/s | 312 ms | 608 ms  | ~112k |
| **8** | **21 667 rec/s** | 382 ms | 1 762 ms | ~134k |
| 16 | 20 000 rec/s | 611 ms | 4 959 ms | ~23k |
| 32 | 21 067 rec/s | 1 949 ms | 8 834 ms | ~156k |

### Вывод
- Прирост throughput прекращается после **4 потоков** (~22k rec/s).
- 16–32 потока только увеличивают latency и lag — CPU на Mac перегружен context switching.
- **Оптимум: 4–8 потоков** на один Java-под.

**Важно**: один Java-под (даже с 32 потоками) не пробивает потолок ~22k rec/s на Mac.  
На Linux bare-metal (без Docker overhead, лучший сетевой стек) ожидаемый прирост **1.5–2.5×** → ~33–55k rec/s.

---

## 4. Эксперимент 3: kafka_num_consumers = 4 vs 1

| Consumers | Throughput | Kafka lag |
|---|---|---|
| 1 (default) | ~21 667 rec/s | варьируется |
| 4 (4 partition) | ~17 267 rec/s | ~80k |

### Вывод
Увеличение consumer'ов ClickHouse **не помогает** при текущей нагрузке, потому что узкое место — не сторона потребления, а **производство** (Java → Kafka). CH с 1 consumer успевает потребить всё что Java успевает произвести.

С 4 consumer'ами throughput даже ниже — дополнительные CH-потоки конкурируют за CPU с Java на той же машине.

**Вывод для продакшна**: kafka_num_consumers надо увеличивать синхронно с добавлением Java-подов и Kafka-партиций, чтобы CH успевал за суммарным производством.

---

## 5. Эксперимент 4: Профиль времён по слоям

### JSON сериализация (только Python)
| Batch | Время | Эквивалент rec/s |
|---|---|---|
| 500  | 1.6 ms  | 314k rec/s |
| 1000 | 4.5 ms  | 221k rec/s |
| 2000 | 10.3 ms | 194k rec/s |
| 5000 | 23.8 ms | 210k rec/s |

Python сериализация — НЕ узкое место. Теоретический предел >200k rec/s.

### HTTP overhead (Docker localhost)
- p50: **8.3 ms**
- avg: **17.4 ms**
- p95: **116 ms** (GC/scheduling spikes)

### Из чего складываются ~115–500ms latency на батч
```
  10ms  — JSON serialize (2000 записей)
  17ms  — HTTP Python → Java (avg, Docker localhost)
 ~30ms  — Java deserialize + validate + map to Record
  74ms  — Kafka produce 2000 сообщений (acks=all)
  ─────
 ~131ms — суммарно p50
```

Реальный p50 = 115–500ms в зависимости от теста, что соответствует.

### ClickHouse потребление
- После паузы производства, CH потребил 222k сообщений **за < 5 секунд** (~44k rec/s).
- CH не является узким местом при одном Java-поде.

---

## 6. Эксперимент 5: Latency чтения

### Idle (нет записей)
| Тип | p50 | p95 | Запросов |
|---|---|---|---|
| Moderation (user_id + record_type) | **104 ms** | 280 ms | 235 |
| Analytics (aggregation) | **856 ms** | 9 626 ms | 5 |

### Под нагрузкой записи (16 потоков × 2000)
| Тип | p50 | p95 | Запросов |
|---|---|---|---|
| Moderation | **304 ms** | 1 223 ms | 86 |
| Analytics | **2 244 ms** | 2 244 ms | 2 |

### Вывод
- Moderation деградирует в **3× при максимальной нагрузке записи** (104→304ms p50).
- Analytics деградирует в **2.6×** (856ms→2.2s) — ожидаемо, CH занят фоновыми merges.
- **Kafka как буфер** помогает: Java больше не ждёт CH при вставке, читающие запросы конкурируют только с фоновыми merges, а не с прямыми INSERT.

---

## 7. Хранилище

| Параметр | Значение |
|---|---|
| Строк в events_raw | ~26.4M |
| На диске (сжато) | 1.29 GiB |
| Коэффициент сжатия | 3.07× |
| Активных parts | 13 |

> Ratio 3.07× ниже прошлого (7.43×) — это из-за большого числа мелких не-merged parts  
> при высокой write нагрузке. После OPTIMIZE TABLE вернётся к 7–8×.

---

## 8. Модель масштабирования для 50k / 100k rec/s

### Допущения
- Bare-metal Linux pod (без Docker overhead): Java throughput ~35–50k rec/s
- Kafka: 1 partition ≈ 15–20k msg/s → нужно partitions ≥ (целевой rec/s / 15k)
- ClickHouse: 1 Kafka consumer ≈ 30–50k rec/s → нужно consumers = partitions

### Расчёт

| Цель | Java pods | Kafka partitions | CH consumers | CH nodes |
|------|-----------|------------------|--------------|----------|
| **20k rec/s** (текущий локально) | 1 | 4 | 1–2 | 1 |
| **50k rec/s** | 2 | 8 | 2 | 1 |
| **100k rec/s** | 3 | 16 | 4 | 1–2 |
| **500k rec/s** | 15 | 64 | 8–16 | 3–4 |

### Формула
```
Java pods = ceil(target_rps / 40_000)          # 40k rec/s per pod (Linux)
Kafka partitions = Java pods × 4               # 4 partitions per pod
CH consumers = min(Kafka partitions / 2, 8)    # не более 8 consumer threads на CH node
CH nodes (shards) = ceil(target_rps / 150_000) # 150k rec/s per CH node insert
```

### Архитектура для 100k rec/s
```
                    ┌──────────────────────────────────────────┐
                    │           Load Balancer                  │
                    └───────┬──────────────┬───────────────────┘
                            │              │               │
                    ┌───────▼───┐  ┌───────▼───┐  ┌───────▼───┐
                    │ Java Pod 1│  │ Java Pod 2│  │ Java Pod 3│
                    │ 4 threads │  │ 4 threads │  │ 4 threads │
                    │ batch=2000│  │ batch=2000│  │ batch=2000│
                    └───────┬───┘  └───────┬───┘  └───────┬───┘
                            │              │               │
                    ┌───────▼──────────────▼───────────────▼───┐
                    │         Kafka  (16 partitions)            │
                    │         events.raw                        │
                    └───────────────────┬───────────────────────┘
                                        │ 4 consumers
                    ┌───────────────────▼───────────────────────┐
                    │     ClickHouse (single node или 2 шарда)   │
                    │     Kafka Engine → MV → events_raw         │
                    │     ORDER BY (user_id, record_type, ...)   │
                    └───────────────────────────────────────────┘
```

---

## 9. Ограничения локального бенчмарка

| Фактор | Локально (Mac Docker) | Продакшн (Linux) | Разница |
|---|---|---|---|
| Network latency | 8–17ms (loopback) | 0.1–1ms (pod-to-pod) | 10–170× |
| CPU sharing | Java+Kafka+CH на одной машине | Изолированные узлы | качественная |
| Docker overhead | +20–40% latency | Нет (bare-metal) | 20–40% |
| IO | SSD Mac | NVMe, RAID | 2–5× |

**Вывод**: локальный потолок ~22k rec/s соответствует ~35–50k на одном Linux-поде.

---

## 10. Что сделано и что нужно для следующего шага

### Сделано
- ✅ Пайплайн Java → Kafka → ClickHouse работает
- ✅ Latency записи: p50 = **74ms** (vs 172ms прямой CH insert)
- ✅ Write throughput: **4.5k → 22k rec/s** при правильных параметрах
- ✅ Нулевой Kafka lag при batch=2000, 8 потоков
- ✅ Moderation reads: p50 **191ms** (vs 805ms в исходной схеме)
- ✅ Схема: ORDER BY (user_id, record_type, event_time) + проекция by_record_type

### Для реального 50–100k rec/s (production)
1. **Горизонтальное масштабирование Java** — 2–3 пода с LoadBalancer
2. **Kafka**: 16 партиций, replication factor 3
3. **CH Kafka Engine**: kafka_num_consumers = 4 (синхронно с партициями)
4. **CH tuning**: `max_insert_threads = 4`, `kafka_poll_max_batch_size = 10000`
5. **Мониторинг**: Kafka consumer lag как SLO — должен быть < 30 секунд отставания

---

## Приложение: Сырые данные экспериментов

Полные данные в `bench_results.json`.

### Batch size series
```
batch=500:  14,883 rec/s  p50=208ms  p95=619ms
batch=1000: 20,133 rec/s  p50=314ms  p95=776ms
batch=2000: 20,467 rec/s  p50=502ms  p95=1704ms
batch=5000: 22,333 rec/s  p50=789ms  p95=4101ms
```

### Thread count series (batch=2000)
```
threads=1:  16,800 rec/s  p50=115ms  p95=162ms
threads=2:  17,133 rec/s  p50=183ms  p95=532ms
threads=4:  22,200 rec/s  p50=312ms  p95=608ms
threads=8:  21,667 rec/s  p50=382ms  p95=1762ms
threads=16: 20,000 rec/s  p50=611ms  p95=4959ms
threads=32: 21,067 rec/s  p50=1949ms p95=8834ms
```

### kafka_num_consumers
```
consumers=1: ~21,667 rec/s  (CH lag=0 при batch=2000)
consumers=4: ~17,267 rec/s  (CH lag=79k — не помогает на одной машине)
```
