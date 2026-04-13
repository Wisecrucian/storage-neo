package one.idsstorage.controller;

import one.idsstorage.clickhouse.HotAttributeManager;
import one.idsstorage.domain.AggregationQuery;
import one.idsstorage.domain.AggregationResult;
import one.idsstorage.domain.ModerationFilter;
import one.idsstorage.domain.Record;
import one.idsstorage.repository.RecordQueryService;
import one.idsstorage.repository.RecordRepository;
import one.idsstorage.service.RecordDataGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/records")
public class RecordStorageController {
    private final RecordRepository recordRepository;
    private final RecordQueryService recordQueryService;
    private final RecordDataGenerator generator;
    private final HotAttributeManager hotAttributeManager;

    public RecordStorageController(
            RecordRepository recordRepository,
            RecordQueryService recordQueryService,
            RecordDataGenerator generator,
            HotAttributeManager hotAttributeManager
    ) {
        this.recordRepository = recordRepository;
        this.recordQueryService = recordQueryService;
        this.generator = generator;
        this.hotAttributeManager = hotAttributeManager;
    }

    @PostMapping("/save-batch")
    public ResponseEntity<Map<String, Object>> saveBatch(@RequestBody List<Record> records) {
        try {
            recordRepository.saveAll(records);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("status", "ok", "written", records == null ? 0 : records.size()));
    }

    @GetMapping("/moderation")
    public ResponseEntity<List<Record>> findForModeration(
            @RequestParam(required = false) String recordType,
            @RequestParam(defaultValue = "100") int limit
    ) {
        ModerationFilter f = new ModerationFilter();
        f.setRecordType(recordType);
        f.setLimit(limit);
        return ResponseEntity.ok(recordQueryService.findForModeration(f));
    }

    @PostMapping("/search")
    public ResponseEntity<List<Record>> search(@RequestBody Map<String, Object> body) {
        int limit = asInt(body == null ? null : body.get("limit"), 100);
        Map<String, String> filters = new LinkedHashMap<>();
        if (body != null && body.get("filters") instanceof Map<?, ?> rawFilters) {
            for (Map.Entry<?, ?> e : rawFilters.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                filters.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return ResponseEntity.ok(recordQueryService.search(filters, limit));
    }

    @GetMapping("/analytics")
    public ResponseEntity<List<AggregationResult>> analytics(
            @RequestParam(required = false) String fromIso,
            @RequestParam(required = false) String toIso,
            @RequestParam(defaultValue = "minute") String interval,
            @RequestParam(defaultValue = "1000") int limit
    ) {
        AggregationQuery q = new AggregationQuery();
        q.setFromIso(fromIso);
        q.setToIso(toIso);
        q.setInterval(interval);
        q.setLimit(limit);
        return ResponseEntity.ok(recordQueryService.getAnalytics(q));
    }

    @GetMapping("/analytics/scenarios")
    public ResponseEntity<Map<String, Object>> analyticsScenarios() {
        return ResponseEntity.ok(Map.of(
                "scenarios", List.of(
                        Map.of(
                                "id", "events_by_type_timeseries",
                                "title", "Events by type over time",
                                "goal", "traffic dynamics and rate per event_type"
                        ),
                        Map.of(
                                "id", "top_users_by_volume",
                                "title", "Top users by event volume",
                                "goal", "identify heavy activity by users"
                        ),
                        Map.of(
                                "id", "flagged_users_spike",
                                "title", "Users with moderation flags spike",
                                "goal", "detect users with abnormal moderation_flag density"
                        ),
                        Map.of(
                                "id", "attrs_source_lang_breakdown",
                                "title", "Source/Lang breakdown from attrs",
                                "goal", "slice traffic by custom attributes"
                        ),
                        Map.of(
                                "id", "record_type_mix",
                                "title", "Record type mix",
                                "goal", "distribution by attrs.recordType enum"
                        )
                )
        ));
    }

    @GetMapping("/analytics/run")
    public ResponseEntity<List<Map<String, Object>>> runAnalyticsScenario(
            @RequestParam(defaultValue = "events_by_type_timeseries") String scenario,
            @RequestParam(required = false) String fromIso,
            @RequestParam(required = false) String toIso,
            @RequestParam(defaultValue = "500") int limit
    ) {
        return ResponseEntity.ok(recordQueryService.runAnalyticsScenario(scenario, fromIso, toIso, limit));
    }

    @PostMapping("/generate-and-save")
    public ResponseEntity<Map<String, Object>> generateAndSave(
            @RequestParam(defaultValue = "1000") int count
    ) {
        List<Record> data = generator.generate(count);
        recordRepository.saveAll(data);
        return ResponseEntity.ok(Map.of("status", "ok", "generated", data.size()));
    }

    @PostMapping("/simulate-load")
    public ResponseEntity<Map<String, Object>> simulateLoad(
            @RequestParam(defaultValue = "10") int rounds,
            @RequestParam(defaultValue = "1000") int perRound
    ) {
        int safeRounds = Math.max(1, Math.min(rounds, 200));
        int safePerRound = Math.max(500, Math.min(perRound, 2000));
        long total = 0;
        for (int i = 0; i < safeRounds; i++) {
            List<Record> batch = generator.generate(safePerRound);
            recordRepository.saveAll(batch);
            total += batch.size();
        }
        return ResponseEntity.ok(Map.of("status", "ok", "rounds", safeRounds, "written", total));
    }

    @GetMapping("/hot-attributes")
    public ResponseEntity<List<Map<String, Object>>> hotAttributesStatus() {
        return ResponseEntity.ok(hotAttributeManager.status());
    }

    @PostMapping("/hot-attributes/apply")
    public ResponseEntity<Map<String, Object>> hotAttributesApply() {
        return ResponseEntity.ok(hotAttributeManager.applyAll());
    }

    private int asInt(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
