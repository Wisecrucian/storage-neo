package one.idsstorage.domain;

public enum RecordType {
    MESSAGE,
    MODERATION,
    PURCHASE,
    SYSTEM,
    OTHER;

    public static RecordType fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        try {
            return RecordType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return OTHER;
        }
    }
}
