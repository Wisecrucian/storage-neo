-- Kafka Engine table: читает сообщения из топика events.raw
-- Java публикует каждую запись как отдельный JSON-объект (JSONEachRow format)
DROP TABLE IF EXISTS default.events_raw_kafka;

CREATE TABLE IF NOT EXISTS default.events_raw_kafka
(
    event_time   DateTime,
    ingest_time  DateTime,
    record_type  String,
    event_id     String,           -- UUID приходит как строка, конвертируем в MV
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

-- Materialized View: перекладывает из Kafka Engine в основную таблицу
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
