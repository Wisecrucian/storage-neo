package one.idsstorage.clickhouse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import one.idsstorage.domain.AggregationResult;
import one.idsstorage.domain.Record;
import one.idsstorage.domain.UserActivityType;
import one.idsstorage.util.UuidV7;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ClickHouseMapper {
    private static final DateTimeFormatter CH_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ObjectMapper objectMapper;

    public ClickHouseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJsonEachRowLine(Record r) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_time", formatForClickHouse(r.getEventTime() == null ? Instant.now() : r.getEventTime()));
        row.put("ingest_time", formatForClickHouse(r.getIngestTime() == null ? Instant.now() : r.getIngestTime()));
        row.put("record_type", r.getCoarseRecordType().name());
        row.put("event_id", toUuid(r.getEventId()));

        Map<String, String> attrs = new LinkedHashMap<>();
        if (r.getAttrs() != null) {
            for (Map.Entry<String, Object> e : r.getAttrs().entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().toLowerCase();
                if (!key.isBlank()) {
                    attrs.put(key, e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
            }
        }
        if (r.getActivityType() != null) {
            attrs.putIfAbsent("type", r.getActivityType().name());
        }
        row.put("user_id", parseLongOrZero(attrs.get("user_id")));
        row.put("chat_id", parseLongOrZero(attrs.get("chat_id")));
        row.put("message_id", parseLongOrZero(attrs.get("message_id")));
        row.put("attrs", attrs);
        return objectMapper.writeValueAsString(row);
    }

    public List<Record> toRecordListFromJsonEachRow(String raw) throws IOException {
        List<Record> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line == null || line.isBlank()) continue;
            JsonNode node = objectMapper.readTree(line);
            Record r = new Record();
            r.setEventId(node.path("event_id").asText());
            if (!node.path("event_time").isMissingNode()) {
                r.setEventTime(parseClickHouseInstant(node.path("event_time").asText()));
            }
            if (!node.path("ingest_time").isMissingNode()) {
                r.setIngestTime(parseClickHouseInstant(node.path("ingest_time").asText()));
            }

            Map<String, Object> attrs = new LinkedHashMap<>();
            JsonNode attrsNode = node.path("attrs");
            if (attrsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = attrsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> f = fields.next();
                    String k = f.getKey() == null ? "" : f.getKey().toLowerCase();
                    if (!k.isBlank()) {
                        attrs.put(k, f.getValue().asText());
                    }
                }
            }
            long uidCol = node.path("user_id").asLong(0);
            if (uidCol != 0) {
                attrs.putIfAbsent("user_id", String.valueOf(uidCol));
            }
            if (!attrs.isEmpty()) {
                r.setAttrs(attrs);
                Object typeRaw = attrs.get("type");
                if (typeRaw != null) {
                    UserActivityType at = UserActivityType.fromRaw(String.valueOf(typeRaw));
                    if (at != null) {
                        r.setActivityType(at);
                    }
                }
            }
            out.add(r);
        }
        return out;
    }

    public List<AggregationResult> toAggregationResults(String raw) throws IOException {
        List<AggregationResult> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line == null || line.isBlank()) continue;
            JsonNode n = objectMapper.readTree(line);
            AggregationResult r = new AggregationResult();
            r.setBucket(n.path("bucket").asText());
            r.setEventType(n.path("record_type").asText());
            r.setCount(n.path("count").asLong());
            r.setRate(n.path("rate").asDouble());
            out.add(r);
        }
        return out;
    }

    public List<Map<String, Object>> toMapList(String raw) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line == null || line.isBlank()) continue;
            JsonNode n = objectMapper.readTree(line);
            out.add(objectMapper.convertValue(n, new TypeReference<Map<String, Object>>() {}));
        }
        return out;
    }

    private String toUuid(String id) {
        if (id == null || id.isBlank()) return UuidV7.generate().toString();
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException e) {
            return UuidV7.generate().toString();
        }
    }

    private long parseLongOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private Instant parseClickHouseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                LocalDateTime dt = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return dt.toInstant(ZoneOffset.UTC);
            } catch (Exception ignoredAgain) {
                LocalDateTime dt = LocalDateTime.parse(value.replace(" ", "T"));
                return dt.toInstant(ZoneOffset.UTC);
            }
        }
    }

    private String formatForClickHouse(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(CH_DATETIME_FORMAT);
    }
}
