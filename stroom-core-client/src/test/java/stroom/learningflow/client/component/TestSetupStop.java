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

package stroom.learningflow.client.component;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSetupStop {

    @Test
    void aSectionsKeyIsReadBackAsTheSameStop() {
        for (final SetupSection section : SetupSection.values()) {
            final SetupStop stop = SetupStop.of(section);
            assertThat(SetupStop.parse(stop.key())).isEqualTo(stop);
            assertThat(stop.isStep()).isFalse();
            assertThat(stop.getSection()).isEqualTo(section);
        }
    }

    @Test
    void aStepsKeyIsReadBackAsTheSameStep() {
        final SetupStop stop = SetupStop.step(3);
        assertThat(stop.key()).isEqualTo("step:3");
        assertThat(SetupStop.parse("step:3")).isEqualTo(stop);
        assertThat(stop.isStep()).isTrue();
        assertThat(stop.getStep()).isEqualTo(3);
        assertThat(stop.getSection()).isNull();
    }

    @Test
    void aStepAndASectionAreNeverTheSameStop() {
        assertThat(SetupStop.step(0)).isNotEqualTo(SetupStop.of(SetupSection.LEARNING));
        assertThat(SetupStop.step(1)).isNotEqualTo(SetupStop.step(2));
        assertThat(SetupStop.of(SetupSection.QUALITY)).hasSameHashCodeAs(SetupStop.of(SetupSection.QUALITY));
    }

    @Test
    void whatIsNotAStopsKeyIsNone() {
        assertThat(SetupStop.parse(null)).isNull();
        assertThat(SetupStop.parse("")).isNull();
        assertThat(SetupStop.parse("step:")).isNull();
        assertThat(SetupStop.parse("step:two")).isNull();
        assertThat(SetupStop.parse("step:-1")).isNull();
        assertThat(SetupStop.parse("section:NOWHERE")).isNull();
        assertThat(SetupStop.parse("QUALITY")).isNull();
    }

    @Test
    void aStepCantBeNegative() {
        assertThatThrownBy(() -> SetupStop.step(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
