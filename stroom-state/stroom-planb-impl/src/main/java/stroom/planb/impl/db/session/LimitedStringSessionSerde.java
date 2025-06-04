package stroom.planb.impl.db.session;

import stroom.bytebuffer.ByteBufferUtils;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.planb.impl.db.Db;
import stroom.planb.impl.db.serde.time.TimeSerde;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;

import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class LimitedStringSessionSerde implements SessionSerde {

    private final ByteBuffer reusableWriteBuffer;
    private final ByteBuffers byteBuffers;
    private final TimeSerde timeSerde;
    private final int timeLength;
    private final int limit;

    public LimitedStringSessionSerde(final ByteBuffers byteBuffers,
                                     final int limit,
                                     final TimeSerde timeSerde) {
        this.byteBuffers = byteBuffers;
        this.timeSerde = timeSerde;
        this.timeLength = timeSerde.getSize() + timeSerde.getSize();
        this.limit = Db.MAX_KEY_LENGTH - timeLength;
        reusableWriteBuffer = ByteBuffer.allocateDirect(limit);
    }

    @Override
    public Session read(final Txn<ByteBuffer> txn, final ByteBuffer byteBuffer) {
        final ByteBuffer startSlice = byteBuffer.slice(byteBuffer.remaining() - timeLength,
                timeSerde.getSize());
        final ByteBuffer endSlice = byteBuffer.slice(byteBuffer.remaining() - timeSerde.getSize(),
                timeSerde.getSize());
        final Instant start = timeSerde.read(startSlice);
        final Instant end = timeSerde.read(endSlice);

        // Slice off the key.
        final ByteBuffer keySlice = byteBuffer.slice(0,
                byteBuffer.remaining() - timeLength);

        // Read via lookup.
        final Val key = ValString.create(ByteBufferUtils.toString(keySlice));
        return new Session(key, start, end);
    }

    @Override
    public void write(final Txn<ByteBuffer> txn, final Session session, final Consumer<ByteBuffer> consumer) {
        final byte[] bytes = session.getKey().toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) {
            throw new RuntimeException("Key length exceeds " + limit + " bytes");
        }
//        byteBuffers.use(bytes.length, byteBuffer -> {
//            byteBuffer.put(bytes);
//            byteBuffer.flip();
//            consumer.accept(byteBuffer);
//        });

        // We are in a single write transaction so should be able to reuse the same buffer again and again.
        reusableWriteBuffer.clear();
        reusableWriteBuffer.put(bytes);
        timeSerde.write(reusableWriteBuffer, session.getStart());
        timeSerde.write(reusableWriteBuffer, session.getEnd());
        reusableWriteBuffer.flip();
        consumer.accept(reusableWriteBuffer);
    }

    @Override
    public <R> R toBufferForGet(final Txn<ByteBuffer> txn,
                                final Session session,
                                final Function<Optional<ByteBuffer>, R> function) {
        final byte[] bytes = session.getKey().toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) {
            throw new RuntimeException("Key length exceeds " + limit + " bytes");
        }
        return byteBuffers.use(bytes.length + timeLength, byteBuffer -> {
            byteBuffer.put(bytes);
            timeSerde.write(byteBuffer, session.getStart());
            timeSerde.write(byteBuffer, session.getEnd());
            byteBuffer.flip();
            return function.apply(Optional.of(byteBuffer));
        });
    }
}
