package stroom.planb.impl.db.temporalstate;

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.planb.impl.db.Db;
import stroom.planb.impl.db.HashLookupDb;
import stroom.planb.impl.db.PlanBEnv;
import stroom.planb.impl.db.UidLookupDb;
import stroom.planb.impl.db.UsedLookupsRecorder;
import stroom.planb.impl.db.UsedLookupsRecorderProxy;
import stroom.planb.impl.db.VariableUsedLookupsRecorder;
import stroom.planb.impl.db.serde.time.TimeSerde;
import stroom.planb.impl.db.serde.val.ValSerdeUtil;
import stroom.planb.impl.db.serde.val.ValSerdeUtil.Addition;
import stroom.planb.impl.db.serde.val.VariableValType;
import stroom.planb.impl.db.temporalstate.TemporalState.Key;
import stroom.query.language.functions.Val;

import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class VariableKeySerde implements TemporalStateKeySerde {

    private static final int USE_HASH_LOOKUP_THRESHOLD = Db.MAX_KEY_LENGTH;

    private final int uidLookupThreshold;
    private final UidLookupDb uidLookupDb;
    private final HashLookupDb hashLookupDb;
    private final ByteBuffers byteBuffers;
    private final TimeSerde timeSerde;

    public VariableKeySerde(final UidLookupDb uidLookupDb,
                            final HashLookupDb hashLookupDb,
                            final ByteBuffers byteBuffers,
                            final TimeSerde timeSerde) {
        this.uidLookupDb = uidLookupDb;
        this.hashLookupDb = hashLookupDb;
        this.byteBuffers = byteBuffers;
        this.timeSerde = timeSerde;
        uidLookupThreshold = 32 + timeSerde.getSize();
    }

    @Override
    public Key read(final Txn<ByteBuffer> txn, final ByteBuffer byteBuffer) {
        // Slice off the end to get the effective time.
        final ByteBuffer timeSlice = byteBuffer.slice(byteBuffer.remaining() - timeSerde.getSize(),
                timeSerde.getSize());
        final Instant effectiveTime = timeSerde.read(timeSlice);

        // Slice off the name.
        final ByteBuffer nameSlice = getPrefix(byteBuffer);

        // Read the variable type.
        final VariableValType valType = VariableValType.PRIMITIVE_VALUE_CONVERTER.fromPrimitiveValue(nameSlice.get());
        final Val val = switch (valType) {
            case DIRECT -> {
                // Read direct.
                yield ValSerdeUtil.read(nameSlice);
            }
            case UID_LOOKUP -> {
                // Read via UI lookup.
                final ByteBuffer valueByteBuffer = uidLookupDb.getValue(txn, nameSlice);
                yield ValSerdeUtil.read(valueByteBuffer);
            }
            case HASH_LOOKUP -> {
                // Read via hash lookup.
                final ByteBuffer valueByteBuffer = hashLookupDb.getValue(txn, nameSlice);
                yield ValSerdeUtil.read(valueByteBuffer);
            }
        };

        return new Key(val, effectiveTime);
    }

    private ByteBuffer getPrefix(final ByteBuffer byteBuffer) {
        // Slice off the name.
        return byteBuffer.slice(0, byteBuffer.remaining() - timeSerde.getSize());
    }

    @Override
    public void write(final Txn<ByteBuffer> txn, final Key key, final Consumer<ByteBuffer> consumer) {
        final Addition prefix = new Addition(1, bb -> bb.put(VariableValType.DIRECT.getPrimitiveValue()));
        final Addition suffix = new Addition(timeSerde.getSize(), bb -> timeSerde.write(bb, key.getEffectiveTime()));

        ValSerdeUtil.write(key.getName(), byteBuffers, valueByteBuffer -> {
            if (valueByteBuffer.remaining() > USE_HASH_LOOKUP_THRESHOLD) {
                // We are going to store as a lookup so take off the variable type prefix.
                final ByteBuffer slice = getName(valueByteBuffer);
                hashLookupDb.put(txn, slice, idByteBuffer -> {
                    byteBuffers.use(idByteBuffer.remaining() + 1 + timeSerde.getSize(), prefixedBuffer -> {
                        // Add the variable type prefix to the lookup id.
                        prefixedBuffer.put(VariableValType.HASH_LOOKUP.getPrimitiveValue());
                        prefixedBuffer.put(idByteBuffer);
                        timeSerde.write(prefixedBuffer, key.getEffectiveTime());
                        prefixedBuffer.flip();
                        consumer.accept(prefixedBuffer);
                    });
                    return null;
                });
            } else if (valueByteBuffer.remaining() > uidLookupThreshold) {
                // We are going to store as a lookup so take off the variable type prefix.
                final ByteBuffer slice = getName(valueByteBuffer);
                uidLookupDb.put(txn, slice, idByteBuffer -> {
                    byteBuffers.use(idByteBuffer.remaining() + 1 + timeSerde.getSize(), prefixedBuffer -> {
                        // Add the variable type prefix to the lookup id.
                        prefixedBuffer.put(VariableValType.UID_LOOKUP.getPrimitiveValue());
                        prefixedBuffer.put(idByteBuffer);
                        timeSerde.write(prefixedBuffer, key.getEffectiveTime());
                        prefixedBuffer.flip();
                        consumer.accept(prefixedBuffer);
                    });
                    return null;
                });
            } else {
                // We have already added the direct variable prefix so just use the byte buffer.
                consumer.accept(valueByteBuffer);
            }
            return null;
        }, prefix, suffix);
    }

    @Override
    public <R> R toBufferForGet(final Txn<ByteBuffer> txn,
                                final Key key,
                                final Function<Optional<ByteBuffer>, R> function) {
        final Addition prefix = new Addition(1, bb -> bb.put(VariableValType.DIRECT.getPrimitiveValue()));
        final Addition suffix = new Addition(timeSerde.getSize(), bb -> timeSerde.write(bb, key.getEffectiveTime()));

        return ValSerdeUtil.write(key.getName(), byteBuffers, valueByteBuffer -> {
            if (valueByteBuffer.remaining() > USE_HASH_LOOKUP_THRESHOLD) {
                // We are going to store as a lookup so take off the variable type prefix.
                final ByteBuffer slice = getName(valueByteBuffer);
                return hashLookupDb.get(txn, slice, optionalIdByteBuffer ->
                        optionalIdByteBuffer
                                .map(idByteBuffer ->
                                        byteBuffers.use(idByteBuffer.remaining() + 1 + timeSerde.getSize(),
                                                prefixedBuffer -> {
                                                    // Add the variable type prefix to the lookup id.
                                                    prefixedBuffer.put(VariableValType.HASH_LOOKUP.getPrimitiveValue());
                                                    prefixedBuffer.put(idByteBuffer);
                                                    timeSerde.write(prefixedBuffer, key.getEffectiveTime());
                                                    prefixedBuffer.flip();
                                                    return function.apply(Optional.of(prefixedBuffer));
                                                }))
                                .orElse(null));
            } else if (valueByteBuffer.remaining() > uidLookupThreshold) {
                // We are going to store as a lookup so take off the variable type prefix.
                final ByteBuffer slice = getName(valueByteBuffer);
                return uidLookupDb.get(txn, slice, optionalIdByteBuffer ->
                        optionalIdByteBuffer
                                .map(idByteBuffer ->
                                        byteBuffers.use(idByteBuffer.remaining() + 1 + timeSerde.getSize(),
                                                prefixedBuffer -> {
                                                    // Add the variable type prefix to the lookup id.
                                                    prefixedBuffer.put(VariableValType.UID_LOOKUP.getPrimitiveValue());
                                                    prefixedBuffer.put(idByteBuffer);
                                                    timeSerde.write(prefixedBuffer, key.getEffectiveTime());
                                                    prefixedBuffer.flip();
                                                    return function.apply(Optional.of(prefixedBuffer));
                                                }))
                                .orElse(null));
            } else {
                // We have already added the direct variable prefix so just use the byte buffer.
                return function.apply(Optional.of(valueByteBuffer));
            }
        }, prefix, suffix);
    }

    private ByteBuffer getName(final ByteBuffer byteBuffer) {
        return byteBuffer.slice(1, byteBuffer.remaining() - timeSerde.getSize());
    }

    @Override
    public boolean usesLookup(final ByteBuffer byteBuffer) {
        // Read the variable type.
        final VariableValType valType = VariableValType.PRIMITIVE_VALUE_CONVERTER.fromPrimitiveValue(byteBuffer.get(0));
        return !VariableValType.DIRECT.equals(valType);
    }

    @Override
    public UsedLookupsRecorder getUsedLookupsRecorder(final PlanBEnv env) {
        return new UsedLookupsRecorderProxy(
                new VariableUsedLookupsRecorder(env, uidLookupDb, hashLookupDb),
                this::getPrefix);
    }
}
