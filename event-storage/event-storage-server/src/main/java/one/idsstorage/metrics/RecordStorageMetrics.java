package one.idsstorage.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RecordStorageMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger queueSize = new AtomicInteger(0);

    private final Counter writesSuccess;
    private final Counter recordsWritten;
    private final Counter writesFailed;
    private final Counter writeErrors;
    private final Timer writeLatency;
    private final Timer batchWaitTime;
    private final Timer batchFlushLatency;
    private final DistributionSummary batchSize;

    private final ConcurrentHashMap<String, Counter> recordTypeWriteCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> readRequestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> readLatencyTimers = new ConcurrentHashMap<>();

    public RecordStorageMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("batch_queue_size", queueSize, AtomicInteger::get).register(registry);

        this.writesSuccess = Counter.builder("writes_success_total").register(registry);
        this.recordsWritten = Counter.builder("records_written_total").register(registry);
        this.writesFailed = Counter.builder("writes_failed_total").register(registry);
        this.writeErrors = Counter.builder("write_errors_total").register(registry);

        this.writeLatency = Timer.builder("write_latency_ms")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.batchWaitTime = Timer.builder("batch_wait_time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.batchFlushLatency = Timer.builder("batch_flush_latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.batchSize = DistributionSummary.builder("batch_size")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void setQueueSize(int size) {
        queueSize.set(Math.max(size, 0));
    }

    public void markWriteSuccess(int count, long waitNanos, long flushNanos) {
        writesSuccess.increment();
        recordsWritten.increment(count);
        batchSize.record(count);
        batchWaitTime.record(waitNanos, TimeUnit.NANOSECONDS);
        batchFlushLatency.record(flushNanos, TimeUnit.NANOSECONDS);
        writeLatency.record(waitNanos + flushNanos, TimeUnit.NANOSECONDS);
    }

    public void markWriteByRecordType(Map<String, Integer> countsByRecordType, long totalLatencyNanos) {
        if (countsByRecordType == null || countsByRecordType.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> e : countsByRecordType.entrySet()) {
            String type = sanitize(e.getKey());
            int count = e.getValue() == null ? 0 : e.getValue();
            if (count <= 0) continue;
            recordTypeWriteCounters.computeIfAbsent(type, t ->
                    Counter.builder("records_written_by_record_type_total")
                            .tag("record_type", t)
                            .register(registry)
            ).increment(count);
        }
    }

    public void markWriteError() {
        writesFailed.increment();
        writeErrors.increment();
    }

    public void markRead(String readType, long latencyNanos) {
        readRequestCounters.computeIfAbsent(readType, t ->
                Counter.builder("read_requests_total")
                        .tag("read_type", t)
                        .register(registry)
        ).increment();
        readLatencyTimers.computeIfAbsent(readType, t ->
                Timer.builder("read_latency_ms")
                        .tag("read_type", t)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry)
        ).record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    public void markReadWithRecordTypeBreakdown(String readType, long latencyNanos, Map<String, Integer> byRecordType) {
        markRead(readType, latencyNanos);
    }

    private String sanitize(String type) {
        if (type == null || type.isBlank()) return "unknown";
        return type.trim();
    }
}
