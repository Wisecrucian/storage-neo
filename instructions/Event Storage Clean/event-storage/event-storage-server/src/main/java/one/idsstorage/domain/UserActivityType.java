package one.idsstorage.domain;

/**
 * Event type classification — mirrors the legacy {@code UserActivityType} enum.
 *
 * <p>Each value defines a distinct kind of user activity with its own set of
 * expected attributes (see {@link UserActivitySchema}). In ClickHouse this
 * value is stored in {@code attrs['type']} as a String; the top-level
 * {@code record_type} column holds a coarser grouping ({@link RecordType}).
 *
 * <p>The mapping {@code UserActivityType → RecordType} determines which
 * physical sort order (main table vs projection) serves queries for a given
 * event category.
 */
public enum UserActivityType {
    // Session / identity
    REGISTRATION(RecordType.SYSTEM),
    LOGIN(RecordType.SYSTEM),
    LOGOUT(RecordType.SYSTEM),

    // Messaging
    MESSAGE_SENT(RecordType.MESSAGE),
    MESSAGE_DELETED(RecordType.MESSAGE),
    MESSAGE_EDITED(RecordType.MESSAGE),
    MESSAGE_READ(RecordType.MESSAGE),

    // Reactions
    REACTION_ADDED(RecordType.MESSAGE),
    REACTION_REMOVED(RecordType.MESSAGE),

    // Moderation
    MODERATION_FLAG(RecordType.MODERATION),
    MODERATION_APPROVE(RecordType.MODERATION),
    MODERATION_REJECT(RecordType.MODERATION),

    // Purchase
    PURCHASE_INITIATED(RecordType.PURCHASE),
    PURCHASE_COMPLETED(RecordType.PURCHASE),
    PURCHASE_REFUNDED(RecordType.PURCHASE),

    // Profile
    PROFILE_UPDATED(RecordType.SYSTEM),
    AVATAR_CHANGED(RecordType.SYSTEM),
    STATUS_CHANGED(RecordType.SYSTEM),

    // Channels
    CHANNEL_CREATED(RecordType.SYSTEM),
    CHANNEL_JOINED(RecordType.SYSTEM),
    CHANNEL_LEFT(RecordType.SYSTEM),
    CHANNEL_DELETED(RecordType.SYSTEM),

    // Files
    FILE_UPLOADED(RecordType.MESSAGE),
    FILE_DOWNLOADED(RecordType.MESSAGE),
    FILE_DELETED(RecordType.MESSAGE),

    // Calls
    CALL_STARTED(RecordType.SYSTEM),
    CALL_ENDED(RecordType.SYSTEM),
    CALL_MISSED(RecordType.SYSTEM),

    // Other
    SEARCH_PERFORMED(RecordType.OTHER),
    NOTIFICATION_RECEIVED(RecordType.OTHER);

    private final RecordType recordType;

    UserActivityType(RecordType recordType) {
        this.recordType = recordType;
    }

    /** Coarse grouping for ClickHouse {@code record_type} column. */
    public RecordType getRecordType() {
        return recordType;
    }

    /**
     * Safe parse with fallback — mirrors the old normalizeRecordType logic
     * but at the activity-type level.
     */
    public static UserActivityType fromRaw(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UserActivityType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
