package stroom.planb.impl.db.temporalstate;

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.planb.impl.db.Db;
import stroom.planb.impl.db.PlanBEnv;
import stroom.planb.impl.db.UidLookupDb;
import stroom.planb.impl.db.UidLookupRecorder;
import stroom.planb.impl.db.UsedLookupsRecorder;
import stroom.planb.impl.db.UsedLookupsRecorderProxy;
import stroom.planb.impl.db.serde.time.TimeSerde;
import stroom.planb.impl.db.serde.val.ValSerdeUtil;
import stroom.planb.impl.db.serde.val.ValSerdeUtil.Addition;
import stroom.planb.impl.db.temporalstate.TemporalState.Key;
import stroom.query.language.functions.Val;

import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class UidLookupKeySerde implements TemporalStateKeySerde {

    private final UidLookupDb uidLookupDb;
    private final ByteBuffers byteBuffers;
    private final TimeSerde timeSerde;

    public UidLookupKeySerde(final UidLookupDb uidLookupDb, final ByteBuffers byteBuffers, final TimeSerde timeSerde) {
        this.uidLookupDb = uidLookupDb;
        this.byteBuffers = byteBuffers;
        this.timeSerde = timeSerde;
    }

    @Override
    public Key read(final Txn<ByteBuffer> txn, final ByteBuffer byteBuffer) {
        // Slice off the end to get the effective time.
        final ByteBuffer timeSlice = byteBuffer.slice(byteBuffer.remaining() - timeSerde.getSize(),
                timeSerde.getSize());
        final Instant effectiveTime = timeSerde.read(timeSlice);

        // Slice off the name.
        final ByteBuffer nameSlice = getPrefix(byteBuffer);

        // Read via lookup.
        final ByteBuffer valueByteBuffer = uidLookupDb.getValue(txn, nameSlice);
        final Val val = ValSerdeUtil.read(valueByteBuffer);
        return new Key(val, effectiveTime);
    }

    private ByteBuffer getPrefix(final ByteBuffer byteBuffer) {
        // Slice off the name.
        return byteBuffer.slice(0, byteBuffer.remaining() - timeSerde.getSize());
    }

    @Override
    public void write(final Txn<ByteBuffer> txn, final Key key, final Consumer<ByteBuffer> consumer) {
        final Addition prefix = Addition.NONE;
        final Addition suffix = new Addition(timeSerde.getSize(), bb -> timeSerde.write(bb, key.getEffectiveTime()));

        ValSerdeUtil.write(key.getName(), byteBuffers, valueByteBuffer -> {
            final ByteBuffer slice = valueByteBuffer.slice(0, valueByteBuffer.remaining() - timeSerde.getSize());
            if (slice.remaining() > Db.MAX_KEY_LENGTH) {
                throw new RuntimeException("Key length exceeds " + Db.MAX_KEY_LENGTH + " bytes");
            }

            uidLookupDb.put(txn, slice, idByteBuffer -> {
                byteBuffers.use(idByteBuffer.remaining() + timeSerde.getSize(), prefixedBuffer -> {
                    prefixedBuffer.put(idByteBuffer);
                    timeSerde.write(prefixedBuffer, key.getEffectiveTime());
                    prefixedBuffer.flip();
                    consumer.accept(prefixedBuffer);
                });
                return null;
            });
            return null;
        }, prefix, suffix);
    }

    @Override
    public <R> R toBufferForGet(final Txn<ByteBuffer> txn,
                                final Key key,
                                final Function<Optional<ByteBuffer>, R> function) {
        final Addition prefix = Addition.NONE;
        final Addition suffix = new Addition(timeSerde.getSize(), bb -> timeSerde.write(bb, key.getEffectiveTime()));

        return ValSerdeUtil.write(key.getName(), byteBuffers, valueByteBuffer -> {
            // We are going to store as a lookup so take off the variable type prefix.
            final ByteBuffer slice = valueByteBuffer.slice(0, valueByteBuffer.remaining() - timeSerde.getSize());
            if (slice.remaining() > Db.MAX_KEY_LENGTH) {
                throw new RuntimeException("Key length exceeds " + Db.MAX_KEY_LENGTH + " bytes");
            }

            return uidLookupDb.get(txn, slice, optionalIdByteBuffer ->
                    optionalIdByteBuffer
                            .map(idByteBuffer ->
                                    byteBuffers.use(idByteBuffer.remaining() + timeSerde.getSize(), prefixedBuffer -> {
                                        prefixedBuffer.put(idByteBuffer);
                                        timeSerde.write(prefixedBuffer, key.getEffectiveTime());
                                        prefixedBuffer.flip();
                                        return function.apply(Optional.of(prefixedBuffer));
                                    }))
                            .orElse(null));
        }, prefix, suffix);
    }

    @Override
    public boolean usesLookup(final ByteBuffer byteBuffer) {
        return true;
    }

    @Override
    public UsedLookupsRecorder getUsedLookupsRecorder(final PlanBEnv env) {
        return new UsedLookupsRecorderProxy(
                new UidLookupRecorder(env, uidLookupDb),
                this::getPrefix);
    }
}
