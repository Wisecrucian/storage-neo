package one.idsstorage.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UUID version 7 generator (RFC 9562).
 *
 * Structure (128 bits):
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   unix_ts_ms (48 bits)                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |unix_ts_ms(cont)| ver=7 | seq_hi (12 bits)                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | var=10 | seq_lo (6 bits) |       random (56 bits)            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    random (continued)                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * Ключевое отличие от v4:
 *  - первые 48 бит = Unix timestamp в мс → записи вставленные рядом
 *    имеют почти одинаковый префикс → DoubleDelta/LZ4 сжимает намного лучше
 *  - монотонность внутри одной мс обеспечивается счётчиком seq
 */
public final class UuidV7 {

    private static final java.util.Random RNG = new java.util.Random();

    // Счётчик для монотонности внутри одной миллисекунды
    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static volatile long lastMs = -1;

    private UuidV7() {}

    /**
     * Генерирует UUID v7 (time-ordered, монотонный).
     * Потокобезопасен.
     */
    public static UUID generate() {
        long ms = System.currentTimeMillis();

        // Сбрасываем счётчик при смене миллисекунды
        int seq;
        synchronized (UuidV7.class) {
            if (ms > lastMs) {
                lastMs = ms;
                SEQ.set(0);
            }
            seq = SEQ.getAndIncrement() & 0xFFF; // 12-bit counter
        }

        // MSB: [48 бит timestamp][4 бит version=7][12 бит seq]
        long msb = (ms << 16)
                | (0x7000L)          // version 7
                | (seq & 0x0FFFL);

        // LSB: [2 бит variant=10][62 бит random]
        long lsb = (0b10L << 62) | (RNG.nextLong() & 0x3FFFFFFFFFFFFFFFL);

        return new UUID(msb, lsb);
    }

    /**
     * Извлекает Unix timestamp (мс) из UUID v7.
     * Полезно для отладки и проверки.
     */
    public static long extractTimestampMs(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
