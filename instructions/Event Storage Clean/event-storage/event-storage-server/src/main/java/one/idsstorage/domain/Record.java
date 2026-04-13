package one.idsstorage.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Event record — unified model for write and read paths.
 *
 * <p>Mirrors the legacy {@code StorageRecordImpl} which stored attributes in a
 * {@code Map<StorageAttribute, Object>}. The key difference: in the ClickHouse
 * model, three attributes are "promoted" to dedicated columns (they participate
 * in the primary sort key), while the rest go into {@code attrs Map(String, String)}.
 *
 * <p>Mapping from legacy to ClickHouse:
 * <pre>
 *   StorageRecord.getAttribute(TIMESTAMP) → event_time (DateTime column)
 *   StorageRecord.getAttribute(USER_ID)   → user_id    (UInt64 column)
 *   StorageRecord.getAttribute(TYPE)      → record_type (LowCardinality(String))
 *                                           + attrs['type'] for fine-grained type
 *   all other attributes                  → attrs Map(String, String)
 * </pre>
 *
 * <p>The legacy appendData semantics are handled differently:
 * <ul>
 *   <li><b>Timestamp ordering</b> — MergeTree sorts by ORDER BY on merge.</li>
 *   <li><b>Deduplication</b> — ReplicatedMergeTree dedup by (partition, ORDER BY)
 *       within the dedup window; or application-level via event_id.</li>
 *   <li><b>Capacity limit</b> — TTL + PARTITION BY toDate(event_time) handles
 *       retention; no manual byteOffset management.</li>
 * </ul>
 */
public class Record {

    // ── Promoted columns (participate in ORDER BY / projections) ─────────

    private RecordType recordType;
    private UserActivityType activityType;
    private Instant eventTime;
    private Instant ingestTime;
    private String eventId;
    private Long userId;
    private Long chatId;
    private Long messageId;

    // ── Dynamic attributes → Map(String, String) in ClickHouse ──────────

    private Map<String, Object> attrs;

    // ══════════════════════════════════════════════════════════════════════
    // StorageRecord-compatible typed access
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Set a typed attribute — mirrors legacy {@code StorageRecord.setAttribute}.
     *
     * <p>If the attribute is one of the promoted columns (TIMESTAMP, USER_ID,
     * TYPE), it sets the corresponding field directly. Otherwise it goes
     * into {@code attrs}.
     */
    @JsonIgnore
    public <T> void setTypedAttribute(StorageAttribute<T> attribute, T value) {
        attribute.validate(value);

        if (attribute == UserActivitySchema.TIMESTAMP && value instanceof java.util.Date d) {
            this.eventTime = d.toInstant();
            return;
        }
        if (attribute == UserActivitySchema.USER_ID && value instanceof Long l) {
            this.userId = l;
            return;
        }
        if (attribute == UserActivitySchema.TYPE && value instanceof String s) {
            UserActivityType at = UserActivityType.fromRaw(s);
            if (at != null) {
                this.activityType = at;
                this.recordType = at.getRecordType();
            }
            putAttr(attribute.chKey(), s);
            return;
        }
        if (attribute == UserActivitySchema.CHAT_ID && value instanceof Long l) {
            this.chatId = l;
            putAttr(attribute.chKey(), attribute.toStringValue(value));
            return;
        }
        if (attribute == UserActivitySchema.MESSAGE_ID && value instanceof Long l) {
            this.messageId = l;
            putAttr(attribute.chKey(), attribute.toStringValue(value));
            return;
        }

        // All other attributes → attrs map
        putAttr(attribute.chKey(), attribute.toStringValue(value));
    }

    /**
     * Get a typed attribute — mirrors legacy {@code StorageRecord.getAttribute}.
     */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public <T> T getTypedAttribute(StorageAttribute<T> attribute) {
        if (attribute == UserActivitySchema.TIMESTAMP) {
            return eventTime == null ? null
                    : (T) java.util.Date.from(eventTime);
        }
        if (attribute == UserActivitySchema.USER_ID) {
            return (T) userId;
        }
        if (attrs == null) return null;
        Object raw = attrs.get(attribute.chKey());
        if (raw == null) return null;
        String s = String.valueOf(raw);
        if (attribute.getType() == Long.class) {
            return (T) Long.valueOf(s);
        }
        if (attribute.getType() == Boolean.class) {
            return (T) Boolean.valueOf("1".equals(s) || "true".equalsIgnoreCase(s));
        }
        return (T) s;
    }

    // ══════════════════════════════════════════════════════════════════════
    // JSON serialization (Jackson) — compatible with existing API & Kafka
    // ══════════════════════════════════════════════════════════════════════

    @JsonProperty("recordType")
    public RecordType getRecordType() { return recordType; }
    public void setRecordType(RecordType recordType) { this.recordType = recordType; }

    @JsonProperty("activityType")
    public UserActivityType getActivityType() { return activityType; }
    public void setActivityType(UserActivityType activityType) {
        this.activityType = activityType;
        if (activityType != null) {
            this.recordType = activityType.getRecordType();
        }
    }

    @JsonProperty("eventTime")
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }

    @JsonProperty("ingestTime")
    public Instant getIngestTime() { return ingestTime; }
    public void setIngestTime(Instant ingestTime) { this.ingestTime = ingestTime; }

    @JsonProperty("eventId")
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    @JsonProperty("userId")
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    @JsonProperty("chatId")
    public Long getChatId() { return chatId; }
    public void setChatId(Long chatId) { this.chatId = chatId; }

    @JsonProperty("messageId")
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }

    @JsonProperty("attrs")
    public Map<String, Object> getAttrs() { return attrs; }
    public void setAttrs(Map<String, Object> attrs) { this.attrs = attrs; }

    // Backward compatibility with existing EventPublisherService / controllers
    @JsonIgnore
    public String getEventType() {
        return activityType != null ? activityType.name()
                : (attrs != null ? String.valueOf(attrs.get("type")) : null);
    }
    public void setEventType(String eventType) {
        UserActivityType at = UserActivityType.fromRaw(eventType);
        if (at != null) {
            this.activityType = at;
            this.recordType = at.getRecordType();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ══════════════════════════════════════════════════════════════════════

    private void putAttr(String key, String value) {
        if (attrs == null) {
            attrs = new LinkedHashMap<>();
        }
        attrs.put(key, value);
    }

    @JsonIgnore
    public Map<String, Object> getAttrsUnmodifiable() {
        return attrs == null ? Collections.emptyMap() : Collections.unmodifiableMap(attrs);
    }
}
