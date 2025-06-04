package stroom.planb.impl.db.serde.valtime;

import stroom.lmdb.serde.UnsignedBytes;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.planb.impl.db.serde.time.TimeSerde;

import java.nio.ByteBuffer;
import java.time.Instant;

public class InsertTimeSerde implements TimeSerde {

    // Always offset by year 2025 epoch seconds to give us an extra 55 years.
    private static final long YEAR_2025_EPOCH_SECONDS = 1735689600L;
    private static final long SECONDS_IN_DAY = 86400;
    // Two bytes gives us approx 184 years from epoch of 1970
    private static final UnsignedBytes UNSIGNED_BYTES = UnsignedBytesInstances.TWO;

    @Override
    public void write(final ByteBuffer byteBuffer, final Instant instant) {
        UNSIGNED_BYTES.put(byteBuffer, (instant.getEpochSecond() - YEAR_2025_EPOCH_SECONDS) / SECONDS_IN_DAY);
    }

    @Override
    public Instant read(final ByteBuffer byteBuffer) {
        return Instant.ofEpochSecond((UNSIGNED_BYTES.get(byteBuffer) * SECONDS_IN_DAY) + YEAR_2025_EPOCH_SECONDS);
    }

    @Override
    public int getSize() {
        return UNSIGNED_BYTES.length();
    }
}
