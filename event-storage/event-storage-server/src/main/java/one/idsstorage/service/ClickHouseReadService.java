package one.idsstorage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class ClickHouseReadService {
    private static final Pattern FIELD_SEGMENT = Pattern.compile("[A-Za-z0-9_]+");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clickhouseUrl;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger pingUp = new AtomicInteger(0);

    public ClickHouseReadService(
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${app.clickhouse.url:http://localhost:8123}") String clickhouseUrl
    ) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clickhouseUrl = clickhouseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        Gauge.builder("event_storage_clickhouse_ping_up", pingUp, AtomicInteger::get)
                .description("1 when ClickHouse ping succeeds, 0 otherwise")
                .register(meterRegistry);
    }

    public boolean ping() {
        long started = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create(clickhouseUrl + "/ping"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200 && response.body().contains("Ok.");
            pingUp.set(ok ? 1 : 0);
            Counter.builder("event_storage_clickhouse_ping_total")
                    .tag("outcome", ok ? "success" : "error")
                    .register(meterRegistry)
                    .increment();
            return ok;
        } catch (Exception e) {
            pingUp.set(0);
            Counter.builder("event_storage_clickhouse_ping_total")
                    .tag("outcome", "error")
                    .register(meterRegistry)
                    .increment();
            return false;
        } finally {
            Timer.builder("event_storage_clickhouse_ping_latency")
                    .register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    public List<Map<String, Object>> readLatest(int limit) throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            int safeLimit = Math.max(1, Math.min(limit, 500));
            String query = "SELECT event_id, event_type, user_id, timestamp, created_at " +
                    "FROM default.events_storage ORDER BY created_at DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";

            HttpRequest request = HttpRequest.newBuilder(URI.create(clickhouseUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(query))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                Counter.builder("event_storage_clickhouse_read_total")
                        .tag("outcome", "error")
                        .register(meterRegistry)
                        .increment();
                throw new IOException("ClickHouse query failed with status " + response.statusCode() + ": " + response.body());
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : response.body().split("\n")) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                JsonNode json = objectMapper.readTree(line);
                rows.add(objectMapper.convertValue(json, new TypeReference<Map<String, Object>>() {}));
            }
            Counter.builder("event_storage_clickhouse_read_total")
                    .tag("outcome", "success")
                    .register(meterRegistry)
                    .increment();
            return rows;
        } finally {
            Timer.builder("event_storage_clickhouse_read_latency")
                    .register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    public List<Map<String, Object>> searchByCustomField(String field, String value, int limit)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            int safeLimit = Math.max(1, Math.min(limit, 500));
            String safeFieldExpr = buildJsonExtractStringExpression(field);
            String escapedValue = escapeSqlLiteral(value);

            String query = "SELECT event_id, event_type, user_id, timestamp, created_at, message " +
                    "FROM default.events_storage " +
                    "WHERE " + safeFieldExpr + " = '" + escapedValue + "' " +
                    "ORDER BY created_at DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";

            HttpRequest request = HttpRequest.newBuilder(URI.create(clickhouseUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(query))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                Counter.builder("event_storage_clickhouse_search_total")
                        .tag("outcome", "error")
                        .register(meterRegistry)
                        .increment();
                throw new IOException("ClickHouse search failed with status " + response.statusCode() + ": " + response.body());
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : response.body().split("\n")) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                JsonNode json = objectMapper.readTree(line);
                rows.add(objectMapper.convertValue(json, new TypeReference<Map<String, Object>>() {}));
            }
            Counter.builder("event_storage_clickhouse_search_total")
                    .tag("outcome", "success")
                    .register(meterRegistry)
                    .increment();
            return rows;
        } finally {
            Timer.builder("event_storage_clickhouse_search_latency")
                    .register(meterRegistry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private String buildJsonExtractStringExpression(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be empty");
        }
        String[] segments = field.split("\\.");
        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null || segment.isBlank() || !FIELD_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("invalid field name: " + field);
            }
            normalized.add(segment);
        }
        StringBuilder builder = new StringBuilder("JSONExtractString(message");
        for (String seg : normalized) {
            builder.append(", '").append(seg).append("'");
        }
        builder.append(")");
        return builder.toString();
    }

    private String escapeSqlLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
