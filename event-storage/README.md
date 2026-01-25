# Event Storage System

Multi-module project with server and client separation.

## Structure

```
event-storage/
├── event-storage-server/    # REST API + Kafka Producer
│   ├── src/main/java/
│   │   └── com/example/eventstorage/
│   │       ├── config/
│   │       │   └── KafkaConfig.java
│   │       ├── controller/
│   │       │   └── EventController.java
│   │       ├── service/
│   │       │   └── KafkaProducerService.java
│   │       └── EventStorageServiceApplication.java
│   └── build.gradle
│
├── event-storage-client/    # Java SDK
│   ├── src/main/java/
│   │   └── com/example/eventstorage/client/
│   │       ├── EventStorageClient.java
│   │       ├── config/
│   │       │   └── EventStorageClientConfig.java
│   │       ├── model/
│   │       │   ├── Event.java
│   │       │   └── EventResponse.java
│   │       └── exception/
│   │           └── EventStorageException.java
│   └── build.gradle
│
├── build.gradle             # Root configuration
├── settings.gradle          # Multi-module settings
└── docker-compose.yml       # Deployment

## Quick Start

### Build
```bash
./gradlew build
```

### Run Server
```bash
docker-compose up -d
```

### Use Client
```java
EventStorageClient client = new EventStorageClient("http://localhost:8081");

Event event = Event.builder()
    .eventType("user_login")
    .userId("user-123")
    .build();

client.sendEvent(event);
```

