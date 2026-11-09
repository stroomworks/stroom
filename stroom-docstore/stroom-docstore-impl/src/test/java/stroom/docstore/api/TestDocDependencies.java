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

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDocDependencies {

    private static final DocRef WORDS_1 = new DocRef("Dictionary", "uuid-words-1", "Words");
    private static final DocRef WORDS_2 = new DocRef("Dictionary", "uuid-words-2", "Words");

    @Test
    void of_marksNothingAmbiguous() {
        final DocDependencies dependencies = DocDependencies.of(Set.of(WORDS_1, WORDS_2));

        assertThat(dependencies.dependencies()).containsExactlyInAnyOrder(WORDS_1, WORDS_2);
        assertThat(dependencies.ambiguous()).isEmpty();
        assertThat(dependencies.isAmbiguous(WORDS_1)).isFalse();
    }

    @Test
    void of_nullOrEmptyIsEmpty() {
        assertThat(DocDependencies.of(null)).isEqualTo(DocDependencies.empty());
        assertThat(DocDependencies.of(Set.of())).isEqualTo(DocDependencies.empty());
        assertThat(DocDependencies.empty().dependencies()).isEmpty();
    }

    @Test
    void isAmbiguous() {
        final DocDependencies dependencies = new DocDependencies(Set.of(WORDS_1, WORDS_2), Set.of(WORDS_2));

        assertThat(dependencies.isAmbiguous(WORDS_1)).isFalse();
        assertThat(dependencies.isAmbiguous(WORDS_2)).isTrue();
    }

    @Test
    void ambiguousMustBeDependencies() {
        assertThatThrownBy(() -> new DocDependencies(Set.of(WORDS_1), Set.of(WORDS_2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullsRejected() {
        assertThatThrownBy(() -> new DocDependencies(null, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DocDependencies(Set.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void copiesItsInput() {
        final Set<DocRef> input = new HashSet<>(Set.of(WORDS_1));
        final DocDependencies dependencies = DocDependencies.of(input);

        input.add(WORDS_2);

        assertThat(dependencies.dependencies()).containsExactly(WORDS_1);
        assertThatThrownBy(() -> dependencies.dependencies().add(WORDS_2))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
