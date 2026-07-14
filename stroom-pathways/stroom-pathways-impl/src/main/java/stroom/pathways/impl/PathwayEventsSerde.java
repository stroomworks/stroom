/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.pathways.impl;

import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.bytebuffer.impl6.ByteBufferPoolOutput;
import stroom.pathways.impl.events.ConstraintDiscoveryEvent;
import stroom.pathways.impl.events.ConstraintMutationEvent;
import stroom.pathways.impl.events.NodeDiscoveryEvent;
import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.impl.events.PathwayEventType;
import stroom.pathways.impl.events.PathwayRootDiscoveryEvent;
import stroom.pathways.impl.events.RequiredConstraintAbsentEvent;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.Constraint;
import stroom.planb.impl.db.trace.NanoTimeUtil;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.unsafe.UnsafeByteBufferInput;
import jakarta.inject.Inject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class PathwayEventsSerde {

    private static final byte TYPE_ROOT_DISCOVERY = 0;
    private static final byte TYPE_NODE_DISCOVERY = 1;
    private static final byte TYPE_CONSTRAINT_DISCOVERY = 2;
    private static final byte TYPE_CONSTRAINT_MUTATION = 3;
    private static final byte TYPE_REQUIRED_CONSTRAINT_ABSENT = 4;

    private final ByteBufferFactory byteBufferFactory;
    private final PathwaySerde pathwaySerde;
    private int bufferSize = 128;

    @Inject
    public PathwayEventsSerde(final ByteBufferFactory byteBufferFactory, final PathwaySerde pathwaySerde) {
        this.byteBufferFactory = byteBufferFactory;
        this.pathwaySerde = pathwaySerde;
    }

    public void writePathwayEvents(final List<PathwayEvent> events, final Consumer<ByteBuffer> consumer) {
        try (final ByteBufferPoolOutput output =
                new ByteBufferPoolOutput(byteBufferFactory, bufferSize, -1)) {
            output.writeInt(events.size());
            events.forEach(event -> writeSingleEvent(event, output));
            output.flush();
            final ByteBuffer byteBuffer = output.getByteBuffer().flip();
            bufferSize = Math.max(bufferSize, byteBuffer.capacity());
            consumer.accept(byteBuffer);
        }
    }

    public void writePathwayEvent(final PathwayEvent event, final Consumer<ByteBuffer> consumer) {
        try (final ByteBufferPoolOutput output =
                new ByteBufferPoolOutput(byteBufferFactory, bufferSize, -1)) {
            writeSingleEvent(event, output);
            output.flush();
            final ByteBuffer byteBuffer = output.getByteBuffer().flip();
            bufferSize = Math.max(bufferSize, byteBuffer.capacity());
            consumer.accept(byteBuffer);
        }
    }

    private void writeSingleEvent(final PathwayEvent event, final Output output) {
        switch (event) {
            case final PathwayRootDiscoveryEvent e -> {
                output.writeByte(TYPE_ROOT_DISCOVERY);
                writePathwayRootDiscoveryEvent(e, output);
            }
            case final NodeDiscoveryEvent e -> {
                output.writeByte(TYPE_NODE_DISCOVERY);
                writeNodeDiscoveryEvent(e, output);
            }
            case final ConstraintDiscoveryEvent e -> {
                output.writeByte(TYPE_CONSTRAINT_DISCOVERY);
                writeConstraintDiscoveryEvent(e, output);
            }
            case final ConstraintMutationEvent e -> {
                output.writeByte(TYPE_CONSTRAINT_MUTATION);
                writeConstraintMutationEvent(e, output);
            }
            case final RequiredConstraintAbsentEvent e -> {
                output.writeByte(TYPE_REQUIRED_CONSTRAINT_ABSENT);
                writeRequiredConstraintAbsentEvent(e, output);
            }
            default -> throw new IllegalArgumentException("Unknown PathwayEvent type: " + event.getClass().getName());
        }
    }

    private void writePathwayRootDiscoveryEvent(final PathwayRootDiscoveryEvent event, final Output output) {
        writeUuid(event.getNodeUuid(), output);
        output.writeString(event.getNodeName());
        output.writeByte(event.getEventType().ordinal());
        writeNanoTime(event.getTimestamp(), output);
    }

    private void writeNodeDiscoveryEvent(final NodeDiscoveryEvent event, final Output output) {
        writeUuid(event.getParentUuid(), output);
        writeUuid(event.getNodeUuid(), output);
        output.writeString(event.getNodeName());
        output.writeByte(event.getEventType().ordinal());
        writeNanoTime(event.getTimestamp(), output);
    }

    private void writeConstraintDiscoveryEvent(final ConstraintDiscoveryEvent event, final Output output) {
        writeUuid(event.getNodeUuid(), output);
        //nodeName omitted since it can be inferred later
        output.writeByte(event.getEventType().ordinal());
        pathwaySerde.writeConstraint(event.getConstraint(), output);
        writeNanoTime(event.getTimestamp(), output);
    }

    private void writeConstraintMutationEvent(final ConstraintMutationEvent event, final Output output) {
        writeUuid(event.getNodeUuid(), output);
        //nodeName omitted since it can be inferred later
        output.writeByte(event.getEventType().ordinal());
        pathwaySerde.writeConstraint(event.getOriginalConstraint(), output);
        //Can skip name of updated constraint - indentical
        pathwaySerde.writeConstraintValue(event.getUpdatedConstraint().getValue(), output);
        output.writeBoolean(event.getUpdatedConstraint().isOptional());
        writeNanoTime(event.getTimestamp(), output);
    }

    private void writeRequiredConstraintAbsentEvent(final RequiredConstraintAbsentEvent event, final Output output) {
        writeUuid(event.getNodeUuid(), output);
        //nodeName omitted since it can be inferred later
        output.writeString(event.getConstraintName());
        output.writeByte(event.getEventType().ordinal());
        writeNanoTime(event.getTimestamp(), output);
    }

    private void writeUuid(final String uuidStr, final Output output) {
        final UUID uuid = UUID.fromString(uuidStr);
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private void writeNanoTime(final NanoTime timestamp, final Output output) {
        output.writeLong(NanoTimeUtil.toEpoch2000Nanos(timestamp));
    }

    public List<PathwayEvent> readPathwayEvents(final ByteBuffer byteBuffer, final Map<String, String> uuidToNameMap) {
        return readPathwayEvents(new UnsafeByteBufferInput(byteBuffer), uuidToNameMap);
    }

    private List<PathwayEvent> readPathwayEvents(final Input input, final Map<String, String> uuidToNameMap) {
        final int size = input.readInt();
        final List<PathwayEvent> events = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            events.add(readSingleEvent(input, uuidToNameMap));
        }
        return events;
    }

    public PathwayEvent readPathwayEvent(final ByteBuffer byteBuffer, final Map<String, String> uuidToNameMap) {
        return readSingleEvent(new UnsafeByteBufferInput(byteBuffer), uuidToNameMap);
    }

    private PathwayEvent readSingleEvent(final Input input, final Map<String, String> uuidToNameMap) {
        final byte type = input.readByte();
        switch (type) {
            case TYPE_ROOT_DISCOVERY -> {
                return readPathwayRootDiscoveryEvent(input);
            }
            case TYPE_NODE_DISCOVERY -> {
                return readNodeDiscoveryEvent(input);
            }
            case TYPE_CONSTRAINT_DISCOVERY -> {
                return readConstraintDiscoveryEvent(input, uuidToNameMap);
            }
            case TYPE_CONSTRAINT_MUTATION -> {
                return readConstraintMutationEvent(input, uuidToNameMap);
            }
            case TYPE_REQUIRED_CONSTRAINT_ABSENT -> {
                return readRequiredConstraintAbsentEvent(input, uuidToNameMap);
            }
            default -> throw new IllegalArgumentException("Unknown PathwayEvent type id: " + type);
        }
    }

    private PathwayRootDiscoveryEvent readPathwayRootDiscoveryEvent(final Input input) {
        final String uuid = readUuid(input);
        final String name = input.readString();
        final PathwayEventType eventType = readEventType(input);
        final NanoTime timestamp = readNanoTime(input);
        return new PathwayRootDiscoveryEvent(uuid, name, eventType, timestamp);
    }

    private NodeDiscoveryEvent readNodeDiscoveryEvent(final Input input) {
        final String parentUuid = readUuid(input);
        final String uuid = readUuid(input);
        final String name = input.readString();
        final PathwayEventType eventType = readEventType(input);
        final NanoTime timestamp = readNanoTime(input);
        return new NodeDiscoveryEvent(parentUuid, uuid, name, eventType, timestamp);
    }

    private ConstraintDiscoveryEvent readConstraintDiscoveryEvent(final Input input,
                                                                  final Map<String, String> uuidToNameMap) {
        final String uuid = readUuid(input);
        final PathwayEventType eventType = readEventType(input);
        final Constraint constraint = pathwaySerde.readConstraint(input);
        final NanoTime timestamp = readNanoTime(input);
        // Resolve the node name from the supplied map, falling back to the uuid. Uses a non-mutating
        // lookup so an immutable/shared map can be passed safely.
        final String name = uuidToNameMap != null ? uuidToNameMap.getOrDefault(uuid, uuid) : null;
        return new ConstraintDiscoveryEvent(uuid, name, constraint, eventType, timestamp);
    }

    private ConstraintMutationEvent readConstraintMutationEvent(final Input input,
                                                                final Map<String, String> uuidToNameMap) {
        final String uuid = readUuid(input);
        final PathwayEventType eventType = readEventType(input);
        final Constraint originalConstraint = pathwaySerde.readConstraint(input);
        final Constraint updatedConstraint = Constraint.builder()
                .name(originalConstraint.getName())
                .value(pathwaySerde.readConstraintValue(input))
                .optional(input.readBoolean())
                .build();
        final NanoTime timestamp = readNanoTime(input);
        // Resolve the node name from the supplied map, falling back to the uuid. Uses a non-mutating
        // lookup so an immutable/shared map can be passed safely.
        final String name = uuidToNameMap != null ? uuidToNameMap.getOrDefault(uuid, uuid) : null;
        return new ConstraintMutationEvent(uuid, name, originalConstraint, updatedConstraint, eventType, timestamp);
    }

    private RequiredConstraintAbsentEvent readRequiredConstraintAbsentEvent(final Input input,
                                                                            final Map<String, String> uuidToNameMap) {
        final String uuid = readUuid(input);
        final String constraintName = input.readString();
        final PathwayEventType eventType = readEventType(input);
        final NanoTime timestamp = readNanoTime(input);
        // Resolve the node name from the supplied map, falling back to the uuid. Uses a non-mutating
        // lookup so an immutable/shared map can be passed safely.
        final String name = uuidToNameMap != null ? uuidToNameMap.getOrDefault(uuid, uuid) : null;
        return new RequiredConstraintAbsentEvent(uuid, name, constraintName, eventType, timestamp);
    }

    private String readUuid(final Input input) {
        final long mostSigBits = input.readLong();
        final long leastSigBits = input.readLong();
        return new UUID(mostSigBits, leastSigBits).toString();
    }

    private PathwayEventType readEventType(final Input input) {
        return PathwayEventType.values()[input.readByte()];
    }

    private NanoTime readNanoTime(final Input input) {
        return NanoTimeUtil.fromEpoch2000Nanos(input.readLong());
    }

}
