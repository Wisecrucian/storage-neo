package one.idsstorage.service;

import one.idsstorage.domain.Record;
import one.idsstorage.domain.StorageAttribute;
import one.idsstorage.domain.UserActivitySchema;
import one.idsstorage.domain.UserActivityType;
import one.idsstorage.util.UuidV7;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Schema-aware data generator — produces valid {@link Record} instances
 * following the {@link UserActivitySchema} attribute definitions.
 *
 * <p>Every generated record:
 * <ol>
 *   <li>Has all three required attributes (TYPE, TIMESTAMP, USER_ID).</li>
 *   <li>Has exactly the attribute set defined for its {@link UserActivityType}
 *       in {@link UserActivitySchema#ATTRIBUTES_BY_TYPE}.</li>
 *   <li>Passes {@link StorageAttribute#validate(Object)} for every field.</li>
 * </ol>
 *
 * <p>This mirrors the Python {@code SchemaAwareRecordGenerator} from
 * {@code storage_schema_sim.py} — both produce structurally identical records,
 * ensuring benchmark consistency between Java write path and Python orchestrator.
 */
@Component
public class RecordDataGenerator {

    private static final UserActivityType[] ALL_TYPES = UserActivityType.values();
    private final Random random = new Random();

    public List<Record> generate(int count) {
        int safeCount = Math.max(1, Math.min(count, 100_000));
        return IntStream.range(0, safeCount)
                .mapToObj(i -> buildOne())
                .toList();
    }

    private Record buildOne() {
        UserActivityType activityType = ALL_TYPES[random.nextInt(ALL_TYPES.length)];
        Record r = new Record();

        // Required attributes — promoted to ClickHouse columns
        r.setTypedAttribute(UserActivitySchema.TYPE, activityType.name());
        r.setTypedAttribute(UserActivitySchema.TIMESTAMP,
                Date.from(Instant.now().minusSeconds(random.nextInt(86_400))));
        r.setTypedAttribute(UserActivitySchema.USER_ID,
                (long) (1 + random.nextInt(500_000)));

        // Type-specific attributes — go into attrs Map(String, String)
        List<StorageAttribute<?>> typeAttrs =
                UserActivitySchema.ATTRIBUTES_BY_TYPE.getOrDefault(activityType, List.of());
        for (StorageAttribute<?> attr : typeAttrs) {
            Object value = sampleValue(attr);
            if (value != null) {
                setRawAttribute(r, attr, value);
            }
        }

        // event_id: UUID v7 for time-ordered compression
        r.setEventId(UuidV7.generate().toString());
        r.setIngestTime(Instant.now());

        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setRawAttribute(Record r, StorageAttribute attr, Object value) {
        r.setTypedAttribute(attr, value);
    }

    /**
     * Generate a realistic value for the given attribute based on its type
     * and semantics. Mirrors {@code _sample_value()} from storage_schema_sim.py.
     */
    private Object sampleValue(StorageAttribute<?> attr) {
        if (attr.getType() == Boolean.class) {
            return random.nextBoolean();
        }
        if (attr.getType() == Long.class) {
            return sampleLong(attr);
        }
        if (attr.getAllowedValues() != null && attr.getAllowedValues().length > 0) {
            String[] vals = attr.getAllowedValues();
            return vals[random.nextInt(vals.length)];
        }
        // Free-form String
        return sampleString(attr);
    }

    private Long sampleLong(StorageAttribute<?> attr) {
        String name = attr.getName();
        return switch (name) {
            case "CHAT_ID" -> (long) (1 + random.nextInt(100_000));
            case "MESSAGE_ID" -> (long) (1 + random.nextInt(50_000_000));
            case "CHANNEL_ID" -> (long) (1 + random.nextInt(10_000));
            case "THREAD_ID" -> (long) (1 + random.nextInt(5_000_000));
            case "SOURCE_CHAT_ID" -> (long) (1 + random.nextInt(100_000));
            case "OTHER_USER_ID" -> (long) (1 + random.nextInt(500_000));
            case "MESSAGE_LENGTH" -> (long) (1 + random.nextInt(4_096));
            case "MODERATION_SCORE" -> (long) random.nextInt(101);
            case "REPORT_COUNT" -> (long) (1 + random.nextInt(500));
            case "AMOUNT_CENTS" -> (long) (100 + random.nextInt(100_000));
            case "MEMBER_COUNT" -> (long) (2 + random.nextInt(100_000));
            case "FILE_SIZE_KB" -> (long) (1 + random.nextInt(102_400));
            case "CALL_DURATION_SEC" -> (long) (1 + random.nextInt(7_200));
            case "PARTICIPANT_COUNT" -> (long) (2 + random.nextInt(100));
            case "ATTACHMENT_COUNT" -> (long) (1 + random.nextInt(10));
            case "QUERY_LENGTH" -> (long) (1 + random.nextInt(200));
            default -> (long) random.nextInt(10_000);
        };
    }

    private String sampleString(StorageAttribute<?> attr) {
        String name = attr.getName();
        return switch (name) {
            case "SESSION_ID" -> "sess-" + random.nextInt(1_000_000);
            case "PLATFORM_VERSION" -> random.nextInt(5) + "." + random.nextInt(20) + "." + random.nextInt(100);
            case "APP_VERSION" -> (10 + random.nextInt(15)) + "." + random.nextInt(30);
            case "ORG_HASH" -> String.valueOf(1_000_000_000L + random.nextLong(9_000_000_000L));
            case "PRODUCT_ID" -> "prod-" + random.nextInt(500);
            case "COUNTRY_CODE" -> new String[]{"RU", "US", "DE", "FR", "GB", "CN", "JP"}[random.nextInt(7)];
            case "TIMEZONE" -> new String[]{"Europe/Moscow", "America/New_York", "Europe/Berlin",
                    "Asia/Tokyo", "UTC"}[random.nextInt(5)];
            case "MIME_TYPE" -> new String[]{"image/jpeg", "image/png", "video/mp4",
                    "application/pdf", "audio/mpeg"}[random.nextInt(5)];
            default -> "val-" + random.nextInt(10_000);
        };
    }
}
