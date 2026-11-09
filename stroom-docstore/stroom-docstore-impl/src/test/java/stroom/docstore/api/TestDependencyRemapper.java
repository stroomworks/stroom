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

package stroom.docstore.api;

import stroom.docref.DocRef;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDependencyRemapper {

    private static final DocRef WORDS_1 = new DocRef("Dictionary", "uuid-words-1", "Words");
    private static final DocRef WORDS_2 = new DocRef("Dictionary", "uuid-words-2", "Words");
    private static final DocRef GEO_IP = new DocRef("Dictionary", "uuid-geo-ip", "GeoIP");

    @Test
    void recordAmbiguous_recordsADependencyMarkedAmbiguous() {
        final DependencyRemapper remapper = new DependencyRemapper();

        remapper.recordAmbiguous(WORDS_1);
        remapper.recordAmbiguous(WORDS_2);
        remapper.record(GEO_IP);

        final DocDependencies dependencies = remapper.getDocDependencies();
        assertThat(dependencies.dependencies()).containsExactlyInAnyOrder(WORDS_1, WORDS_2, GEO_IP);
        assertThat(dependencies.ambiguous()).containsExactly(WORDS_1, WORDS_2);
        assertThat(remapper.getDependencies()).containsExactlyInAnyOrder(WORDS_1, WORDS_2, GEO_IP);
    }

    @Test
    void recordAmbiguous_definiteRecordingTakesPrecedence() {
        final DependencyRemapper remapper = new DependencyRemapper();

        // Recorded both ways, in either order, the document is depended on definitely
        remapper.recordAmbiguous(WORDS_1);
        remapper.record(WORDS_1);
        remapper.remap(WORDS_2);
        remapper.recordAmbiguous(WORDS_2);

        assertThat(remapper.getDocDependencies().ambiguous()).isEmpty();
    }

    @Test
    void recordAmbiguous_doesNotReportAChange() {
        final DependencyRemapper remapper = new DependencyRemapper(Map.of(WORDS_1, WORDS_2));

        remapper.recordAmbiguous(WORDS_1);

        // Like record(), and unlike remap(), nothing is substituted, so the copy path must not write
        assertThat(remapper.isChanged()).isFalse();
    }

    @Test
    void recordAmbiguous_rejectsNull() {
        final DependencyRemapper remapper = new DependencyRemapper();

        assertThatThrownBy(() -> remapper.recordAmbiguous(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getDocDependencies_noneAmbiguousByDefault() {
        final DependencyRemapper remapper = new DependencyRemapper();

        remapper.remap(GEO_IP);
        remapper.remap(null);

        assertThat(remapper.getDocDependencies()).isEqualTo(DocDependencies.of(Set.of(GEO_IP)));
    }
}
