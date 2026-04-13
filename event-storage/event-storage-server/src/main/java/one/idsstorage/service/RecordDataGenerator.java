package one.idsstorage.service;

import one.idsstorage.domain.Record;
import one.idsstorage.domain.RecordType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

@Component
public class RecordDataGenerator {
    private static final List<String> TYPES = List.of("message_sent", "message_deleted", "moderation_flag", "purchase");
    private static final List<RecordType> RECORD_TYPES = List.of(
            RecordType.MESSAGE,
            RecordType.MODERATION,
            RecordType.PURCHASE,
            RecordType.SYSTEM
    );
    private static final int ATTRS_MIN = 5;
    private static final int ATTRS_MAX = 30;
    private static final int ATTRS_MEAN = 15;
    private static final int ATTRS_GAUSS_STDDEV = 4; // clamp -> overall mean is close to ATTRS_MEAN
    private final Random random = new Random();

    public List<Record> generate(int count) {
        int safeCount = Math.max(1, Math.min(count, 100_000));
        return IntStream.range(0, safeCount)
                .mapToObj(this::buildOne)
                .toList();
    }

    private Record buildOne(int idx) {
        Record r = new Record();
        Instant now = Instant.now().minusSeconds(random.nextInt(3600));
        r.setEventTime(now);
        r.setIngestTime(Instant.now());
        r.setEventType(TYPES.get(random.nextInt(TYPES.size())));
        RecordType recordType = RECORD_TYPES.get(random.nextInt(RECORD_TYPES.size()));
        r.setRecordType(recordType);
        r.setEventId(UUID.randomUUID().toString());
        r.setUserId((long) (1000 + random.nextInt(10000)));
        r.setChatId((long) (100 + random.nextInt(5000)));
        r.setMessageId((long) (1_000_000 + idx));
        int attrsCountTotal = sampleAttrsCountTotal();
        int attrsCountExtra = Math.max(0, attrsCountTotal - 1); // -1 because RecordStorage ClickHouseMapper injects recordType

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("source", random.nextBoolean() ? "mobile" : "web");
        attrs.put("lang", random.nextBoolean() ? "ru" : "en");
        attrs.put("score", random.nextInt(100));
        attrs.put("campaign", "cmp-" + random.nextInt(20));

        int remaining = Math.max(0, attrsCountExtra - attrs.size());
        int added = 0;
        while (added < remaining) {
            String key = "k" + added;
            // deterministic-ish values to reduce JSON overhead variability
            Object value = (added % 3 == 0)
                    ? random.nextInt(10000)
                    : (added % 3 == 1 ? (double) random.nextInt(100000) / 100.0 : "v-" + random.nextInt(1000));
            attrs.put(key, value);
            added++;
        }

        r.setAttrs(attrs);
        return r;
    }

    private int sampleAttrsCountTotal() {
        // Use Gaussian to approximate "around 15" distribution; then clamp to [5..30].
        int v = (int) Math.round(random.nextGaussian() * ATTRS_GAUSS_STDDEV + ATTRS_MEAN);
        if (v < ATTRS_MIN) {
            return ATTRS_MIN;
        }
        if (v > ATTRS_MAX) {
            return ATTRS_MAX;
        }
        return v;
    }
}
