package one.idsstorage.domain;

import java.time.Instant;
import java.util.List;

/**
 * Typed query filter — replaces the legacy {@code Map<String, Object> params}
 * that was built by {@code getParamsForGroupBy()} and passed through
 * {@code ComponentQueryArgs → executeQuery → split-reduce}.
 *
 * <p>In the old system, query parameters were an untyped map with string keys
 * ({@code Args.USER_IDS}, {@code Args.DOCUMENT_TYPES}, etc.). This led to
 * runtime errors and made SQL generation fragile. {@code QueryFilter} captures
 * the same semantics with compile-time type safety.
 *
 * <p>Mapping from legacy params:
 * <pre>
 *   types=ADD_OPTION_TO_TOPIC         → activityTypes
 *   documentTypes=ANTIPHISHING        → documentTypes (maps to record_type filter)
 *   minutes=60                        → timeFrom/timeTo (converted at API layer)
 *   userIds=[123]                     → userIds
 *   otherUserIds=[456,789]            → otherUserIds
 *   documentIds=[1,2,3]              → chatIds (context-dependent mapping)
 *   documentEids=["abc"]             → eventIds
 *   chartBy=TIMESTAMP                → groupByAttribute
 *   queryString="CHAT_ID=12345"      → attrFilter (parsed into key+value)
 *   chunkSize=50, chunkStartAt=0     → limit, offset
 *   topBy=SOURCE                     → groupByAttribute + countQuery=true
 * </pre>
 *
 * <p>ClickHouse implications: every filter field maps directly to a WHERE
 * clause or GROUP BY expression. The query builder uses the primary sort key
 * {@code (user_id, record_type, event_time)} for point lookups, and the
 * projection {@code by_record_type (record_type, event_time)} for analytics.
 */
public class QueryFilter {

    // ── Event type filters ───────────────────────────────────────────────

    /** Fine-grained activity types (→ attrs['type'] IN (...)) */
    private List<UserActivityType> activityTypes;

    /** Coarse record type (→ record_type = ...) */
    private RecordType recordType;

    // ── Entity filters ───────────────────────────────────────────────────

    private List<Long> userIds;
    private List<Long> otherUserIds;
    private List<Long> chatIds;
    private List<Long> messageIds;
    private List<String> eventIds;

    // ── Time range ───────────────────────────────────────────────────────

    /** If set, only events after this time (→ event_time >= ...) */
    private Instant timeFrom;

    /** If set, only events before this time (→ event_time <= ...) */
    private Instant timeTo;

    /**
     * Shorthand: "last N minutes". Converted to timeFrom at query execution.
     * Takes precedence over timeFrom if both are set.
     */
    private Integer lastMinutes;

    // ── Attribute-level filter ───────────────────────────────────────────

    /**
     * Filter by a specific attribute value.
     * Legacy: {@code queryString="CHAT_ID=12345"} → attrName="chat_id", attrValue="12345".
     * Generates: {@code attrs['chat_id'] = '12345'} or uses materialized column
     * if the attribute is hot.
     */
    private String attrName;
    private String attrValue;

    // ── Aggregation ──────────────────────────────────────────────────────

    /**
     * GROUP BY attribute name.
     * Legacy: {@code chartBy=TIMESTAMP} or {@code topBy=SOURCE}.
     * For TIMESTAMP → GROUP BY toStartOfMinute(event_time).
     * For schema attributes → GROUP BY attrs['source'] (or materialized column).
     */
    private String groupByAttribute;

    /** If true, return counts per group rather than individual records. */
    private boolean countQuery;

    // ── Pagination ───────────────────────────────────────────────────────

    private int offset = 0;
    private int limit = 100;

    // ── Sorting ──────────────────────────────────────────────────────────

    /** Default: event_time DESC. Can override for specific use cases. */
    private String orderBy;
    private boolean orderAsc = false;

    // ══════════════════════════════════════════════════════════════════════
    // Builder-style setters (return this for chaining)
    // ══════════════════════════════════════════════════════════════════════

    public QueryFilter activityTypes(List<UserActivityType> types) {
        this.activityTypes = types;
        return this;
    }

    public QueryFilter activityType(UserActivityType type) {
        this.activityTypes = List.of(type);
        return this;
    }

    public QueryFilter recordType(RecordType recordType) {
        this.recordType = recordType;
        return this;
    }

    public QueryFilter userIds(List<Long> userIds) {
        this.userIds = userIds;
        return this;
    }

    public QueryFilter userId(Long userId) {
        this.userIds = userId == null ? null : List.of(userId);
        return this;
    }

    public QueryFilter otherUserIds(List<Long> otherUserIds) {
        this.otherUserIds = otherUserIds;
        return this;
    }

    public QueryFilter chatIds(List<Long> chatIds) {
        this.chatIds = chatIds;
        return this;
    }

    public QueryFilter messageIds(List<Long> messageIds) {
        this.messageIds = messageIds;
        return this;
    }

    public QueryFilter eventIds(List<String> eventIds) {
        this.eventIds = eventIds;
        return this;
    }

    public QueryFilter timeFrom(Instant timeFrom) {
        this.timeFrom = timeFrom;
        return this;
    }

    public QueryFilter timeTo(Instant timeTo) {
        this.timeTo = timeTo;
        return this;
    }

    public QueryFilter lastMinutes(Integer minutes) {
        this.lastMinutes = minutes;
        return this;
    }

    public QueryFilter attrFilter(String name, String value) {
        this.attrName = name;
        this.attrValue = value;
        return this;
    }

    public QueryFilter groupBy(String attribute) {
        this.groupByAttribute = attribute;
        return this;
    }

    public QueryFilter countQuery(boolean countQuery) {
        this.countQuery = countQuery;
        return this;
    }

    public QueryFilter offset(int offset) {
        this.offset = offset;
        return this;
    }

    public QueryFilter limit(int limit) {
        this.limit = limit;
        return this;
    }

    public QueryFilter orderBy(String orderBy, boolean asc) {
        this.orderBy = orderBy;
        this.orderAsc = asc;
        return this;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Getters
    // ══════════════════════════════════════════════════════════════════════

    public List<UserActivityType> getActivityTypes() { return activityTypes; }
    public RecordType getRecordType() { return recordType; }
    public List<Long> getUserIds() { return userIds; }
    public List<Long> getOtherUserIds() { return otherUserIds; }
    public List<Long> getChatIds() { return chatIds; }
    public List<Long> getMessageIds() { return messageIds; }
    public List<String> getEventIds() { return eventIds; }
    public Instant getTimeFrom() { return timeFrom; }
    public Instant getTimeTo() { return timeTo; }
    public Integer getLastMinutes() { return lastMinutes; }
    public String getAttrName() { return attrName; }
    public String getAttrValue() { return attrValue; }
    public String getGroupByAttribute() { return groupByAttribute; }
    public boolean isCountQuery() { return countQuery; }
    public int getOffset() { return offset; }
    public int getLimit() { return limit; }
    public String getOrderBy() { return orderBy; }
    public boolean isOrderAsc() { return orderAsc; }

    // ══════════════════════════════════════════════════════════════════════
    // Factory: parse from legacy HTTP params (minutes=0&types=X&...)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parse legacy query string params into a typed filter.
     * Example input: {@code minutes=60, types=MESSAGE_SENT, chartBy=TIMESTAMP, chunkSize=50}
     */
    public static QueryFilter fromParams(java.util.Map<String, String> params) {
        QueryFilter f = new QueryFilter();

        String types = params.get("types");
        if (types != null && !types.isBlank()) {
            f.activityTypes = java.util.Arrays.stream(types.split(","))
                    .map(String::trim)
                    .map(UserActivityType::fromRaw)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        String documentTypes = params.get("documentTypes");
        if (documentTypes != null && !documentTypes.isBlank()) {
            f.recordType = RecordType.fromRaw(documentTypes.trim());
        }

        String minutes = params.get("minutes");
        if (minutes != null && !minutes.isBlank()) {
            int m = Integer.parseInt(minutes);
            if (m > 0) {
                f.lastMinutes = m;
            }
        }

        String userIdStr = params.get("userId");
        if (userIdStr != null && !userIdStr.isBlank()) {
            f.userIds = List.of(Long.parseLong(userIdStr));
        }

        String chartBy = params.get("chartBy");
        if (chartBy != null && !chartBy.isBlank()) {
            f.groupByAttribute = chartBy.trim();
        }

        String topBy = params.get("topBy");
        if (topBy != null && !topBy.isBlank()) {
            f.groupByAttribute = topBy.trim();
            f.countQuery = true;
        }

        String queryString = params.get("queryString");
        if (queryString != null && queryString.contains("=")) {
            String[] parts = queryString.split("=", 2);
            f.attrName = parts[0].trim().toLowerCase();
            f.attrValue = parts[1].trim();
        }

        String chunkSize = params.get("chunkSize");
        if (chunkSize != null && !chunkSize.isBlank()) {
            f.limit = Math.min(Integer.parseInt(chunkSize), 5000);
        }

        String chunkStartAt = params.get("chunkStartAt");
        if (chunkStartAt != null && !chunkStartAt.isBlank()) {
            f.offset = Integer.parseInt(chunkStartAt);
        }

        return f;
    }
}
