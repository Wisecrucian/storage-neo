CREATE DATABASE IF NOT EXISTS default;

DROP VIEW IF EXISTS default.events_mv;
DROP TABLE IF EXISTS default.events_kafka;
DROP TABLE IF EXISTS default.events_storage;

CREATE TABLE default.events_storage
(
    event_id String,
    event_type String,
    user_id String,
    session_id Nullable(String),
    order_id Nullable(String),
    action Nullable(String),
    amount Nullable(Float64),
    ip_address Nullable(String),
    page_url Nullable(String),
    referrer Nullable(String),
    message String,
    timestamp DateTime,
    created_at DateTime DEFAULT now()
)
ENGINE = MergeTree
ORDER BY (timestamp, event_type, user_id);

CREATE TABLE default.events_kafka
(
    message String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka-kafka-1:29092',
    kafka_topic_list = 'events',
    kafka_group_name = 'clickhouse_consumer_group',
    kafka_format = 'JSONAsString',
    kafka_skip_broken_messages = 1,
    kafka_num_consumers = 1;

CREATE MATERIALIZED VIEW default.events_mv
TO default.events_storage
AS
SELECT
    if(JSONHas(message, 'eventId'), JSONExtractString(message, 'eventId'), toString(generateUUIDv4())) AS event_id,
    JSONExtractString(message, 'eventType') AS event_type,
    JSONExtractString(message, 'userId') AS user_id,
    if(JSONHas(message, 'sessionId'), JSONExtractString(message, 'sessionId'), NULL) AS session_id,
    if(JSONHas(message, 'orderId'), JSONExtractString(message, 'orderId'), NULL) AS order_id,
    if(JSONHas(message, 'action'), JSONExtractString(message, 'action'), NULL) AS action,
    if(JSONHas(message, 'amount'), toFloat64OrNull(JSONExtractString(message, 'amount')), NULL) AS amount,
    if(JSONHas(message, 'ipAddress'), JSONExtractString(message, 'ipAddress'), NULL) AS ip_address,
    if(JSONHas(message, 'pageUrl'), JSONExtractString(message, 'pageUrl'), NULL) AS page_url,
    if(JSONHas(message, 'referrer'), JSONExtractString(message, 'referrer'), NULL) AS referrer,
    message AS message,
    if(JSONHas(message, 'timestamp'), parseDateTimeBestEffortOrNull(JSONExtractString(message, 'timestamp')), now()) AS timestamp
FROM default.events_kafka
WHERE (message != '') AND isValidJSON(message);
