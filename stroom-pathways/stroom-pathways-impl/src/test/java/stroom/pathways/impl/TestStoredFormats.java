/*
 * Copyright 2026 Crown Copyright
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

import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.pathways.shared.PathwaySummary;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a stored pathway does when it was laid out by a different build.
 *
 * <p>Neither the pathway nor the mutation layout carries field names — they are read back by position
 * — so a row from a build that ordered the fields differently cannot be told from a current one by its
 * contents. Each carries a byte saying which shape follows, and reading refuses anything else rather
 * than returning whatever the bytes happened to mean.
 */
class TestStoredFormats {

    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();

    @Test
    void aPathwayWrittenInAnotherFormatIsRefused() {
        final ByteBuffer stored = write();

        assertThat(new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(stored.duplicate()).getName())
                .as("the fixture only says something if it reads back as itself first")
                .isEqualTo("GET /orders");

        final ByteBuffer foreign = stored.duplicate();
        foreign.put(0, (byte) (foreign.get(0) + 1));

        assertThatThrownBy(() -> new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(foreign))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pathways table has to be cleared");
    }

    @Test
    void aSummaryRefusesItToo() {
        // The list reads only the header of each pathway, so it has to check the same byte — otherwise
        // a page of rows comes back with times and sizes read out of the wrong bytes.
        final ByteBuffer foreign = write();
        foreign.put(0, (byte) (foreign.get(0) + 1));

        assertThatThrownBy(() -> new PathwaySerde(BYTE_BUFFER_FACTORY).readSummary(foreign))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pathways table has to be cleared");
    }

    @Test
    void aStoredPathwayKeepsItsCounts() {
        final Pathway read = new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(write());

        assertThat(read.getTimesUsed()).isEqualTo(4207);
        assertThat(read.getTimesUpdated()).isEqualTo(31);
    }

    @Test
    void aSummaryReadsTheCountsToo() {
        // The list stops reading before the model, so the counts have to sit ahead of it. Were they
        // written after, a row would show whatever the first bytes of the path key came to.
        final PathwaySummary summary = new PathwaySerde(BYTE_BUFFER_FACTORY).readSummary(write());

        assertThat(summary.getTimesUsed()).isEqualTo(4207);
        assertThat(summary.getTimesUpdated()).isEqualTo(31);
        assertThat(summary.getLastUsedTime()).isEqualTo(NanoTime.ofMillis(3));
    }

    private static ByteBuffer write() {
        final Pathway pathway = Pathway.builder()
                .name("GET /orders")
                .createTime(NanoTime.ofMillis(1))
                .updateTime(NanoTime.ofMillis(2))
                .lastUsedTime(NanoTime.ofMillis(3))
                .timesUsed(4207)
                .timesUpdated(31)
                .pathKey(new NamePathKey("GET /orders"))
                .root(new PathNode("GET /orders"))
                .build();

        final ByteBuffer[] written = new ByteBuffer[1];
        new PathwaySerde(BYTE_BUFFER_FACTORY).writePathway(pathway, 0, buffer -> {
            final ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining());
            copy.put(buffer).flip();
            written[0] = copy;
        });
        return written[0];
    }
}
