package one.idsstorage.domain;

import java.time.Instant;
import java.util.List;

public class QueryFilter {
    private List<UserActivityType> activityTypes;
    private RecordType recordType;
    private List<String> eventIds;
    private Instant timeFrom;
    private Instant timeTo;
    private Integer lastMinutes;
    private String attrName;
    private String attrValue;
    private String groupByAttribute;
    private boolean countQuery;
    private int offset = 0;
    private int limit = 100;
    private String orderBy;
    private boolean orderAsc = false;

    public QueryFilter activityTypes(List<UserActivityType> types) { this.activityTypes = types; return this; }
    public QueryFilter activityType(UserActivityType type) { this.activityTypes = List.of(type); return this; }
    public QueryFilter recordType(RecordType value) { this.recordType = value; return this; }
    public QueryFilter eventIds(List<String> value) { this.eventIds = value; return this; }
    public QueryFilter timeFrom(Instant value) { this.timeFrom = value; return this; }
    public QueryFilter timeTo(Instant value) { this.timeTo = value; return this; }
    public QueryFilter lastMinutes(Integer value) { this.lastMinutes = value; return this; }
    public QueryFilter attrFilter(String name, String value) { this.attrName = name; this.attrValue = value; return this; }
    public QueryFilter groupBy(String value) { this.groupByAttribute = value; return this; }
    public QueryFilter countQuery(boolean value) { this.countQuery = value; return this; }
    public QueryFilter offset(int value) { this.offset = value; return this; }
    public QueryFilter limit(int value) { this.limit = value; return this; }
    public QueryFilter orderBy(String value, boolean asc) { this.orderBy = value; this.orderAsc = asc; return this; }

    public List<UserActivityType> getActivityTypes() { return activityTypes; }
    public RecordType getRecordType() { return recordType; }
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
            if (m > 0) f.lastMinutes = m;
        }

        String chartBy = params.get("chartBy");
        if (chartBy != null && !chartBy.isBlank()) f.groupByAttribute = chartBy.trim();

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
