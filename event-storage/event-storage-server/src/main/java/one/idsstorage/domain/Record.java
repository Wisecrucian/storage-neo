package one.idsstorage.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Record {
    private UserActivityType activityType;
    private Instant eventTime;
    private Instant ingestTime;
    private String eventId;
    private Map<String, Object> attrs;

    @JsonIgnore
    public <T> void setTypedAttribute(StorageAttribute<T> attribute, T value) {
        attribute.validate(value);

        if (attribute == UserActivitySchema.TIMESTAMP && value instanceof java.util.Date d) {
            this.eventTime = d.toInstant();
            return;
        }
        if (attribute == UserActivitySchema.TYPE && value instanceof String s) {
            UserActivityType at = UserActivityType.fromRaw(s);
            if (at != null) {
                this.activityType = at;
            }
            putAttr(attribute.chKey(), s);
            return;
        }
        putAttr(attribute.chKey(), attribute.toStringValue(value));
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public <T> T getTypedAttribute(StorageAttribute<T> attribute) {
        if (attribute == UserActivitySchema.TIMESTAMP) {
            return eventTime == null ? null
                    : (T) java.util.Date.from(eventTime);
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

    @JsonProperty("activityType")
    public UserActivityType getActivityType() { return activityType; }
    public void setActivityType(UserActivityType activityType) {
        this.activityType = activityType;
    }

    @JsonIgnore
    public RecordType getCoarseRecordType() {
        UserActivityType at = activityType;
        if (at == null && attrs != null) {
            Object t = attrs.get(UserActivitySchema.TYPE.chKey());
            if (t != null) {
                at = UserActivityType.fromRaw(String.valueOf(t));
            }
        }
        return at != null ? at.getRecordType() : RecordType.OTHER;
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

    @JsonProperty("attrs")
    public Map<String, Object> getAttrs() { return attrs; }
    public void setAttrs(Map<String, Object> attrs) {
        if (attrs == null) {
            this.attrs = null;
            return;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            normalized.put(e.getKey().toLowerCase(), e.getValue());
        }
        this.attrs = normalized;
    }

    @JsonIgnore
    public String getEventType() {
        return activityType != null ? activityType.name()
                : (attrs != null ? String.valueOf(attrs.get("type")) : null);
    }
    public void setEventType(String eventType) {
        UserActivityType at = UserActivityType.fromRaw(eventType);
        if (at != null) {
            this.activityType = at;
        }
    }

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
