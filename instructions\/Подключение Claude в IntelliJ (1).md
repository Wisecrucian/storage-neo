# INSTRUCTIONS — Два оставшихся фикса

## 1. Создать clickhouse/init.d/01-schema.sql

Создай файл `clickhouse/init.d/01-schema.sql` с содержимым:

```sql
CREATE DATABASE IF NOT EXISTS default;

DROP TABLE IF EXISTS default.events_raw;

CREATE TABLE IF NOT EXISTS default.events_raw
(
    event_time    DateTime                CODEC(DoubleDelta, LZ4),
    ingest_time   DateTime                CODEC(DoubleDelta, LZ4),
    record_type   LowCardinality(String)  CODEC(ZSTD(1)),
    event_id      UUID                    CODEC(LZ4),
    user_id       UInt64 DEFAULT 0        CODEC(T64, LZ4),
    chat_id       UInt64 DEFAULT 0        CODEC(T64, LZ4),
    message_id    UInt64 DEFAULT 0        CODEC(T64, LZ4),
    attrs         Map(String, String)     CODEC(ZSTD(3)),
    attr_lang     LowCardinality(String)  MATERIALIZED attrs['lang'],
    attr_source   LowCardinality(String)  MATERIALIZED attrs['source'],
    attr_score    Int32                   MATERIALIZED toInt32OrZero(attrs['moderation_score'])
)
ENGINE = MergeTree
PARTITION BY toDate(event_time)
ORDER BY (user_id, record_type, event_time)
SETTINGS index_granularity = 8192, min_bytes_for_wide_part = 0, min_rows_for_wide_part = 0;

ALTER TABLE default.events_raw
    ADD PROJECTION IF NOT EXISTS by_record_type
    (SELECT * ORDER BY (record_type, event_time));

ALTER TABLE default.events_raw
    ADD INDEX IF NOT EXISTS idx_chat_id chat_id TYPE bloom_filter GRANULARITY 4;

DROP TABLE IF EXISTS default.events_raw_kafka;

CREATE TABLE IF NOT EXISTS default.events_raw_kafka
(
    event_time   DateTime,
    ingest_time  DateTime,
    record_type  String,
    event_id     String,
    user_id      UInt64,
    chat_id      UInt64,
    message_id   UInt64,
    attrs        Map(String, String)
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list   = 'host.docker.internal:9092',
    kafka_topic_list    = 'events.raw',
    kafka_group_name    = 'clickhouse-events-consumer',
    kafka_format        = 'JSONEachRow',
    kafka_num_consumers = 1,
    kafka_skip_broken_messages = 10;

DROP VIEW IF EXISTS default.events_raw_mv;

CREATE MATERIALIZED VIEW IF NOT EXISTS default.events_raw_mv
TO default.events_raw
AS SELECT
    event_time,
    ingest_time,
    toLowCardinality(record_type)   AS record_type,
    toUUID(event_id)                AS event_id,
    user_id,
    chat_id,
    message_id,
    attrs
FROM default.events_raw_kafka;
```

## 2. Исправить Makefile

В файле `Makefile` заменить:
```
APP_DIR = event-storage-service
```
на:
```
APP_DIR = event-storage
```

## Проверка

После обоих изменений:
- `make infra-up` должен поднять Kafka + ClickHouse
- `make clickhouse-init` должен создать таблицы
- `cd event-storage && ./gradlew compileJava` должен компилироваться без ошибок
