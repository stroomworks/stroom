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

import stroom.planb.impl.db.HashClashCommitRunnable;
import stroom.planb.impl.db.LmdbWriter;
import stroom.planb.impl.db.PlanBEnv;
import stroom.planb.impl.db.trace.PathwaysDb.SimpleDb;
import stroom.planb.shared.StateSettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.PutFlags;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.planb.impl.db.trace.NanoTimeUtil;
import stroom.planb.impl.serde.time.NanoTimeSerde;

import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.pathways.impl.events.ConstraintDiscoveryEvent;
import stroom.pathways.impl.events.ConstraintMutationEvent;
import stroom.pathways.impl.events.NodeDiscoveryEvent;
import stroom.pathways.impl.events.PathwayEvent;
import stroom.pathways.impl.events.PathwayEventType;
import stroom.pathways.impl.events.PathwayRootDiscoveryEvent;
import stroom.pathways.impl.events.RequiredConstraintAbsentEvent;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.StringValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class TestPathwaySerde {

    @Test
    void test(@TempDir final Path pathwaysDir) {
        final StateSettings settings = new StateSettings.Builder().build();
        final HashClashCommitRunnable hashClashCommitRunnable = new HashClashCommitRunnable();
        final PlanBEnv env = new PlanBEnv(pathwaysDir,
                settings.getMaxStoreSize(),
                20,
                false,
                hashClashCommitRunnable);
        final SimpleDb sdb = new SimpleDb(
                env,
                env.openDbi("processing-status", DbiFlags.MDB_CREATE),
                new PutFlags[]{});

        try (final LmdbWriter writer = env.createWriter()) {
            final ByteBuffer key1 = ByteBuffer.allocateDirect(4);
            key1.putInt(1).flip();
            final ByteBuffer val1 = ByteBuffer.allocateDirect(4);
            val1.putInt(100).flip();
            sdb.insert(writer, key1, val1);

            final ByteBuffer key2 = ByteBuffer.allocateDirect(8);
            key2.putInt(2);
            key2.putInt(3);
            key2.flip();
            final ByteBuffer val2 = ByteBuffer.allocateDirect(8);
            val2.putInt(200);
            val2.putInt(255);
            val2.flip();
            sdb.insert(writer, key2, val2);

            final ByteBuffer key3 = ByteBuffer.allocateDirect(4);
            key3.putInt(4).flip();
            final String myString = "test string data";
            final byte[] stringBytes = myString.getBytes(StandardCharsets.UTF_8);
            final ByteBuffer val3 = ByteBuffer.allocateDirect(stringBytes.length);
            val3.put(stringBytes).flip();
            sdb.insert(writer, key3, val3);
        }

        final ByteBuffer key1Read = ByteBuffer.allocateDirect(4);
        key1Read.putInt(1).flip();
        final Integer readVal1 = sdb.get(key1Read, bb -> bb == null ? null : bb.getInt());
        assertThat(readVal1).isEqualTo(100);

        final ByteBuffer key2Read = ByteBuffer.allocateDirect(8);
        key2Read.putInt(2);
        key2Read.putInt(3);
        key2Read.flip();
        final int[] readVal2 = sdb.get(key2Read, bb -> {
            if (bb == null) return null;
            return new int[]{bb.getInt(), bb.getInt()};
        });
        assertThat(readVal2).containsExactly(200, 255);

        final ByteBuffer key3Read = ByteBuffer.allocateDirect(4);
        key3Read.putInt(4).flip();
        final String readStr = sdb.get(key3Read, bb -> {
            if (bb == null) return null;
            final byte[] arr = new byte[bb.remaining()];
            bb.get(arr);
            return new String(arr, StandardCharsets.UTF_8);
        });
        assertThat(readStr).isEqualTo("test string data");
    }

    @Test
    void testNanoTimeSerde(@TempDir final Path pathwaysDir) {
        final StateSettings settings = new StateSettings.Builder().build();
        final HashClashCommitRunnable hashClashCommitRunnable = new HashClashCommitRunnable();
        final PlanBEnv env = new PlanBEnv(pathwaysDir, settings.getMaxStoreSize(), 20, false, hashClashCommitRunnable);
        final SimpleDb sdb = new SimpleDb(env, env.openDbi("processing-status-nanotime", DbiFlags.MDB_CREATE), new PutFlags[]{});

        final NanoTimeSerde nanoTimeSerde = new NanoTimeSerde();
        final String randomNanoTimeStr = String.valueOf(System.currentTimeMillis() * 1_000_000L + (long)(Math.random() * 1_000_000L));
        final NanoTime originalNanoTime = NanoTime.fromString(randomNanoTimeStr);

        try (final LmdbWriter writer = env.createWriter()) {
            final ByteBuffer key = ByteBuffer.allocateDirect(4);
            key.putInt(1).flip();
            final ByteBuffer val = ByteBuffer.allocateDirect(nanoTimeSerde.getSize());
            nanoTimeSerde.write(val, NanoTimeUtil.toInstant(originalNanoTime));
            val.flip();
            sdb.insert(writer, key, val);
        }

        final ByteBuffer keyRead = ByteBuffer.allocateDirect(4);
        keyRead.putInt(1).flip();
        final String readStr = sdb.get(keyRead, bb -> {
            if (bb == null) return null;
            final Instant instant = nanoTimeSerde.read(bb);
            return NanoTimeUtil.fromInstant(instant).toNanoEpochString();
        });

        assertThat(readStr).isEqualTo(randomNanoTimeStr);
    }

    private void assertEventSerdeRoundTrip(final PathwayEvent expectedEvent, final Map<String, String> uuidToNameMap) {
        final ByteBufferFactory byteBufferFactory = new ByteBufferFactoryImpl();
        final PathwaySerde pathwaySerde = new PathwaySerde(byteBufferFactory);
        final PathwayEventsSerde serde = new PathwayEventsSerde(byteBufferFactory, pathwaySerde);
        
        final List<PathwayEvent> expectedEvents = List.of(expectedEvent);
        final AtomicReference<ByteBuffer> bufferRef = new AtomicReference<>();
        serde.writePathwayEvents(expectedEvents, bufferRef::set);
        
        final ByteBuffer byteBuffer = bufferRef.get();
        final List<PathwayEvent> actualEvents = serde.readPathwayEvents(byteBuffer, uuidToNameMap != null ? uuidToNameMap : new HashMap<>());
        
        assertThat(actualEvents).hasSize(1);
        final PathwayEvent actual = actualEvents.get(0);
        
        assertThat(actual.getNodeUuid()).isEqualTo(expectedEvent.getNodeUuid());
        assertThat(actual.getNodeName()).isEqualTo(expectedEvent.getNodeName());
        assertThat(actual.getEventType()).isEqualTo(expectedEvent.getEventType());
        assertThat(actual.getTimestamp().getSeconds()).isEqualTo(expectedEvent.getTimestamp().getSeconds());
        assertThat(actual.getTimestamp().getNanos()).isEqualTo(expectedEvent.getTimestamp().getNanos());
        
        if (expectedEvent instanceof NodeDiscoveryEvent expectedNodeEvent && actual instanceof NodeDiscoveryEvent actualNodeEvent) {
            assertThat(actualNodeEvent.getParentUuid()).isEqualTo(expectedNodeEvent.getParentUuid());
        }
        if (expectedEvent instanceof RequiredConstraintAbsentEvent expectedAbsent && actual instanceof RequiredConstraintAbsentEvent actualAbsent) {
            assertThat(actualAbsent.getConstraintName()).isEqualTo(expectedAbsent.getConstraintName());
        }
        if (expectedEvent instanceof ConstraintDiscoveryEvent expectedCd && actual instanceof ConstraintDiscoveryEvent actualCd) {
            assertThat(actualCd.getConstraint().getName()).isEqualTo(expectedCd.getConstraint().getName());
        }
        if (expectedEvent instanceof ConstraintMutationEvent expectedCm && actual instanceof ConstraintMutationEvent actualCm) {
            assertThat(actualCm.getOriginalConstraint().getName()).isEqualTo(expectedCm.getOriginalConstraint().getName());
            assertThat(actualCm.getUpdatedConstraint().getName()).isEqualTo(expectedCm.getUpdatedConstraint().getName());
        }
    }

    @Test
    void testPathwayRootDiscoveryEventSerde() {
        final PathwayRootDiscoveryEvent event = new PathwayRootDiscoveryEvent(
                UUID.randomUUID().toString(), "rootNode", PathwayEventType.MUTATION, NanoTimeUtil.now());
        assertEventSerdeRoundTrip(event, null);
    }

    @Test
    void testNodeDiscoveryEventSerde() {
        final NodeDiscoveryEvent event = new NodeDiscoveryEvent(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "childNode", PathwayEventType.INFO, NanoTimeUtil.now());
        assertEventSerdeRoundTrip(event, null);
    }

    @Test
    void testConstraintDiscoveryEventSerde() {
        final String uuid = UUID.randomUUID().toString();
        final Constraint constraint = Constraint.builder().name("c1").value(new StringValue("v1")).optional(false).build();
        final ConstraintDiscoveryEvent event = new ConstraintDiscoveryEvent(uuid, "cNode", constraint, PathwayEventType.MUTATION, NanoTimeUtil.now());
        assertEventSerdeRoundTrip(event, Map.of(uuid, "cNode"));
    }

    @Test
    void testConstraintMutationEventSerde() {
        final String uuid = UUID.randomUUID().toString();
        final Constraint original = Constraint.builder().name("c1").value(new StringValue("v1")).optional(false).build();
        final Constraint updated = Constraint.builder().name("c1").value(new StringValue("v2")).optional(false).build();
        final ConstraintMutationEvent event = new ConstraintMutationEvent(uuid, "cNode", original, updated, PathwayEventType.MUTATION, NanoTimeUtil.now());
        assertEventSerdeRoundTrip(event, Map.of(uuid, "cNode"));
    }

    @Test
    void testRequiredConstraintAbsentEventSerde() {
        final String uuid = UUID.randomUUID().toString();
        final RequiredConstraintAbsentEvent event = new RequiredConstraintAbsentEvent(uuid, "cNode", "absentName", PathwayEventType.VIOLATION, NanoTimeUtil.now());
        assertEventSerdeRoundTrip(event, Map.of(uuid, "cNode"));
    }

    @Test
    void testAllPathwayEventsSerde() {
        testPathwayRootDiscoveryEventSerde();
        testNodeDiscoveryEventSerde();
        testConstraintDiscoveryEventSerde();
        testConstraintMutationEventSerde();
        testRequiredConstraintAbsentEventSerde();
    }
}