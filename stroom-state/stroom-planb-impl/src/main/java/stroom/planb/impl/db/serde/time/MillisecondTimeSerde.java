package stroom.planb.impl.db.serde.time;

import stroom.lmdb.serde.UnsignedBytes;
import stroom.lmdb.serde.UnsignedBytesInstances;

import java.nio.ByteBuffer;
import java.time.Instant;

public class MillisecondTimeSerde implements TimeSerde {

    // Six bytes gives us approx 9,151 years from epoch of 1970
    private static final UnsignedBytes UNSIGNED_BYTES = UnsignedBytesInstances.SIX;

    @Override
    public void write(final ByteBuffer byteBuffer, final Instant instant) {
        UNSIGNED_BYTES.put(byteBuffer, instant.toEpochMilli());
    }

    @Override
    public Instant read(final ByteBuffer byteBuffer) {
        return Instant.ofEpochMilli(UNSIGNED_BYTES.get(byteBuffer));
    }

    @Override
    public int getSize() {
        return UNSIGNED_BYTES.length();
    }
}
