package one.idsstorage.clickhouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HotAttributeManager {
    private static final Logger log = LoggerFactory.getLogger(HotAttributeManager.class);
    private static final String TABLE = "default.events_raw";

    private final ClickHouseClient client;
    private final HotAttributeConfig config;

    public HotAttributeManager(ClickHouseClient client, HotAttributeConfig config) {
        this.client = client;
        this.config = config;
    }

    public List<Map<String, Object>> status() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> existingColumns = getExistingColumns();

        for (HotAttributeConfig.HotColumn col : config.getColumns()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", col.getName());
            entry.put("sourceKey", col.getSourceKey());
            entry.put("chType", col.getChType());
            entry.put("expression", col.buildMaterializeExpression());
            entry.put("exists", existingColumns.contains(col.getName()));
            entry.put("sql", col.toAlterSql(TABLE));
            result.add(entry);
        }
        return result;
    }

    public Map<String, Object> applyAll() {
        List<String> existingColumns = getExistingColumns();
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (HotAttributeConfig.HotColumn col : config.getColumns()) {
            if (existingColumns.contains(col.getName())) {
                skipped.add(col.getName());
                continue;
            }
            try {
                client.query(col.toAlterSql(TABLE));
                created.add(col.getName());
                log.info("Created hot attribute column: {}", col.getName());
            } catch (Exception e) {
                errors.add(col.getName() + ": " + e.getMessage());
                log.error("Failed to create hot attribute column: {}", col.getName(), e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return result;
    }

    private List<String> getExistingColumns() {
        try {
            String raw = client.query(
                    "SELECT name FROM system.columns WHERE database='default' AND table='events_raw' FORMAT TSV"
            );
            List<String> cols = new ArrayList<>();
            for (String line : raw.split("\n")) {
                if (line != null && !line.isBlank()) cols.add(line.trim());
            }
            return cols;
        } catch (Exception e) {
            log.warn("Cannot read columns from CH: {}", e.getMessage());
            return List.of();
        }
    }
}
