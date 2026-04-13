# Baseline — Фаза 1: Текущее состояние системы

> Дата снятия: 2026-03-27  
> Тест: 2-минутный mixed workload (2 writer threads × 500 rec/batch, 2 point-read threads, 1 analytics thread)

---

## 1. Схема таблицы `default.events_raw`

```sql
CREATE TABLE default.events_raw
(
    event_time   DateTime               CODEC(DoubleDelta, LZ4),
    ingest_time  DateTime               CODEC(DoubleDelta, LZ4),
    record_type  LowCardinality(String) CODEC(ZSTD(1)),
    event_id     UUID                   CODEC(LZ4),
    user_id      UInt64 DEFAULT 0       CODEC(T64, LZ4),
    chat_id      UInt64 DEFAULT 0       CODEC(T64, LZ4),
    message_id   UInt64 DEFAULT 0       CODEC(T64, LZ4),
    attrs        Map(String, String)    CODEC(ZSTD(3))
)
ENGINE = MergeTree
PARTITION BY toDate(event_time)
ORDER BY (record_type, event_time, user_id)
SETTINGS index_granularity = 8192,
         min_bytes_for_wide_part = 0,
         min_rows_for_wide_part = 0;

-- Проекция для OLTP-чтений по user_id
ALTER TABLE default.events_raw
    ADD PROJECTION IF NOT EXISTS by_user
    (SELECT * ORDER BY (user_id, event_time));
```

### Горячие атрибуты (materialized columns, application.yml)

| Java name  | attrs key | ClickHouse type        | Expression                     |
|------------|-----------|------------------------|--------------------------------|
| attr_lang  | lang      | LowCardinality(String) | attrs['lang']                  |
| attr_source| source    | LowCardinality(String) | attrs['source']                |
| attr_score | score     | Int32                  | toInt32OrZero(attrs['score'])  |

---

## 2. Объём данных

| Параметр              | Значение          |
|-----------------------|-------------------|
| Всего строк           | 24 879 500 (~24.9M)|
| Партиции              | 2026-03-26, 2026-03-27 |
| Активных parts        | 13–14             |
| Compressed (on disk)  | **1.21 GiB**      |
| Uncompressed (raw)    | **8.94 GiB**      |
| **Общий ratio**       | **7.43×**         |

### По проекции `by_user`

| Параметр         | Значение  |
|------------------|-----------|
| Compressed       | 1.74 GiB  |
| Uncompressed     | 8.79 GiB  |
| Ratio            | 5.09×     |
| Rows             | 24 879 500|

---

## 3. Сжатие по колонкам

| Колонка      | Тип                    | Codec              | Compressed | Raw      | Ratio | % диска |
|--------------|------------------------|--------------------|-----------|----------|-------|---------|
| attrs        | Map(String, String)    | ZSTD(3)            | 524 MiB   | 7.02 GiB | 13.71×| 45.9%   |
| event_id     | UUID                   | LZ4                | 376 MiB   | 375 MiB  | 1.00× | 33.0%   |
| message_id   | UInt64                 | T64, LZ4           | 74.5 MiB  | 187 MiB  | 2.51× | 6.5%    |
| chat_id      | UInt64                 | T64, LZ4           | 58.2 MiB  | 187 MiB  | 3.22× | 5.1%    |
| user_id      | UInt64                 | T64, LZ4           | 58.2 MiB  | 187 MiB  | 3.22× | 5.1%    |
| attr_score   | Int32                  | (default)          | 26.7 MiB  | 93.7 MiB | 3.51× | 2.3%    |
| ingest_time  | DateTime               | DoubleDelta, LZ4   | 11.1 MiB  | 93.7 MiB | 8.45× | 1.0%    |
| attr_source  | LowCardinality(String) | ZSTD(1)            | 9.1 MiB   | 23.5 MiB | 2.57× | 0.8%    |
| event_time   | DateTime               | DoubleDelta, LZ4   | 2.66 MiB  | 93.7 MiB | **35.28×**| 0.2%|
| attr_lang    | LowCardinality(String) | ZSTD(1)            | 567 KiB   | 23.5 MiB | 42.39×| ~0%     |
| record_type  | LowCardinality(String) | ZSTD(1)            | 23 KiB    | 23.5 MiB |**1027×**| ~0%  |

### Выводы по сжатию

- **`record_type` и `attr_lang`** — рекордные ratio (1027× и 42×): LowCardinality с малым числом значений (5 и ~6) работает как словарное кодирование + LZ4 на почти пустые данные.
- **`event_time`** (35×): DoubleDelta отлично работает на монотонно возрастающих временных метках с одинаковыми дельтами (секунды инсерта).
- **`attrs`** (13.7×): ZSTD(3) хорошо жмёт повторяющиеся ключи JSON/Map через словарь.
- **`event_id`** (UUID, 1×): случайные 128-бит UUID несжимаемы по определению. Занимает 33% диска при ~24.9M строк. Это известный trade-off UUID vs SequentialID.
- **`message_id/chat_id/user_id`** (2.5–3.2×): T64 эффективен для целых чисел с разным диапазоном, но данные генерируются рандомно → нет монотонности → ratio невысокий.

---

## 4. Производительность (mixed workload, 2 мин)

### Write

| Метрика               | Значение         |
|-----------------------|------------------|
| Итого записей         | 309 000          |
| Средний throughput    | **2 548 rec/s**  |
| Batches успешно       | 618              |
| Ошибок записи         | 0                |
| Batch size (p50/p95)  | 496 / 496        |
| Flush latency p50     | **172 ms**       |
| Flush latency p95     | **281 ms**       |
| Flush latency p99     | **566 ms**       |
| Flush latency max     | 2 035 ms         |

### Read — точечные (moderation, по user_id/chat_id/message_id)

| Метрика           | Значение      |
|-------------------|---------------|
| Запросов          | 208           |
| Throughput        | 1.7 q/s       |
| Latency p50       | **805 ms**    |
| Latency p95       | **910 ms**    |
| Avg latency       | ~1 075 ms     |

### Read — аналитические

| Метрика           | Значение      |
|-------------------|---------------|
| Запросов          | 15            |
| Throughput        | 0.1 q/s       |
| Latency avg       | ~7.7 s        |
| Latency max       | 25.3 s        |

### Распределение типов записей (за тест)

| record_type  | Кол-во  | ~%   |
|--------------|---------|------|
| MESSAGE      | 138 890 | 45%  |
| MODERATION   | 62 097  | 20%  |
| PURCHASE     | 46 507  | 15%  |
| SYSTEM       | 37 040  | 12%  |
| OTHER        | 24 466  | 8%   |

---

## 5. Оптимизация ORDER BY — Фаза 1, шаг 2

### Проблема (исходная схема)

Таблица имела `ORDER BY (record_type, event_time, user_id)` — аналитический порядок.  
Следствие: запрос `WHERE user_id=X AND record_type=Y` читал **643/3043 гранулы (21%)**.  
Проекция `by_user (user_id, event_time)` не выбиралась при наличии фильтра `record_type`,  
так как оптимизатор ClickHouse видел оба условия в составном индексе и предпочитал main table.

### Решение

Схема пересоздана с приоритетом OLTP:

```sql
ORDER BY (user_id, record_type, event_time)  -- был: (record_type, event_time, user_id)

-- Теперь аналитика обслуживается через проекцию:
ADD PROJECTION by_record_type (SELECT * ORDER BY (record_type, event_time))
```

### EXPLAIN до/после

| Запрос | Было | Стало |
|--------|------|-------|
| `WHERE user_id=X AND record_type=Y` | 643/3043 гранул (21%) — main table | **9/178 гранул (5%)** — main index |
| `WHERE user_id=X` | 13/3043 гранул — projection by_user | **10/179 гранул** — main index |
| `WHERE record_type=Y AND event_time >= ...` | full scan 3043 гранул | **85/179 гранул** — projection by_record_type |

### Ключевой вывод для диплома

> **Выбор первого ключа `ORDER BY` определяет основной паттерн доступа**.  
> OLTP-система (поиск по пользователю) требует `user_id` первым ключом.  
> OLAP-агрегации по `record_type` вынесены в отдельную проекцию — без потерь для аналитики.  
> Проекция — это "вторичный индекс" ClickHouse с полной копией данных в другом порядке сортировки.

## 6. Замечания и план улучшений (после рефакторинга)

1. **`event_id` (UUID) — 33% диска**: случайные UUID несжимаемы.  
   → Эксперимент фазы 2: UUID v7 (time-ordered) для улучшения сжатия.

2. **`attrs` — ~46% диска**: Map жмётся в 13.7×, но всё равно основная доля.  
   → Hot-attributes механизм уже реализован (attr_lang, attr_source, attr_score).

3. **Аналитика через проекцию by_record_type** — работает, читает ~47% гранул.  
   → Для более широких аналитических запросов (без record_type фильтра) нужен отдельный тест.

---

## 6. Параметры окружения (baseline)

| Параметр             | Значение               |
|----------------------|------------------------|
| ClickHouse           | 26.2.5.45 (single node)|
| Java app             | Spring Boot + Micrometer|
| Batch size           | 500 records            |
| Write threads        | 2                      |
| ClickHouse host      | Docker (Mac, localhost) |
| `index_granularity`  | 8192                   |
| Part format          | Wide (forced)          |
| Projection           | by_user (user_id, event_time) |
