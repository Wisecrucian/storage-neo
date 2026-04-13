package one.idsstorage.domain;

import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema definition for user activity events — mirrors the legacy
 * {@code UserActivitySchema} + {@code StorageSchemaBuilder} pattern.
 *
 * <p>Design decisions and their ClickHouse implications:
 * <ul>
 *   <li><b>Required attributes</b> ({@code TYPE}, {@code TIMESTAMP}, {@code USER_ID})
 *       map to dedicated ClickHouse columns ({@code record_type}, {@code event_time},
 *       {@code user_id}) — they participate in the primary sort key and projections.</li>
 *   <li><b>Type-specific attributes</b> (2–15 per {@link UserActivityType}) map to
 *       {@code attrs Map(String, String)} — flexible, no DDL changes on schema evolution.</li>
 *   <li><b>Hot attributes</b> (frequently filtered/aggregated) can be promoted to
 *       materialized columns via {@link one.idsstorage.clickhouse.HotAttributeManager}
 *       without changing the write path.</li>
 * </ul>
 *
 * <p>Type-specific attribute sets are defined in {@link #ATTRIBUTES_BY_TYPE}.
 * {@link #validateStrict(Record)} rejects any {@code attrs} key outside
 * {@link #REQUIRED_ATTRIBUTES} plus the list for the resolved {@link UserActivityType}.
 *
 * @see StorageAttribute
 * @see UserActivityType
 */
public final class UserActivitySchema {

    /**
     * Schema version — increment when a breaking change occurs
     * (attribute removed or type changed). Matches legacy VERSION = 28.
     */
    public static final int VERSION = 28;

    private static int idx = 0;
    private static <T> StorageAttribute<T> attr(String name, Class<T> type) {
        return new StorageAttribute<>(name, type, idx++);
    }
    private static <T> StorageAttribute<T> attr(String name, Class<T> type, String... allowed) {
        return new StorageAttribute<>(name, type, idx++, allowed);
    }

    // ── Required (always present) ────────────────────────────────────────

    public static final StorageAttribute<String> TYPE =
            attr("TYPE", String.class,
                    "REGISTRATION", "LOGIN", "LOGOUT",
                    "MESSAGE_SENT", "MESSAGE_DELETED", "MESSAGE_EDITED", "MESSAGE_READ",
                    "REACTION_ADDED", "REACTION_REMOVED",
                    "MODERATION_FLAG", "MODERATION_APPROVE", "MODERATION_REJECT",
                    "PURCHASE_INITIATED", "PURCHASE_COMPLETED", "PURCHASE_REFUNDED",
                    "PROFILE_UPDATED", "AVATAR_CHANGED", "STATUS_CHANGED",
                    "CHANNEL_CREATED", "CHANNEL_JOINED", "CHANNEL_LEFT", "CHANNEL_DELETED",
                    "FILE_UPLOADED", "FILE_DOWNLOADED", "FILE_DELETED",
                    "CALL_STARTED", "CALL_ENDED", "CALL_MISSED",
                    "SEARCH_PERFORMED", "NOTIFICATION_RECEIVED");

    public static final StorageAttribute<Date> TIMESTAMP = attr("TIMESTAMP", Date.class);
    public static final StorageAttribute<Long> USER_ID = attr("USER_ID", Long.class);

    // ── Session / identity ───────────────────────────────────────────────

    public static final StorageAttribute<String> SESSION_ID = attr("SESSION_ID", String.class);
    public static final StorageAttribute<String> USER_DEVICE_TYPE =
            attr("USER_DEVICE_TYPE", String.class, "ANDROID", "IOS", "WEB", "DESKTOP");
    public static final StorageAttribute<String> PLATFORM_VERSION = attr("PLATFORM_VERSION", String.class);
    public static final StorageAttribute<String> APP_VERSION = attr("APP_VERSION", String.class);
    public static final StorageAttribute<String> TERMINAL_TYPE =
            attr("TERMINAL_TYPE", String.class, "ONEME", "BASIC", "PRO", "ENTERPRISE");
    public static final StorageAttribute<String> ORG_HASH = attr("ORG_HASH", String.class);
    public static final StorageAttribute<String> GENDER =
            attr("GENDER", String.class, "0", "1", "2");
    public static final StorageAttribute<Boolean> USER_ACTIVE_STATUS = attr("USER_ACTIVE_STATUS", Boolean.class);
    public static final StorageAttribute<String> LANG =
            attr("LANG", String.class, "ru", "en", "de", "fr", "zh", "es");
    public static final StorageAttribute<String> SOURCE =
            attr("SOURCE", String.class, "mobile", "web", "backend", "desktop");

    // ── Message ──────────────────────────────────────────────────────────

    public static final StorageAttribute<Long> CHAT_ID = attr("CHAT_ID", Long.class);
    public static final StorageAttribute<Long> MESSAGE_ID = attr("MESSAGE_ID", Long.class);
    public static final StorageAttribute<Long> MESSAGE_LENGTH = attr("MESSAGE_LENGTH", Long.class);
    public static final StorageAttribute<Boolean> HAS_ATTACHMENTS = attr("HAS_ATTACHMENTS", Boolean.class);
    public static final StorageAttribute<Long> ATTACHMENT_COUNT = attr("ATTACHMENT_COUNT", Long.class);
    public static final StorageAttribute<String> ATTACHMENT_TYPE =
            attr("ATTACHMENT_TYPE", String.class, "IMAGE", "VIDEO", "AUDIO", "FILE", "STICKER");
    public static final StorageAttribute<Boolean> IS_FORWARDED = attr("IS_FORWARDED", Boolean.class);
    public static final StorageAttribute<Boolean> IS_REPLY = attr("IS_REPLY", Boolean.class);
    public static final StorageAttribute<String> REACTION_TYPE =
            attr("REACTION_TYPE", String.class, "LIKE", "LOVE", "LAUGH", "SAD", "ANGRY", "WOW");
    public static final StorageAttribute<Long> CHANNEL_ID = attr("CHANNEL_ID", Long.class);
    public static final StorageAttribute<Long> THREAD_ID = attr("THREAD_ID", Long.class);
    public static final StorageAttribute<Long> SOURCE_CHAT_ID = attr("SOURCE_CHAT_ID", Long.class);

    // ── Moderation ───────────────────────────────────────────────────────

    public static final StorageAttribute<Long> MODERATION_SCORE = attr("MODERATION_SCORE", Long.class);
    public static final StorageAttribute<String> MODERATION_CATEGORY =
            attr("MODERATION_CATEGORY", String.class, "SPAM", "HATE", "ADULT", "VIOLENCE", "OTHER");
    public static final StorageAttribute<String> MODERATION_ACTION =
            attr("MODERATION_ACTION", String.class, "FLAGGED", "APPROVED", "REJECTED", "DELETED");
    public static final StorageAttribute<Long> REPORT_COUNT = attr("REPORT_COUNT", Long.class);
    public static final StorageAttribute<Boolean> IS_AUTOMATED = attr("IS_AUTOMATED", Boolean.class);
    public static final StorageAttribute<Boolean> USER_GROUP_ADMIN = attr("USER_GROUP_ADMIN", Boolean.class);
    public static final StorageAttribute<Boolean> USER_CHAT_ADMIN = attr("USER_CHAT_ADMIN", Boolean.class);
    public static final StorageAttribute<Boolean> GROUP_PROMO = attr("GROUP_PROMO", Boolean.class);

    // ── Purchase ─────────────────────────────────────────────────────────

    public static final StorageAttribute<Long> AMOUNT_CENTS = attr("AMOUNT_CENTS", Long.class);
    public static final StorageAttribute<String> CURRENCY =
            attr("CURRENCY", String.class, "USD", "EUR", "RUB", "GBP");
    public static final StorageAttribute<String> PRODUCT_ID = attr("PRODUCT_ID", String.class);
    public static final StorageAttribute<String> PAYMENT_METHOD =
            attr("PAYMENT_METHOD", String.class, "CARD", "WALLET", "INVOICE");
    public static final StorageAttribute<Boolean> IS_TRIAL = attr("IS_TRIAL", Boolean.class);
    public static final StorageAttribute<String> SUBSCRIPTION_TIER =
            attr("SUBSCRIPTION_TIER", String.class, "FREE", "BASIC", "PRO", "ENTERPRISE");

    // ── Profile ──────────────────────────────────────────────────────────

    public static final StorageAttribute<Boolean> EMAIL_VERIFIED = attr("EMAIL_VERIFIED", Boolean.class);
    public static final StorageAttribute<Boolean> PHONE_VERIFIED = attr("PHONE_VERIFIED", Boolean.class);
    public static final StorageAttribute<String> AGE_GROUP =
            attr("AGE_GROUP", String.class, "18-24", "25-34", "35-44", "45-54", "55+");
    public static final StorageAttribute<String> COUNTRY_CODE = attr("COUNTRY_CODE", String.class);
    public static final StorageAttribute<String> TIMEZONE = attr("TIMEZONE", String.class);

    // ── Channel ──────────────────────────────────────────────────────────

    public static final StorageAttribute<Long> MEMBER_COUNT = attr("MEMBER_COUNT", Long.class);
    public static final StorageAttribute<Boolean> IS_PUBLIC = attr("IS_PUBLIC", Boolean.class);
    public static final StorageAttribute<String> CHANNEL_TYPE =
            attr("CHANNEL_TYPE", String.class, "GROUP", "CHANNEL", "DM", "BROADCAST");
    public static final StorageAttribute<Long> OTHER_USER_ID = attr("OTHER_USER_ID", Long.class);
    public static final StorageAttribute<Boolean> LIMIT_REACHED = attr("LIMIT_REACHED", Boolean.class);
    public static final StorageAttribute<String> FRIENDSHIP_REQUEST_SOURCE =
            attr("FRIENDSHIP_REQUEST_SOURCE", String.class, "SEARCH", "CONTACT", "QR", "INVITE_LINK");

    // ── File ─────────────────────────────────────────────────────────────

    public static final StorageAttribute<Long> FILE_SIZE_KB = attr("FILE_SIZE_KB", Long.class);
    public static final StorageAttribute<String> FILE_TYPE =
            attr("FILE_TYPE", String.class, "IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "ARCHIVE");
    public static final StorageAttribute<String> MIME_TYPE = attr("MIME_TYPE", String.class);

    // ── Call ──────────────────────────────────────────────────────────────

    public static final StorageAttribute<Long> CALL_DURATION_SEC = attr("CALL_DURATION_SEC", Long.class);
    public static final StorageAttribute<Boolean> IS_VIDEO = attr("IS_VIDEO", Boolean.class);
    public static final StorageAttribute<Long> PARTICIPANT_COUNT = attr("PARTICIPANT_COUNT", Long.class);

    // ── Search ────────────────────────────────────────────────────────────

    public static final StorageAttribute<Long> QUERY_LENGTH = attr("QUERY_LENGTH", Long.class);
    public static final StorageAttribute<String> SEARCH_SCOPE =
            attr("SEARCH_SCOPE", String.class, "MESSAGES", "USERS", "CHANNELS", "FILES", "GLOBAL");

    // ══════════════════════════════════════════════════════════════════════
    // Required attributes (must be present in every record)
    // ══════════════════════════════════════════════════════════════════════

    public static final List<StorageAttribute<?>> REQUIRED_ATTRIBUTES =
            List.of(TYPE, TIMESTAMP, USER_ID);

    // ══════════════════════════════════════════════════════════════════════
    // Type → attribute mappings
    // Mirrors legacy: addType(builder, UserActivityType.X, attr1, attr2, ...)
    // ══════════════════════════════════════════════════════════════════════

    public static final Map<UserActivityType, List<StorageAttribute<?>>> ATTRIBUTES_BY_TYPE;

    static {
        Map<UserActivityType, List<StorageAttribute<?>>> m = new EnumMap<>(UserActivityType.class);

        // Session / identity (2–5 attrs each)
        m.put(UserActivityType.REGISTRATION, List.of(
                SESSION_ID, USER_DEVICE_TYPE, PLATFORM_VERSION, LANG, TERMINAL_TYPE, ORG_HASH));
        m.put(UserActivityType.LOGIN, List.of(
                SESSION_ID, USER_DEVICE_TYPE, PLATFORM_VERSION, APP_VERSION, LANG));
        m.put(UserActivityType.LOGOUT, List.of(
                SESSION_ID, USER_DEVICE_TYPE));

        // Messaging (5–12 attrs)
        m.put(UserActivityType.MESSAGE_SENT, List.of(
                CHAT_ID, MESSAGE_ID, MESSAGE_LENGTH, HAS_ATTACHMENTS, ATTACHMENT_COUNT,
                ATTACHMENT_TYPE, IS_FORWARDED, IS_REPLY, SOURCE_CHAT_ID, LANG, SOURCE,
                USER_GROUP_ADMIN, USER_CHAT_ADMIN));
        m.put(UserActivityType.MESSAGE_DELETED, List.of(
                CHAT_ID, MESSAGE_ID, USER_CHAT_ADMIN, SOURCE));
        m.put(UserActivityType.MESSAGE_EDITED, List.of(
                CHAT_ID, MESSAGE_ID, MESSAGE_LENGTH, LANG, SOURCE));
        m.put(UserActivityType.MESSAGE_READ, List.of(
                CHAT_ID, MESSAGE_ID, SOURCE));
        m.put(UserActivityType.REACTION_ADDED, List.of(
                CHAT_ID, MESSAGE_ID, REACTION_TYPE, SOURCE));
        m.put(UserActivityType.REACTION_REMOVED, List.of(
                CHAT_ID, MESSAGE_ID, REACTION_TYPE, SOURCE));

        // Moderation (5–10 attrs)
        m.put(UserActivityType.MODERATION_FLAG, List.of(
                CHAT_ID, MESSAGE_ID, MODERATION_SCORE, MODERATION_CATEGORY,
                IS_AUTOMATED, REPORT_COUNT, LANG, SOURCE, GROUP_PROMO));
        m.put(UserActivityType.MODERATION_APPROVE, List.of(
                CHAT_ID, MESSAGE_ID, MODERATION_ACTION, IS_AUTOMATED, SOURCE));
        m.put(UserActivityType.MODERATION_REJECT, List.of(
                CHAT_ID, MESSAGE_ID, MODERATION_ACTION, MODERATION_CATEGORY,
                IS_AUTOMATED, USER_CHAT_ADMIN, SOURCE));

        // Purchase (4–8 attrs)
        m.put(UserActivityType.PURCHASE_INITIATED, List.of(
                AMOUNT_CENTS, CURRENCY, PRODUCT_ID, PAYMENT_METHOD, IS_TRIAL));
        m.put(UserActivityType.PURCHASE_COMPLETED, List.of(
                AMOUNT_CENTS, CURRENCY, PRODUCT_ID, PAYMENT_METHOD,
                IS_TRIAL, SUBSCRIPTION_TIER));
        m.put(UserActivityType.PURCHASE_REFUNDED, List.of(
                AMOUNT_CENTS, CURRENCY, PRODUCT_ID));

        // Profile (3–6 attrs)
        m.put(UserActivityType.PROFILE_UPDATED, List.of(
                EMAIL_VERIFIED, PHONE_VERIFIED, AGE_GROUP, COUNTRY_CODE, GENDER));
        m.put(UserActivityType.AVATAR_CHANGED, List.of(
                USER_DEVICE_TYPE, FILE_SIZE_KB));
        m.put(UserActivityType.STATUS_CHANGED, List.of(
                USER_ACTIVE_STATUS, LANG));

        // Channels (3–7 attrs)
        m.put(UserActivityType.CHANNEL_CREATED, List.of(
                CHANNEL_ID, CHANNEL_TYPE, IS_PUBLIC, MEMBER_COUNT));
        m.put(UserActivityType.CHANNEL_JOINED, List.of(
                CHANNEL_ID, CHANNEL_TYPE, MEMBER_COUNT));
        m.put(UserActivityType.CHANNEL_LEFT, List.of(
                CHANNEL_ID, CHANNEL_TYPE));
        m.put(UserActivityType.CHANNEL_DELETED, List.of(
                CHANNEL_ID, CHANNEL_TYPE, MEMBER_COUNT));

        // Files (3–6 attrs)
        m.put(UserActivityType.FILE_UPLOADED, List.of(
                CHAT_ID, FILE_SIZE_KB, FILE_TYPE, MIME_TYPE, HAS_ATTACHMENTS, SOURCE));
        m.put(UserActivityType.FILE_DOWNLOADED, List.of(
                CHAT_ID, FILE_SIZE_KB, FILE_TYPE, SOURCE));
        m.put(UserActivityType.FILE_DELETED, List.of(
                CHAT_ID, FILE_SIZE_KB, FILE_TYPE, SOURCE));

        // Calls (3–5 attrs)
        m.put(UserActivityType.CALL_STARTED, List.of(
                CHAT_ID, IS_VIDEO, PARTICIPANT_COUNT));
        m.put(UserActivityType.CALL_ENDED, List.of(
                CHAT_ID, CALL_DURATION_SEC, IS_VIDEO, PARTICIPANT_COUNT));
        m.put(UserActivityType.CALL_MISSED, List.of(
                CHAT_ID, IS_VIDEO));

        // Other (2–3 attrs)
        m.put(UserActivityType.SEARCH_PERFORMED, List.of(
                QUERY_LENGTH, SEARCH_SCOPE, LANG));
        m.put(UserActivityType.NOTIFICATION_RECEIVED, List.of(
                USER_DEVICE_TYPE, CHANNEL_ID));

        // Social
        // ADD_FRIEND is referenced in old code; map it to OTHER for now
        // m.put(UserActivityType.ADD_FRIEND, List.of(OTHER_USER_ID, LIMIT_REACHED, FRIENDSHIP_REQUEST_SOURCE));

        ATTRIBUTES_BY_TYPE = Collections.unmodifiableMap(m);
    }

    // ══════════════════════════════════════════════════════════════════════
    // All attributes (for introspection / iteration)
    // ══════════════════════════════════════════════════════════════════════

    /** All declared attributes in registration order. */
    public static final List<StorageAttribute<?>> ALL_ATTRIBUTES = List.of(
            TYPE, TIMESTAMP, USER_ID,
            SESSION_ID, USER_DEVICE_TYPE, PLATFORM_VERSION, APP_VERSION,
            TERMINAL_TYPE, ORG_HASH, GENDER, USER_ACTIVE_STATUS, LANG, SOURCE,
            CHAT_ID, MESSAGE_ID, MESSAGE_LENGTH, HAS_ATTACHMENTS, ATTACHMENT_COUNT,
            ATTACHMENT_TYPE, IS_FORWARDED, IS_REPLY, REACTION_TYPE, CHANNEL_ID,
            THREAD_ID, SOURCE_CHAT_ID,
            MODERATION_SCORE, MODERATION_CATEGORY, MODERATION_ACTION, REPORT_COUNT,
            IS_AUTOMATED, USER_GROUP_ADMIN, USER_CHAT_ADMIN, GROUP_PROMO,
            AMOUNT_CENTS, CURRENCY, PRODUCT_ID, PAYMENT_METHOD, IS_TRIAL, SUBSCRIPTION_TIER,
            EMAIL_VERIFIED, PHONE_VERIFIED, AGE_GROUP, COUNTRY_CODE, TIMEZONE,
            MEMBER_COUNT, IS_PUBLIC, CHANNEL_TYPE, OTHER_USER_ID, LIMIT_REACHED,
            FRIENDSHIP_REQUEST_SOURCE,
            FILE_SIZE_KB, FILE_TYPE, MIME_TYPE,
            CALL_DURATION_SEC, IS_VIDEO, PARTICIPANT_COUNT,
            QUERY_LENGTH, SEARCH_SCOPE
    );

    /** Quick lookup: attribute name → attribute instance. */
    public static final Map<String, StorageAttribute<?>> BY_NAME;

    static {
        Map<String, StorageAttribute<?>> tmp = new LinkedHashMap<>();
        for (StorageAttribute<?> a : ALL_ATTRIBUTES) {
            tmp.put(a.getName(), a);
            tmp.put(a.chKey(), a); // also index by CH key for deserialization
        }
        BY_NAME = Collections.unmodifiableMap(tmp);
    }

    /**
     * Rejects records whose {@code attrs} keys are not exactly the union of
     * {@link #REQUIRED_ATTRIBUTES} (by ClickHouse map key) and the list for the
     * resolved {@link UserActivityType}. Also checks {@code eventTime},
     * {@code user_id}, and consistency between {@link Record#getActivityType()}
     * and {@code attrs['type']}.
     *
     * @throws IllegalArgumentException if validation fails
     */
    public static void validateStrict(Record r) {
        if (r == null) {
            throw new IllegalArgumentException("record is null");
        }
        Map<String, Object> attrs = r.getAttrs();
        if (attrs == null) {
            throw new IllegalArgumentException("attrs required");
        }

        UserActivityType fromField = r.getActivityType();
        Object typeRaw = attrs.get(TYPE.chKey());
        UserActivityType fromAttr = typeRaw == null ? null : UserActivityType.fromRaw(String.valueOf(typeRaw));

        if (fromField != null && fromAttr != null && fromField != fromAttr) {
            throw new IllegalArgumentException(
                    "activityType and attrs.type mismatch: " + fromField + " vs " + fromAttr);
        }
        UserActivityType at = fromField != null ? fromField : fromAttr;
        if (at == null) {
            throw new IllegalArgumentException("activity type required (activityType or attrs.type)");
        }

        if (r.getEventTime() == null) {
            throw new IllegalArgumentException("eventTime required");
        }

        Object uid = attrs.get(USER_ID.chKey());
        if (uid == null || String.valueOf(uid).isBlank()) {
            throw new IllegalArgumentException("user_id required in attrs");
        }
        try {
            Long.parseLong(String.valueOf(uid).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("user_id must be a number");
        }

        List<StorageAttribute<?>> byType = ATTRIBUTES_BY_TYPE.get(at);
        if (byType == null) {
            throw new IllegalArgumentException("no schema for activity type: " + at);
        }

        Set<String> allowedKeys = new HashSet<>();
        for (StorageAttribute<?> a : REQUIRED_ATTRIBUTES) {
            allowedKeys.add(a.chKey());
        }
        for (StorageAttribute<?> a : byType) {
            allowedKeys.add(a.chKey());
        }

        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            String key = e.getKey();
            if (key == null) {
                continue;
            }
            String nk = key.toLowerCase();
            if (!allowedKeys.contains(nk)) {
                throw new IllegalArgumentException("Unknown attribute for " + at + ": " + key);
            }
            StorageAttribute<?> spec = BY_NAME.get(nk);
            if (spec != null) {
                validateAttrValue(spec, e.getValue());
            }
        }
    }

    private static void validateAttrValue(StorageAttribute<?> attr, Object raw) {
        if (raw == null) {
            return;
        }
        if (attr == TIMESTAMP) {
            return;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return;
        }
        Class<?> t = attr.getType();
        if (t == Long.class) {
            try {
                Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid " + attr.getName() + ": " + raw);
            }
            return;
        }
        if (t == Boolean.class) {
            boolean ok = "1".equals(s) || "0".equals(s)
                    || "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s);
            if (!ok) {
                throw new IllegalArgumentException("Invalid " + attr.getName() + ": " + raw);
            }
            return;
        }
        if (t == String.class && attr.getAllowedValues() != null) {
            attr.validate(s);
        }
    }

    private UserActivitySchema() {}
}
