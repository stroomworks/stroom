package stroom.planb.impl.db.serde.time;

import stroom.lmdb.serde.UnsignedBytes;
import stroom.lmdb.serde.UnsignedBytesInstances;

import java.nio.ByteBuffer;
import java.time.Instant;

public class DayTimeSerde implements TimeSerde {

    private static final long SECONDS_IN_DAY = 86400;
    // Two bytes gives us approx 184 years from epoch of 1970
    private static final UnsignedBytes UNSIGNED_BYTES = UnsignedBytesInstances.TWO;

    @Override
    public void write(final ByteBuffer byteBuffer, final Instant instant) {
        UNSIGNED_BYTES.put(byteBuffer, instant.getEpochSecond() / SECONDS_IN_DAY);
    }

    @Override
    public Instant read(final ByteBuffer byteBuffer) {
        return Instant.ofEpochSecond(UNSIGNED_BYTES.get(byteBuffer) * SECONDS_IN_DAY);
    }

    @Override
    public int getSize() {
        return UNSIGNED_BYTES.length();
    }
}
