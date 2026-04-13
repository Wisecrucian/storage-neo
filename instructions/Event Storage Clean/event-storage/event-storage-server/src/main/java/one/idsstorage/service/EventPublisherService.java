package one.idsstorage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class EventPublisherService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public EventPublisherService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public String publish(String topic, Map<String, Object> request) throws Exception {
        long started = System.nanoTime();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request != null) {
            payload.putAll(request);
        }

        payload.put("eventId", blankToDefault(asString(payload.get("eventId")), UUID.randomUUID().toString()));
        payload.put("eventType", blankToDefault(asString(payload.get("eventType")), "unknown"));
        payload.put("userId", blankToDefault(asString(payload.get("userId")), "anonymous"));
        payload.put("timestamp", blankToDefault(asString(payload.get("timestamp")), Instant.now().toString()));
        payload.put("recordType", normalizeRecordType(asString(payload.get("recordType"))));
        String eventType = asString(payload.get("eventType"));
        String recordType = asString(payload.get("recordType"));

        String message = objectMapper.writeValueAsString(payload);

        int customFieldsCount = Math.max(payload.size() - 4, 0);
        DistributionSummary.builder("event_storage_publish_payload_bytes")
                .description("Size of serialized event payload in bytes")
                .baseUnit("bytes")
                .register(meterRegistry)
                .record(message.getBytes().length);
        DistributionSummary.builder("event_storage_custom_fields_count")
                .description("Number of custom fields in event payload")
                .register(meterRegistry)
                .record(customFieldsCount);

        try {
            kafkaTemplate.send(topic, message).get(10, TimeUnit.SECONDS);
            Counter.builder("event_storage_publish_total")
                    .description("Total publish attempts by outcome")
                    .tag("topic", topic)
                    .tag("outcome", "success")
                    .register(meterRegistry)
                    .increment();
            Counter.builder("event_storage_business_events_total")
                    .description("Business events accepted by event type")
                    .tag("event_type", eventType)
                    .tag("record_type", recordType)
                    .register(meterRegistry)
                    .increment();
            recordPurchaseAmountIfPresent(eventType, payload.get("amount"));
            return (String) payload.get("eventId");
        } catch (Exception e) {
            Counter.builder("event_storage_publish_total")
                    .description("Total publish attempts by outcome")
                    .tag("topic", topic)
                    .tag("outcome", "error")
                    .register(meterRegistry)
                    .increment();
            throw e;
        } finally {
            Timer.builder("event_storage_publish_latency")
                    .description("Latency of publish operation")
                    .tag("topic", topic)
                    .register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeRecordType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "OTHER";
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "MESSAGE", "MODERATION", "PURCHASE", "SYSTEM", "OTHER" -> normalized;
            default -> "OTHER";
        };
    }

    private void recordPurchaseAmountIfPresent(String eventType, Object amountValue) {
        if (!"purchase".equalsIgnoreCase(eventType) || amountValue == null) {
            return;
        }
        try {
            double amount = Double.parseDouble(String.valueOf(amountValue));
            if (amount >= 0) {
                Counter.builder("event_storage_business_purchase_amount_total")
                        .description("Total amount of accepted purchase events")
                        .baseUnit("currency_units")
                        .register(meterRegistry)
                        .increment(amount);
                Counter.builder("event_storage_business_purchase_count_total")
                        .description("Total count of accepted purchase events")
                        .register(meterRegistry)
                        .increment();
            }
        } catch (NumberFormatException ignored) {
        }
    }
}
