# Архитектура: маппинг старой системы → новой

## Контракт (сохраняется)

`StorageCdbRemoteService` — интерфейс, используемый внешними клиентами:
- `storeRecords(entityId, records)` — запись
- `publishRecords(entityId, records)` — запись + observer notification
- `getStorageRecords(StorageQueryArgs)` — чтение с предикатным фильтром
- `executeQuery(ComponentQueryArgs)` — аналитика

## Write Path

| Старое (Cassandra) | Новое (Kafka + ClickHouse) |
|---|---|
| `runWithLock(entityId)` | Kafka partition key = entityId |
| `appendData` binary buffer | MergeTree append + merge |
| `timestampOrdering.sortedCopy` | MergeTree ORDER BY |
| `skipDuplicates(data, header)` | ReplicatedMergeTree dedup / event_id |
| `maxCapacity` truncation | TTL + PARTITION DROP |
| `asyncMaxBatch=100, asyncMaxDelay=100` | Kafka producer batching |
| Observer pattern (publishRecords) | Kafka consumer groups |

## Read Path

| Старое | Новое |
|---|---|
| `ComponentQueryArgs` + рефлексия | `QueryFilter` → SQL builder |
| Split-reduce через кластер | ClickHouse Distributed Engine |
| In-memory + Lucene + combine | Один MergeTree запрос |
| `StorageQuery` (предикаты) | SQL WHERE clause |
| `EqualsPredicate(USER_ID, X)` | `user_id = X` (column) |
| `EqualsPredicate(LANG, "ru")` | `attr_lang = 'ru'` (materialized) |
| `InPredicate(TYPE, [A, B])` | `attrs['type'] IN ('A','B')` |
| `RangePredicate(TIMESTAMP, f, t)` | `event_time >= f AND event_time <= t` |

## Порядок реализации

- [x] Batch 1: Domain Model (StorageAttribute, UserActivityType, UserActivitySchema, Record)
- [ ] Batch 2: Predicate Query Language (StorageQuery → SQL)
- [ ] Batch 3: Query Builder + Read Path
- [ ] Batch 4: Adapter Layer (StorageCdbService)
- [ ] Batch 5: Observer / Publish (Kafka consumer groups)
