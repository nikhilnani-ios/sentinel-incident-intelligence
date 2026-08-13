package io.sentinel.platform.domain.model;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Minimal UUIDv7 generator: 48 bits of millisecond timestamp followed by random bits.
 *
 * <p>Sequential ids keep primary-key index inserts on the right-hand side of the B-tree, which
 * matters once the incident_signal table is in the hundreds of millions of rows.
 */
public final class TimeOrderedUuid {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TimeOrderedUuid() {}

    public static UUID next() {
        return from(Instant.now());
    }

    static UUID from(Instant instant) {
        long timestamp = instant.toEpochMilli();
        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);

        long mostSignificant =
                (timestamp << 16) | (0x7000L) | ((randomBytes[0] & 0x0FL) << 8) | (randomBytes[1] & 0xFFL);

        long leastSignificant = 0x8000000000000000L;
        for (int i = 2; i < 10; i++) {
            leastSignificant |= (randomBytes[i] & 0xFFL) << ((9 - i) * 8);
        }
        leastSignificant &= 0xBFFFFFFFFFFFFFFFL;

        return new UUID(mostSignificant, leastSignificant);
    }
}
