# Архитектура нового хранилища — структура пакетов и классов

## Принцип

Внешний контракт (`StorageCdbRemoteService`) сохраняется — его используют другие проекты.
Внутренняя реализация полностью заменяется: Cassandra + binary buffer + split-reduce + Lucene
→ Kafka + ClickHouse + SQL.

Слой адаптации (adapter) реализует старый интерфейс поверх нового стека.

---

## Пакетная структура

```
one.idsstorage/
├── domain/                          # Модель данных
│   ├── StorageAttribute<T>          # [DONE] Типизированный дескриптор атрибута
│   ├── UserActivityType             # [DONE] 30 типов событий → RecordType
│   ├── UserActivitySchema           # [DONE] 55 атрибутов, маппинг type→attrs
│   ├── Record                       # [DONE] StorageRecord-совместимая запись
│   ├── RecordType                   # [DONE] Категория (MESSAGE/MODERATION/...)
│   ├── QueryFilter                  # [DONE] Типизированный фильтр запросов
│   ├── ModerationFilter             # [exists] Оставить для backward compat
│   ├── AggregationQuery             # [exists] Оставить для backward compat
│   └── AggregationResult            # [exists] Оставить для backward compat
│
├── query/                           # Предикатный язык запросов (НОВЫЙ пакет)
│   ├── StorageQuery                 # Список предикатов (AND-семантика)
│   ├── StorageRecordPredicate       # Интерфейс предиката
│   ├── AttributePredicate<T>        # Базовый класс: предикат по атрибуту
│   ├── EqualsPredicate<T>           # attr = value
│   ├── InPredicate<T>               # attr IN (values)
│   ├── RangePredicate<T>            # attr >= from AND attr <= to
│   └── SqlPredicateCompiler         # НОВЫЙ: StorageQuery → SQL WHERE clause
│
├── adapter/                         # Адаптер: старый контракт → новый стек (НОВЫЙ пакет)
│   ├── StorageCdbService            # Реализация StorageCdbRemoteService
│   │                                #   storeRecords → RecordRepository (Kafka)
│   │                                #   publishRecords → Kafka + observer dispatch
│   │                                #   getStorageRecords → SqlPredicateCompiler → CH
│   │                                #   executeQuery → QueryFilter → CH
│   ├── StorageQueryExecutor         # StorageQuery → SQL → ClickHouse → IListChunk
│   └── RecordConverter              # StorageRecord ↔ Record ↔ CH JSON row
│
├── clickhouse/                      # ClickHouse-специфичный слой (СУЩЕСТВУЕТ)
│   ├── ClickHouseClient             # [exists] HTTP клиент к CH
│   ├── ClickHouseMapper             # [exists] JSON ↔ Record сериализация
│   ├── ClickHouseRecordRepository   # [exists] Kafka produce (write path)
│   ├── ClickHouseRecordQueryService # [exists] SQL запросы (read path)
│   ├── ClickHouseQueryBuilder       # НОВЫЙ: QueryFilter/StorageQuery → SQL
│   ├── HotAttributeConfig           # [exists] Конфиг materialized колонок
│   └── HotAttributeManager          # [exists] DDL управление hot attrs
│
├── controller/                      # REST API (СУЩЕСТВУЕТ)
│   ├── RecordStorageController      # [exists] /api/records/* — внутренний API
│   └── StorageQueryController       # НОВЫЙ: /api/query/* — endpoint для StorageQuery
│
├── service/                         # Бизнес-сервисы (СУЩЕСТВУЕТ)
│   ├── EventPublisherService        # [exists] Legacy event publish
│   ├── RecordDataGenerator          # [DONE] Schema-aware генератор
│   └── ClickHouseReadService        # [exists] Read service
│
├── repository/                      # Интерфейсы (СУЩЕСТВУЕТ)
│   ├── RecordRepository             # [exists] Write interface
│   └── RecordQueryService           # [exists] Read interface
│
├── metrics/                         # Метрики (СУЩЕСТВУЕТ)
│   ├── RecordStorageMetrics         # [exists]
│   └── ClickHouseStorageMetrics     # [exists]
│
└── util/                            # Утилиты (СУЩЕСТВУЕТ)
    └── UuidV7                       # [exists] UUID v7 генерация
```

---

## Write Path (финальный)

```
Внешний клиент
    │
    ▼
StorageCdbService.storeRecords(entityId, List<StorageRecord>)
    │
    ├─ RecordConverter: StorageRecord → Record (domain)
    │
    ├─ ClickHouseRecordRepository.saveAll(records)
    │     │
    │     ├─ ClickHouseMapper.toJsonEachRowLine(record) — сериализация
    │     ├─ KafkaTemplate.send(topic="events.raw", key=entityId, value=json)
    │     │     партиционирование по entityId → порядок per entity
    │     └─ metrics: write latency, batch size, record type breakdown
    │
    └─ (async) ClickHouse Kafka Engine → MV → events_raw MergeTree
          сортировка по ORDER BY (user_id, record_type, event_time)
          дедупликация: ReplicatedMergeTree insert dedup window
          retention: TTL = PARTITION BY toDate(event_time) + DROP PARTITION
```

### Что заменяет что:

| Старое (Cassandra)                  | Новое (Kafka + ClickHouse)                    |
|-------------------------------------|-----------------------------------------------|
| `runWithLock(entityId)`             | Kafka partition key = entityId                 |
| `appendData` binary byteOffset     | MergeTree append + background merge            |
| `timestampOrdering.sortedCopy`      | MergeTree ORDER BY сортирует при merge          |
| `skipDuplicates(data, header)`      | ReplicatedMergeTree dedup / event_id unique     |
| `maxCapacity` truncation            | TTL + PARTITION DROP по расписанию              |
| `@RemoteMethod asyncMaxBatch=100`   | Kafka producer batching (linger.ms + batch.size)|
| Observer pattern (publishRecords)   | Kafka consumer groups (каждый observer = group) |

---

## Read Path (финальный)

```
Внешний клиент
    │
    ▼
StorageCdbService.getStorageRecords(StorageQueryArgs)
    │
    ├─ StorageQueryArgs.getQuery() → StorageQuery (список предикатов)
    │
    ├─ SqlPredicateCompiler.compile(StorageQuery) → WHERE clause
    │     │
    │     ├─ EqualsPredicate(USER_ID, 123)   → "user_id = 123"
    │     ├─ EqualsPredicate(LANG, "ru")     → "attr_lang = 'ru'" (materialized)
    │     │                                 или "attrs['lang'] = 'ru'" (Map fallback)
    │     ├─ InPredicate(TYPE, [A, B])       → "attrs['type'] IN ('A','B')"
    │     ├─ RangePredicate(TIMESTAMP, f, t)  → "event_time >= f AND event_time <= t"
    │     └─ Promoted attrs (user_id, chat_id, message_id) → column access
    │        Other attrs → Map access или materialized column
    │
    ├─ ClickHouseQueryBuilder.buildSelect(where, chunk, orderBy)
    │     → полный SQL с LIMIT/OFFSET
    │
    ├─ ClickHouseClient.query(sql) → JSONEachRow
    │
    ├─ ClickHouseMapper.toRecordList(raw) → List<Record>
    │
    ├─ RecordConverter: Record → StorageRecord
    │
    └─ → IListChunk<StorageRecord> (offset, limit, totalCount, data)
```

### Выбор индекса ClickHouse автоматический:

| Предикат содержит           | Используется                           |
|-----------------------------|----------------------------------------|
| user_id = X                 | Primary key (user_id, record_type, event_time) |
| user_id + record_type       | Primary key — обе колонки в prefix      |
| record_type + event_time    | Projection by_record_type               |
| chat_id = X                 | Bloom filter skip index                 |
| attrs['lang'] = X           | Materialized column attr_lang           |
| только event_time range     | Partition pruning по toDate(event_time) |

---

## Analytics Path

```
StorageCdbService.executeQuery(ComponentQueryArgs)
    │
    ├─ ComponentQueryArgs → QueryFilter.fromParams(args.getArguments())
    │
    ├─ ClickHouseQueryBuilder.buildAggregation(filter)
    │     GROUP BY + aggregate functions
    │     Использует projection by_record_type для (record_type, event_time) запросов
    │
    ├─ ClickHouseClient.query(sql)
    │
    └─ → AggregatedResult
```

---

## Порядок реализации (батчи)

### Batch 1 — Domain Model [DONE]
StorageAttribute, UserActivityType, UserActivitySchema, Record, RecordDataGenerator

### Batch 2 — Predicate Query Language
StorageQuery, StorageRecordPredicate, EqualsPredicate, InPredicate, RangePredicate,
SqlPredicateCompiler

### Batch 3 — Query Builder + Read Path
ClickHouseQueryBuilder, StorageQueryExecutor, StorageQueryController

### Batch 4 — Adapter Layer
RecordConverter, StorageCdbService (implements StorageCdbRemoteService),
IListChunk integration

### Batch 5 — Observer / Publish
Kafka consumer group setup для observer pattern замены

---

## Открытые вопросы (уточнить по ходу)

1. `BadUserContentBean` — структура и когда заполняется?
2. `AggregatedResult` — полная структура (не только StorageQueryResult)?
3. Observer-ы на publishRecords — список и логика каждого?
4. `IChunkProperties` — cursor-based или offset-based пагинация?
5. Нужна ли точная дедупликация по (userId, type, timestamp) или достаточно event_id?
