# SETUP — Чистый проект event-storage

## Что это

Чистая кодовая база для нового хранилища событий (Kafka + ClickHouse).
Основана на рабочем прототипе из storage-neo, с применёнными изменениями:

- Новая доменная модель (StorageAttribute, UserActivityType, UserActivitySchema)
- Обновлённый Record с typed access (совместим со старым StorageRecord)
- Schema-aware генератор данных
- Единый DDL для ClickHouse (без legacy events_storage)
- Удалены legacy контроллеры (EventController, StorageController)
- Исправлен Makefile (правильный APP_DIR)

## Как развернуть

```bash
# Распаковать
tar xzf event-storage-clean.tar.gz -C /path/to/new-repo

# Инициализировать git
cd /path/to/new-repo
git init
git add .
git commit -m "Initial: clean event-storage base"
```

## Структура

```
├── Makefile                     # make infra-up, make clickhouse-shell, etc.
├── README.md                    # Обзор проекта
├── docs/ARCHITECTURE.md         # Маппинг старое→новое
├── kafka/docker-compose.yml     # Kafka + KRaft + Kafka UI
├── clickhouse/
│   ├── docker-compose.yml       # ClickHouse
│   └── init.d/01-schema.sql     # Единый DDL (events_raw + Kafka Engine + MV)
├── event-storage/               # Java приложение (Gradle multi-module)
│   ├── event-storage-server/    # Spring Boot сервис
│   └── event-storage-client/    # SDK (заглушка)
└── experiments/scripts/         # Python бенчмарки
```

## Что работает

- `make infra-up` → поднимает Kafka + ClickHouse
- `cd event-storage && ./gradlew bootRun` → запускает сервис на :8080
- `POST /api/records/save-batch` → пишет через Kafka в ClickHouse
- `POST /api/records/generate-and-save?count=1000` → генерирует тестовые данные
- `GET /api/records/moderation?userId=123` → point read
- `GET /api/records/analytics` → агрегации

## Что предстоит добавить

По мере переноса кода из старого проекта:

1. **query/** — предикатный язык запросов (StorageQuery, EqualsPredicate, etc.)
2. **adapter/** — реализация StorageCdbRemoteService поверх Kafka + ClickHouse
3. **Реальные классы из старого проекта** — StorageCdbRemoteService interface,
   IListChunk, IChunkProperties, AggregatedResult, BadUserContentBean
4. **Observer pattern** → Kafka consumer groups

## Для Cursor

При добавлении новых файлов из старого проекта:
- Классы из `domain/` → `event-storage-server/src/main/java/one/idsstorage/domain/`
- Классы query → создать `event-storage-server/src/main/java/one/idsstorage/query/`
- Адаптер → создать `event-storage-server/src/main/java/one/idsstorage/adapter/`
- Не менять: `clickhouse/`, `repository/`, `metrics/`, `util/`
