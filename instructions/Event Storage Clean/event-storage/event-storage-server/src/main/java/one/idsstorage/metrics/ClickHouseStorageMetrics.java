package one.idsstorage.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import one.idsstorage.clickhouse.ClickHouseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ClickHouseStorageMetrics {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseStorageMetrics.class);

    private final ClickHouseClient client;
    private final MeterRegistry registry;
    private final AtomicLong compressedBytes = new AtomicLong(0);
    private final AtomicLong uncompressedBytes = new AtomicLong(0);
    private final AtomicLong activeParts = new AtomicLong(0);
    private final AtomicLong totalRows = new AtomicLong(0);
    private final Map<String, AtomicLong> projectionCompressedBytes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> projectionUncompressedBytes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> projectionRows = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> projectionParts = new ConcurrentHashMap<>();

    public ClickHouseStorageMetrics(ClickHouseClient client, MeterRegistry registry) {
        this.client = client;
        this.registry = registry;
        Gauge.builder("ch_storage_compressed_bytes", compressedBytes, AtomicLong::doubleValue).register(registry);
        Gauge.builder("ch_storage_uncompressed_bytes", uncompressedBytes, AtomicLong::doubleValue).register(registry);
        Gauge.builder("ch_storage_active_parts", activeParts, AtomicLong::doubleValue).register(registry);
        Gauge.builder("ch_storage_total_rows", totalRows, AtomicLong::doubleValue).register(registry);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 5000)
    public void collect() {
        try {
            String sql = "SELECT sum(data_compressed_bytes) AS compressed, " +
                    "sum(data_uncompressed_bytes) AS uncompressed, " +
                    "count() AS parts, " +
                    "sum(rows) AS total_rows " +
                    "FROM system.parts WHERE table = 'events_raw' AND active = 1 FORMAT TSV";
            String result = client.query(sql).trim();
            if (result.isEmpty()) return;

            String[] cols = result.split("\t");
            if (cols.length >= 4) {
                compressedBytes.set(Long.parseLong(cols[0]));
                uncompressedBytes.set(Long.parseLong(cols[1]));
                activeParts.set(Long.parseLong(cols[2]));
                totalRows.set(Long.parseLong(cols[3]));
            }
            collectProjectionMetrics();
        } catch (Exception e) {
            log.debug("Failed to collect CH storage metrics: {}", e.getMessage());
        }
    }

    private void collectProjectionMetrics() {
        try {
            String sql = "SELECT name, " +
                    "sum(data_compressed_bytes) AS compressed, " +
                    "sum(data_uncompressed_bytes) AS uncompressed, " +
                    "sum(rows) AS total_rows, " +
                    "count() AS parts " +
                    "FROM system.projection_parts " +
                    "WHERE database = 'default' AND table = 'events_raw' AND active = 1 " +
                    "GROUP BY name FORMAT TSV";
            String raw = client.query(sql).trim();
            if (raw.isEmpty()) {
                return;
            }
            for (String line : raw.split("\n")) {
                if (line == null || line.isBlank()) continue;
                String[] cols = line.split("\t");
                if (cols.length < 5) continue;
                String projection = sanitizeProjection(cols[0]);
                updateGauge(projectionCompressedBytes, "ch_projection_compressed_bytes", projection, Long.parseLong(cols[1]));
                updateGauge(projectionUncompressedBytes, "ch_projection_uncompressed_bytes", projection, Long.parseLong(cols[2]));
                updateGauge(projectionRows, "ch_projection_rows", projection, Long.parseLong(cols[3]));
                updateGauge(projectionParts, "ch_projection_parts", projection, Long.parseLong(cols[4]));
            }
        } catch (Exception e) {
            log.debug("Failed to collect CH projection metrics: {}", e.getMessage());
        }
    }

    private void updateGauge(Map<String, AtomicLong> map, String metricName, String projection, long value) {
        AtomicLong holder = map.computeIfAbsent(projection, p -> {
            AtomicLong gaugeValue = new AtomicLong(0);
            Gauge.builder(metricName, gaugeValue, AtomicLong::doubleValue)
                    .tag("projection", p)
                    .register(registry);
            return gaugeValue;
        });
        holder.set(value);
    }

    private String sanitizeProjection(String projection) {
        if (projection == null || projection.isBlank()) {
            return "unknown";
        }
        return projection.trim();
    }
}
