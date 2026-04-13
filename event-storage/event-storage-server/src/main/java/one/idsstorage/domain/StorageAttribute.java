package one.idsstorage.domain;

import java.io.Serializable;

/**
 * Typed attribute descriptor — mirrors the legacy StorageAttribute<T> interface.
 *
 * <p>In the old system each attribute carried a Java type ({@code Long.class},
 * {@code Boolean.class}, etc.) used for runtime validation in
 * {@code setAttribute(attr, value)}. In the ClickHouse model all dynamic
 * attributes serialize to {@code Map(String, String)}, but we preserve the
 * type metadata for three purposes:
 * <ol>
 *   <li>Input validation — reject garbage before it reaches Kafka.</li>
 *   <li>Materialized column extraction — the {@code expression} (e.g.
 *       {@code toInt32OrZero(attrs['score'])}) depends on the logical type.</li>
 *   <li>Documentation — the schema is self-describing for thesis experiments.</li>
 * </ol>
 *
 * <p>Instances are flyweight singletons defined in {@link UserActivitySchema}.
 */
public final class StorageAttribute<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final Class<T> type;
    private final int index;
    private final String[] allowedValues; // non-null only for enum-like String attrs

    public StorageAttribute(String name, Class<T> type, int index) {
        this(name, type, index, null);
    }

    public StorageAttribute(String name, Class<T> type, int index, String[] allowedValues) {
        this.name = name;
        this.type = type;
        this.index = index;
        this.allowedValues = allowedValues;
    }

    public String getName() {
        return name;
    }

    /** ClickHouse map key: lowercase of the logical name. */
    public String chKey() {
        return name.toLowerCase();
    }

    public Class<T> getType() {
        return type;
    }

    public int getIndex() {
        return index;
    }

    public String[] getAllowedValues() {
        return allowedValues;
    }

    /**
     * Validate that {@code value} matches the declared type.
     * Mirrors the old {@code assert attribute.getType().isInstance(value)} guard.
     */
    public void validate(Object value) {
        if (value == null) return;
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "Attribute " + name + ": expected " + type.getSimpleName()
                            + ", got " + value.getClass().getSimpleName());
        }
        if (allowedValues != null && value instanceof String s) {
            boolean found = false;
            for (String av : allowedValues) {
                if (av.equals(s)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException(
                        "Attribute " + name + ": value '" + s + "' not in allowed set");
            }
        }
    }

    /**
     * Serialize value to String for {@code Map(String, String)} storage.
     * Booleans → "1"/"0" (compact, avoids ClickHouse parsing overhead).
     */
    public String toStringValue(Object value) {
        if (value == null) return "";
        if (value instanceof Boolean b) return b ? "1" : "0";
        return String.valueOf(value);
    }

    @Override
    public String toString() {
        return "StorageAttribute{" + name + ", " + type.getSimpleName() + ", idx=" + index + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StorageAttribute<?> that)) return false;
        return index == that.index && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + index;
    }
}
