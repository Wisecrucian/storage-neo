# Event Storage - Структура проекта

## Что было изменено

### Старая структура (DEPRECATED)
- `event-storage-service/` - монолитное приложение

### Новая структура (ACTIVE)
```
event-storage/
├── event-storage-server/     # Серверная часть
│   ├── REST API endpoints
│   ├── Kafka Producer
│   └── Spring Boot приложение
│
└── event-storage-client/     # Клиентская библиотека (SDK)
    ├── EventStorageClient - основной клиент
    ├── Models (Event, EventResponse)
    ├── Configuration
    └── Exception handling
```

## Модули

### 1. event-storage-server
**Назначение:** REST API для приёма событий и отправки в Kafka

**Компоненты:**
- `EventController` - REST endpoints (/api/events/send, /api/events/send-with-key)
- `KafkaProducerService` - сервис отправки в Kafka
- `KafkaConfig` - конфигурация Kafka producer

**Endpoints:**
- POST /api/events/send?topic=events
- POST /api/events/send-with-key?topic=events&key={key}

### 2. event-storage-client
**Назначение:** Java SDK для использования в других приложениях

**API:**
```java
// Создание клиента
EventStorageClient client = new EventStorageClient("http://localhost:8081");

// Отправка события
Event event = Event.builder()
    .eventType("user_login")
    .userId("user-123")
    .sessionId("session-abc")
    .build();

client.sendEvent(event);                    // В топик по умолчанию
client.sendEvent(event, "custom-topic");    // В конкретный топик
client.sendEventWithKey(event, "user-123"); // С ключом партиционирования
client.sendEventAsync(event);                // Асинхронно
```

**Возможности клиента:**
- Автоматическая генерация eventId и timestamp
- Валидация обязательных полей
- Обработка ошибок с retry
- Асинхронная отправка
- Настраиваемые таймауты
- Логирование

## Сборка и запуск

### Сборка всего проекта
```bash
cd event-storage
./gradlew build
```

### Сборка отдельных модулей
```bash
./gradlew :event-storage-server:build
./gradlew :event-storage-client:build
```

### Запуск через Docker
```bash
cd event-storage
docker-compose up -d
```

### Запуск сервера локально
```bash
./gradlew :event-storage-server:bootRun
```

## Что дальше делать со старым event-storage-service?

**Рекомендация:** Удалить, так как весь код перенесен в новую структуру.

```bash
# Удалить старую папку
rm -rf event-storage-service/
```

## Интеграция с ClickHouse

Существующая интеграция через Kafka остаётся без изменений:
- События отправляются в топик `events`
- ClickHouse читает из этого топика через Kafka Engine
- Материализованное представление автоматически парсит JSON и сохраняет в таблицу

## Преимущества новой структуры

1. **Разделение ответственности**
   - Сервер: только API и Kafka producer
   - Клиент: SDK для приложений

2. **Переиспользование кода**
   - Клиент можно подключить как библиотеку в любое Java приложение
   - Не нужно писать HTTP запросы вручную

3. **Типобезопасность**
   - Модели событий в Java
   - Compile-time проверка полей

4. **Удобство разработки**
   - Мультимодульный проект
   - Независимая сборка модулей
   - Возможность публикации клиента в Maven

5. **Расширяемость**
   - Легко добавить новые модули (например, event-storage-consumer)
   - Можно создать клиенты для других языков

