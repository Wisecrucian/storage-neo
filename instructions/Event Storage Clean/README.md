# Event Storage — ClickHouse + Kafka

Высоконагруженное событийное хранилище для антиспам-системы.
Замена Cassandra-кластера (384 ноды) на Kafka + ClickHouse.

## Быстрый старт

```bash
# 1. Поднять инфраструктуру
make infra-up        # Kafka + ClickHouse

# 2. Инициализировать схему (первый раз)
make clickhouse-init

# 3. Запустить приложение
cd event-storage && ./gradlew bootRun

# 4. Тест записи
curl -X POST http://localhost:8080/api/records/generate-and-save?count=1000

# 5. Проверить данные
make clickhouse-shell
# > SELECT count() FROM events_raw;
# > SELECT record_type, count() FROM events_raw GROUP BY record_type;
```

## Архитектура

```
Antispam System → HTTP POST /api/records/save-batch
                       │
                       ▼
              Storage Service (Java 17, Spring Boot)
                       │
                  Kafka produce (topic: events.raw)
                  key = entityId (partition ordering)
                       │
                       ▼
              Apache Kafka (KRaft)
                       │
                  batch consume
                       │
                       ▼
              ClickHouse
              ├─ events_raw_kafka (Kafka Engine)
              ├─ events_raw_mv (Materialized View)
              └─ events_raw (MergeTree)
                   ORDER BY (user_id, record_type, event_time)
                   PARTITION BY toDate(event_time)
                   Projection: by_record_type (record_type, event_time)
```

## Структура проекта

```
├── event-storage/              # Java приложение
│   ├── event-storage-server/   # Spring Boot сервис
│   │   └── src/main/java/one/idsstorage/
│   │       ├── domain/         # Модель данных
│   │       │   ├── StorageAttribute    — типизированный дескриптор атрибута
│   │       │   ├── UserActivityType    — 30 типов событий
│   │       │   ├── UserActivitySchema  — 55 атрибутов, маппинг type→attrs
│   │       │   ├── Record              — событие (StorageRecord-совместимый)
│   │       │   ├── RecordType          — категория (MESSAGE/MODERATION/...)
│   │       │   └── QueryFilter         — типизированный фильтр запросов
│   │       ├── clickhouse/     # ClickHouse интеграция
│   │       │   ├── ClickHouseClient          — HTTP клиент
│   │       │   ├── ClickHouseMapper          — JSON ↔ Record
│   │       │   ├── ClickHouseRecordRepository — Kafka write path
│   │       │   ├── ClickHouseRecordQueryService — SQL read path
│   │       │   └── HotAttributeManager       — materialized columns DDL
│   │       ├── controller/     # REST API
│   │       ├── service/        # Бизнес-логика
│   │       ├── repository/     # Интерфейсы
│   │       ├── metrics/        # Prometheus метрики
│   │       └── util/           # UuidV7 и др.
│   └── event-storage-client/   # SDK для внешних клиентов
├── kafka/                      # Docker Compose для Kafka
├── clickhouse/                 # Docker Compose + DDL для ClickHouse
├── experiments/                # Бенчмарки (Python)
└── Makefile                    # Оркестрация
```

## Модель данных

Каждое событие — `StorageRecord` с типизированными атрибутами:

- **Промотированные колонки** (участвуют в ORDER BY):
  `event_time`, `user_id`, `record_type`, `chat_id`, `message_id`, `event_id`

- **Динамические атрибуты** → `attrs Map(String, String)`:
  Все type-specific поля из `UserActivitySchema` (до 50 на запись)

- **Горячие атрибуты** → materialized columns:
  `attr_lang`, `attr_source`, `attr_score` — 8–15× ускорение GROUP BY

## API

| Endpoint | Метод | Описание |
|---|---|---|
| `/api/records/save-batch` | POST | Запись батча записей → Kafka |
| `/api/records/moderation` | GET | Point read по userId/chatId/recordType |
| `/api/records/analytics` | GET | Агрегация по времени и типу |
| `/api/records/analytics/run` | GET | Предопределённые аналитические сценарии |
| `/api/records/generate-and-save` | POST | Генерация тестовых данных по схеме |
| `/api/records/hot-attributes` | GET | Статус materialized columns |
