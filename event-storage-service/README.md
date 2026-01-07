# Event Storage Service

Spring Boot приложение для отправки событий в Kafka.

## Структура проекта

```
event-storage-service/
├── src/
│   ├── main/
│   │   ├── java/com/example/eventstorage/
│   │   │   ├── EventStorageServiceApplication.java  # Главный класс
│   │   │   ├── config/
│   │   │   │   └── KafkaConfig.java                 # Конфигурация Kafka
│   │   │   ├── controller/
│   │   │   │   └── EventController.java             # REST контроллер
│   │   │   └── service/
│   │   │       └── KafkaProducerService.java        # Сервис для отправки в Kafka
│   │   └── resources/
│   │       └── application.yml                      # Конфигурация приложения
├── build.gradle
├── settings.gradle
└── Dockerfile
```

## Запуск

### Локально

1. Убедитесь, что Kafka/Redpanda запущен:
```bash
cd ../kafka
docker compose up -d
```

2. Запустите приложение:
```bash
./gradlew bootRun
```

### Через Docker Compose

```bash
docker compose up -d
```

## API Endpoints

### Отправить событие в Kafka

```bash
POST /api/events/send?topic=events
Content-Type: application/json

"Hello, Kafka!"
```

### Отправить событие с ключом

```bash
POST /api/events/send-with-key?topic=events&key=user-123
Content-Type: application/json

"User action event"
```

## Конфигурация

Настройки в `application.yml`:
- `spring.kafka.bootstrap-servers` - адрес Kafka брокера (по умолчанию `localhost:9092`)
- `server.port` - порт приложения (по умолчанию `8080`)

## Зависимости

- Spring Boot 3.2.0
- Spring Kafka
- ClickHouse JDBC
- Lombok

