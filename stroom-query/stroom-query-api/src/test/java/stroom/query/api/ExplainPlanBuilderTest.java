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

package stroom.query.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplainPlanBuilderTest {

    @Test
    void doesBuild() {
        final ExplainPlan child = ExplainPlan.builder()
                .description("Scan Events (full scan)")
                .estimatedRows(1_000L)
                .estimatedDurationMs(5L)
                .confidence(1.0)
                .notes(List.of("no per-row throughput signal"))
                .build();

        final ExplainPlan parent = ExplainPlan.builder()
                .description("Filter")
                .children(List.of(child))
                .build();

        assertThat(parent.getDescription()).isEqualTo("Filter");
        assertThat(parent.getChildren()).containsExactly(child);
        assertThat(parent.getEstimatedRows()).isNull();
        assertThat(child.getEstimatedRows()).isEqualTo(1_000L);
        assertThat(child.getEstimatedDurationMs()).isEqualTo(5L);
        assertThat(child.getConfidence()).isEqualTo(1.0);
        assertThat(child.getNotes()).containsExactly("no per-row throughput signal");
    }

    @Test
    void defaultsToEmptyChildrenAndNotes() {
        final ExplainPlan plan = ExplainPlan.builder().description("Scan").build();

        assertThat(plan.getChildren()).isEmpty();
        assertThat(plan.getNotes()).isEmpty();
    }

    @Test
    void rejectsNullDescription() {
        assertThatThrownBy(() -> new ExplainPlan(null, List.of(), null, null, null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalsAndHashCode_areStructural() {
        final ExplainPlan a = ExplainPlan.builder().description("Scan").estimatedRows(1L).build();
        final ExplainPlan b = ExplainPlan.builder().description("Scan").estimatedRows(1L).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
