package one.idsstorage.clickhouse;

import one.idsstorage.domain.Record;
import one.idsstorage.domain.UserActivitySchema;
import one.idsstorage.metrics.RecordStorageMetrics;
import one.idsstorage.repository.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Repository
public class ClickHouseRecordRepository implements RecordRepository {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseRecordRepository.class);

    static final String TOPIC = "events.raw";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ClickHouseMapper mapper;
    private final RecordStorageMetrics metrics;
    private final int configuredBatchSize;

    public ClickHouseRecordRepository(
            KafkaTemplate<String, String> kafkaTemplate,
            ClickHouseMapper mapper,
            RecordStorageMetrics metrics,
            @Value("${app.records.batch-size:1000}") int configuredBatchSize
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
        this.metrics = metrics;
        this.configuredBatchSize = Math.max(500, Math.min(configuredBatchSize, 10000));
    }

    @Override
    public void saveAll(List<Record> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (Record r : records) {
            UserActivitySchema.validateStrict(r);
        }

        int total = records.size();
        int offset = 0;
        while (offset < total) {
            int end = Math.min(offset + configuredBatchSize, total);
            List<Record> batch = records.subList(offset, end);
            long waitStarted = System.nanoTime();
            metrics.setQueueSize(total - offset);
            try {
                flushBatch(batch, waitStarted);
            } catch (Exception e) {
                metrics.markWriteError();
                log.error("Failed to flush record batch of size {}", batch.size(), e);
                throw new RuntimeException("Failed to publish records to Kafka", e);
            } finally {
                metrics.setQueueSize(total - end);
            }
            offset = end;
        }
    }

    private void flushBatch(List<Record> batch, long waitStarted)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        long flushStarted = System.nanoTime();
        Map<String, Integer> countsByRecordType = new LinkedHashMap<>();
        List<org.apache.kafka.clients.producer.ProducerRecord<String, String>> kafkaRecords = new ArrayList<>(batch.size());
        for (Record r : batch) {
            String json = mapper.toJsonEachRowLine(r);
            String recordType = (r == null) ? "OTHER" : r.getCoarseRecordType().name();
            countsByRecordType.merge(recordType, 1, Integer::sum);
            kafkaRecords.add(new org.apache.kafka.clients.producer.ProducerRecord<>(TOPIC, recordType, json));
        }

        var lastFuture = kafkaTemplate.send(kafkaRecords.get(kafkaRecords.size() - 1));
        for (int i = 0; i < kafkaRecords.size() - 1; i++) {
            kafkaTemplate.send(kafkaRecords.get(i));
        }
        lastFuture.get(10, TimeUnit.SECONDS);

        long now = System.nanoTime();
        long totalLatency = now - waitStarted;
        metrics.markWriteSuccess(batch.size(), flushStarted - waitStarted, now - flushStarted);
        metrics.markWriteByRecordType(countsByRecordType, totalLatency);
    }
}
