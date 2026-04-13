package one.idsstorage.clickhouse;

import one.idsstorage.domain.AggregationQuery;
import one.idsstorage.domain.AggregationResult;
import one.idsstorage.domain.ModerationFilter;
import one.idsstorage.domain.Record;
import one.idsstorage.metrics.RecordStorageMetrics;
import one.idsstorage.repository.RecordQueryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ClickHouseRecordQueryService implements RecordQueryService {
    private static final Pattern SAFE_ATTR = Pattern.compile("[A-Za-z0-9_]+");
    private final ClickHouseClient clickHouseClient;
    private final ClickHouseMapper mapper;
    private final RecordStorageMetrics metrics;

    public ClickHouseRecordQueryService(
            ClickHouseClient clickHouseClient,
            ClickHouseMapper mapper,
            RecordStorageMetrics metrics
    ) {
        this.clickHouseClient = clickHouseClient;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    public List<Record> findForModeration(ModerationFilter filter) {
        long started = System.nanoTime();
        try {
            int safeLimit = Math.max(1, Math.min(filter == null ? 100 : filter.getLimit(), 1000));
            String where = buildModerationWhere(filter);
            String sql = "SELECT event_time, ingest_time, record_type, event_id, attrs " +
                    "FROM default.events_raw " +
                    "WHERE " + where + " " +
                    "ORDER BY event_time DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";
            String raw = clickHouseClient.query(sql);
            List<Record> rows = mapper.toRecordListFromJsonEachRow(raw);
            Map<String, Integer> byRecordType = new LinkedHashMap<>();
            for (Record row : rows) {
                String rt = row.getCoarseRecordType().name();
                byRecordType.merge(rt, 1, Integer::sum);
            }
            metrics.markReadWithRecordTypeBreakdown("moderation", System.nanoTime() - started, byRecordType);
            return rows;
        } catch (Exception e) {
            throw new RuntimeException("Failed moderation query", e);
        }
    }

    @Override
    public List<Record> search(Map<String, String> filters, int limit) {
        long started = System.nanoTime();
        try {
            int safeLimit = Math.max(1, Math.min(limit, 1000));
            String where = buildSearchWhere(filters);
            String sql = "SELECT event_time, ingest_time, record_type, event_id, attrs " +
                    "FROM default.events_raw " +
                    "WHERE " + where + " " +
                    "ORDER BY event_time DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";
            String raw = clickHouseClient.query(sql);
            return mapper.toRecordListFromJsonEachRow(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed search query", e);
        } finally {
            metrics.markRead("search", System.nanoTime() - started);
        }
    }

    @Override
    public List<AggregationResult> getAnalytics(AggregationQuery query) {
        long started = System.nanoTime();
        try {
            int safeLimit = Math.max(1, Math.min(query == null ? 1000 : query.getLimit(), 5000));
            String intervalExpr = mapIntervalToBucket(query == null ? "minute" : query.getInterval());
            String from = safeTs(query == null ? null : query.getFromIso(), "now() - INTERVAL 1 HOUR");
            String to = safeTs(query == null ? null : query.getToIso(), "now()");
            String sql = "SELECT " + intervalExpr + " AS bucket, record_type, count() AS count, count() / 60.0 AS rate " +
                    "FROM default.events_raw " +
                    "WHERE event_time >= " + from + " AND event_time <= " + to + " " +
                    "GROUP BY bucket, record_type " +
                    "ORDER BY bucket DESC, count DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";
            String raw = clickHouseClient.query(sql);
            return mapper.toAggregationResults(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed analytics query", e);
        } finally {
            metrics.markRead("analytics", System.nanoTime() - started);
        }
    }

    @Override
    public List<Map<String, Object>> runAnalyticsScenario(String scenario, String fromIso, String toIso, int limit) {
        long started = System.nanoTime();
        try {
            String safeScenario = scenario == null ? "events_by_type_timeseries" : scenario.trim().toLowerCase();
            int safeLimit = Math.max(1, Math.min(limit, 5000));
            String from = safeTs(fromIso, "now() - INTERVAL 24 HOUR");
            String to = safeTs(toIso, "now()");

            String sql;
            switch (safeScenario) {
                case "top_users_by_volume":
                    sql = "SELECT user_id, record_type, count() AS events_count " +
                            "FROM default.events_raw " +
                            "WHERE event_time >= " + from + " AND event_time <= " + to + " AND user_id IS NOT NULL " +
                            "GROUP BY user_id, record_type " +
                            "ORDER BY events_count DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";
                    break;
                case "flagged_users_spike":
                    sql = "SELECT user_id, countIf(record_type = 'MODERATION') AS flags_count, count() AS total_events, " +
                            "round(flags_count / greatest(total_events, 1), 4) AS flags_ratio " +
                            "FROM default.events_raw " +
                            "WHERE event_time >= " + from + " AND event_time <= " + to + " AND user_id IS NOT NULL " +
                            "GROUP BY user_id HAVING flags_count >= 3 " +
                            "ORDER BY flags_count DESC, flags_ratio DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";
                    break;
                case "attrs_source_lang_breakdown":
                    sql = "SELECT attrs['source'] AS source, attrs['lang'] AS lang, count() AS count " +
                            "FROM default.events_raw " +
                            "WHERE event_time >= " + from + " AND event_time <= " + to + " " +
                            "GROUP BY source, lang ORDER BY count DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";
                    break;
                case "record_type_mix":
                    sql = "SELECT record_type, count() AS count " +
                            "FROM default.events_raw " +
                            "WHERE event_time >= " + from + " AND event_time <= " + to + " " +
                            "GROUP BY record_type ORDER BY count DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";
                    break;
                case "events_by_type_timeseries":
                default:
                    sql = "SELECT toStartOfMinute(event_time) AS bucket, record_type, count() AS count, count() / 60.0 AS rate " +
                            "FROM default.events_raw " +
                            "WHERE event_time >= " + from + " AND event_time <= " + to + " " +
                            "GROUP BY bucket, record_type " +
                            "ORDER BY bucket DESC, count DESC LIMIT " + safeLimit + " FORMAT JSONEachRow";
                    break;
            }

            String raw = clickHouseClient.query(sql);
            return mapper.toMapList(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed analytics scenario query", e);
        } finally {
            metrics.markRead("analytics", System.nanoTime() - started);
        }
    }

    private String buildModerationWhere(ModerationFilter f) {
        if (f == null) {
            return "1 = 1";
        }
        List<String> conditions = new ArrayList<>();
        if (f.getRecordType() != null && !f.getRecordType().isBlank()) {
            String escaped = f.getRecordType().replace("'", "").toUpperCase();
            conditions.add("record_type = '" + escaped + "'");
        }
        return conditions.isEmpty() ? "1 = 1" : String.join(" AND ", conditions);
    }

    private String mapIntervalToBucket(String interval) {
        if ("hour".equalsIgnoreCase(interval)) {
            return "toStartOfHour(event_time)";
        }
        if ("second".equalsIgnoreCase(interval)) {
            return "toStartOfSecond(event_time)";
        }
        return "toStartOfMinute(event_time)";
    }

    private String safeTs(String iso, String fallbackExpr) {
        if (iso == null || iso.isBlank()) {
            return fallbackExpr;
        }
        String escaped = iso.replace("'", "");
        return "parseDateTimeBestEffort('" + escaped + "')";
    }

    private String buildSearchWhere(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return "1 = 1";
        }
        List<String> conditions = new ArrayList<>();
        for (Map.Entry<String, String> e : filters.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String key = e.getKey().trim();
            String value = e.getValue().trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            String upper = key.toUpperCase();
            switch (upper) {
                case "USER_ID":
                    conditions.add("user_id = " + parseLong(value, "USER_ID"));
                    break;
                case "RECORD_TYPE":
                    conditions.add("record_type = '" + value.replace("'", "").toUpperCase() + "'");
                    break;
                case "EVENT_ID":
                    conditions.add("event_id = '" + value.replace("'", "") + "'");
                    break;
                default:
                    if (!SAFE_ATTR.matcher(upper).matches()) {
                        throw new IllegalArgumentException("Invalid attribute name: " + key);
                    }
                    String attrKey = upper.toLowerCase();
                    conditions.add("attrs['" + attrKey + "'] = '" + escapeSqlLiteral(value) + "'");
                    break;
            }
        }
        return conditions.isEmpty() ? "1 = 1" : String.join(" AND ", conditions);
    }

    private long parseLong(String value, String fieldName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(fieldName + " must be a number: " + value);
        }
    }

    private String escapeSqlLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
