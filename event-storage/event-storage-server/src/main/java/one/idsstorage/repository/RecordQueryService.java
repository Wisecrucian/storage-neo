package one.idsstorage.repository;

import one.idsstorage.domain.AggregationQuery;
import one.idsstorage.domain.AggregationResult;
import one.idsstorage.domain.ModerationFilter;
import one.idsstorage.domain.Record;

import java.util.List;
import java.util.Map;

public interface RecordQueryService {
    List<Record> findForModeration(ModerationFilter filter);

    List<Record> search(Map<String, String> filters, int limit);

    List<AggregationResult> getAnalytics(AggregationQuery query);

    List<Map<String, Object>> runAnalyticsScenario(String scenario, String fromIso, String toIso, int limit);
}
