.PHONY: help up down restart build logs clean test kafka-up kafka-down clickhouse-up clickhouse-down app-up app-down app-build

# Переменные
KAFKA_DIR = kafka
CLICKHOUSE_DIR = clickhouse
APP_DIR = event-storage

# Цвета для вывода
GREEN = \033[0;32m
YELLOW = \033[1;33m
NC = \033[0m # No Color

help: ## Показать справку по командам
	@echo "$(GREEN)Доступные команды:$(NC)"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(YELLOW)%-20s$(NC) %s\n", $$1, $$2}'

# Основные команды
up: ## Запустить все сервисы (Kafka, ClickHouse, приложение)
	@echo "$(GREEN)Запуск всех сервисов...$(NC)"
	@$(MAKE) kafka-up
	@$(MAKE) clickhouse-up
	@sleep 5
	@$(MAKE) app-up

down: ## Остановить все сервисы
	@echo "$(GREEN)Остановка всех сервисов...$(NC)"
	@$(MAKE) app-down
	@$(MAKE) clickhouse-down
	@$(MAKE) kafka-down

restart: ## Перезапустить все сервисы
	@$(MAKE) down
	@sleep 2
	@$(MAKE) up

# Kafka команды
kafka-up: ## Запустить Kafka и Kafka UI
	@echo "$(GREEN)Запуск Kafka...$(NC)"
	@cd $(KAFKA_DIR) && docker compose up -d
	@echo "$(GREEN)Kafka UI доступен на http://localhost:8080$(NC)"

kafka-down: ## Остановить Kafka
	@cd $(KAFKA_DIR) && docker compose down

kafka-logs: ## Показать логи Kafka
	@cd $(KAFKA_DIR) && docker compose logs -f

kafka-restart: ## Перезапустить Kafka
	@$(MAKE) kafka-down
	@sleep 2
	@$(MAKE) kafka-up

kafka-create-topic: ## Создать топик events в Kafka
	@echo "$(GREEN)Создание топика events...$(NC)"
	@docker exec -it kafka-kafka-1 kafka-topics --create \
		--bootstrap-server localhost:9092 \
		--topic events \
		--partitions 3 \
		--replication-factor 1 || echo "$(YELLOW)Топик уже существует или Kafka не запущен$(NC)"

kafka-list-topics: ## Показать список топиков Kafka
	@echo "$(GREEN)Список топиков Kafka:$(NC)"
	@docker exec -it kafka-kafka-1 kafka-topics --list --bootstrap-server localhost:9092 || echo "$(YELLOW)Kafka не запущен$(NC)"

# ClickHouse команды
clickhouse-up: ## Запустить ClickHouse
	@echo "$(GREEN)Запуск ClickHouse...$(NC)"
	@cd $(CLICKHOUSE_DIR) && docker compose up -d
	@sleep 3
	@echo "$(GREEN)ClickHouse доступен на http://localhost:8123$(NC)"

clickhouse-down: ## Остановить ClickHouse
	@cd $(CLICKHOUSE_DIR) && docker compose down

clickhouse-logs: ## Показать логи ClickHouse
	@cd $(CLICKHOUSE_DIR) && docker compose logs -f clickhouse

clickhouse-shell: ## Подключиться к ClickHouse через клиент
	@docker exec -it clickhouse clickhouse-client

clickhouse-init: ## Инициализировать таблицы Kafka в ClickHouse
	@echo "$(GREEN)Инициализация таблиц ClickHouse...$(NC)"
	@docker exec -i clickhouse clickhouse-client < clickhouse/init.d/01-schema.sql || echo "$(YELLOW)Ошибка инициализации или таблицы уже существуют$(NC)"

clickhouse-check-data: ## Проверить данные в ClickHouse
	@echo "$(GREEN)Проверка данных в events_storage:$(NC)"
	@docker exec clickhouse clickhouse-client --query "SELECT count() as total_events FROM events_storage"
	@echo "$(GREEN)Последние 5 событий:$(NC)"
	@docker exec clickhouse clickhouse-client --query "SELECT event_type, user_id, timestamp FROM events_storage ORDER BY created_at DESC LIMIT 5 FORMAT Pretty"

clickhouse-check-tables: ## Проверить созданные таблицы
	@echo "$(GREEN)Список таблиц:$(NC)"
	@docker exec clickhouse clickhouse-client --query "SHOW TABLES"

clickhouse-test-kafka: ## Отправить тестовое событие и проверить в ClickHouse
	@echo "$(GREEN)Отправка тестового события...$(NC)"
	@curl -s -X POST "http://localhost:8081/api/events/send" \
		-H "Content-Type: application/json" \
		-d '{"eventId":"test-$(shell date +%s)","eventType":"test_event","userId":"test-user","timestamp":"'$(shell date -u +%Y-%m-%dT%H:%M:%SZ)'"}' > /dev/null
	@sleep 2
	@echo "$(GREEN)Проверка данных в ClickHouse:$(NC)"
	@docker exec clickhouse clickhouse-client --query "SELECT * FROM events_storage WHERE event_type = '\''test_event'\'' ORDER BY created_at DESC LIMIT 1 FORMAT Vertical"

clickhouse-restart: ## Перезапустить ClickHouse
	@$(MAKE) clickhouse-down
	@sleep 2
	@$(MAKE) clickhouse-up

# Приложение команды
app-build: ## Собрать Spring Boot приложение
	@echo "$(GREEN)Сборка приложения...$(NC)"
	@cd $(APP_DIR) && ./gradlew clean bootJar -x test

app-up: ## Запустить приложение через Docker Compose
	@echo "$(GREEN)Запуск приложения...$(NC)"
	@echo "$(YELLOW)Убедитесь, что Kafka и ClickHouse запущены!$(NC)"
	@cd $(APP_DIR) && docker compose up -d --build
	@echo "$(GREEN)Приложение доступно на http://localhost:8080$(NC)"

app-down: ## Остановить приложение
	@cd $(APP_DIR) && docker compose down

app-logs: ## Показать логи приложения
	@cd $(APP_DIR) && docker compose logs -f app

app-run: ## Запустить приложение локально (без Docker)
	@cd $(APP_DIR) && ./gradlew bootRun

app-restart: ## Перезапустить приложение
	@$(MAKE) app-down
	@sleep 2
	@$(MAKE) app-up

# Общие команды
logs: ## Показать логи всех сервисов
	@echo "$(GREEN)Логи всех сервисов:$(NC)"
	@docker compose logs -f

clean: ## Остановить все и удалить volumes
	@echo "$(YELLOW)Внимание: Это удалит все данные!$(NC)"
	@$(MAKE) down
	@cd $(KAFKA_DIR) && docker compose down -v
	@cd $(CLICKHOUSE_DIR) && docker compose down -v
	@cd $(APP_DIR) && docker compose down -v
	@echo "$(GREEN)Очистка завершена$(NC)"

clean-app: ## Очистить только приложение (без инфраструктуры)
	@cd $(APP_DIR) && docker compose down -v
	@echo "$(GREEN)Очистка приложения завершена$(NC)"

clean-all: clean ## Полная очистка (включая Docker образы)
	@docker system prune -f
	@echo "$(GREEN)Полная очистка завершена$(NC)"

# Статус
status: ## Показать статус всех контейнеров
	@echo "$(GREEN)Статус контейнеров:$(NC)"
	@docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# Инфраструктура
infra-up: ## Запустить только инфраструктуру (Kafka + ClickHouse)
	@$(MAKE) kafka-up
	@$(MAKE) clickhouse-up

infra-down: ## Остановить инфраструктуру
	@$(MAKE) kafka-down
	@$(MAKE) clickhouse-down

# Утилиты
ps: status ## Показать статус контейнеров (алиас для status)

.DEFAULT_GOAL := help

