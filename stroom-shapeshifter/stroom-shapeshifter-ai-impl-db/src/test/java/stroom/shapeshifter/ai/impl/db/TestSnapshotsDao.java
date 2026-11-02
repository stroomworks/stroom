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

package stroom.shapeshifter.ai.impl.db;

import stroom.shapeshifter.ai.stage.Snapshots.Snapshot;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The snapshots of A61 as rows: kept, found by id and by binding, moved with a draft's approval, and gone
/// with the binding (A71).
class TestSnapshotsDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String BINDING = "rule-" + System.nanoTime();
    private static final String DRAFT = "draft-" + System.nanoTime();

    @Inject
    private SnapshotsDao snapshots;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        snapshots.discard(BINDING);
        snapshots.discard(DRAFT);
    }

    @Test
    void aSnapshotComesBackWholeByItsIdAndInOrderByItsBinding() {
        snapshots.keep(snapshot("s1", BINDING, "{\"elements\":[]}", Map.of("xsltFilter", "<xsl/>"), Map.of("f", "v1")));
        snapshots.keep(snapshot("s2", BINDING, "{\"elements\":[]}", Map.of("xsltFilter", "<xsl2/>"), Map.of("f",
                "v2")));

        final Snapshot found = snapshots.byId("s2").orElseThrow();
        assertThat(found.bindingUuid()).isEqualTo(BINDING);
        assertThat(found.code()).containsEntry("xsltFilter", "<xsl2/>");
        assertThat(found.versions()).containsEntry("f", "v2");
        assertThat(found.structure()).isEqualTo("{\"elements\":[]}");
        assertThat(snapshots.forBinding(BINDING)).extracting(Snapshot::id).containsExactly("s1", "s2");
        assertThat(snapshots.byId("nothing")).isEmpty();
    }

    @Test
    void aDraftsSnapshotsMoveToTheBindingItReplacedAndGoWithTheBinding() {
        snapshots.keep(snapshot("s1", BINDING, "{}", Map.of(), Map.of()));
        snapshots.keep(snapshot("d1", DRAFT, "{}", Map.of(), Map.of()));

        snapshots.move(DRAFT, BINDING);
        assertThat(snapshots.forBinding(BINDING)).extracting(Snapshot::id).containsExactly("s1", "d1");
        assertThat(snapshots.forBinding(DRAFT)).isEmpty();

        snapshots.discard(BINDING);
        assertThat(snapshots.forBinding(BINDING)).isEmpty();
        assertThat(snapshots.byId("d1")).isEmpty();
    }

    private static Snapshot snapshot(final String id,
                                     final String binding,
                                     final String structure,
                                     final Map<String, String> code,
                                     final Map<String, String> versions) {
        return new Snapshot(id, DOC, binding, "fragment-1", structure, code, versions, 1_000L);
    }
}
