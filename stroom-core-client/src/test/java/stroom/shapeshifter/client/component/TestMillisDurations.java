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

package stroom.shapeshifter.client.component;

import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestMillisDurations {

    private static final long SECOND = 1000L;
    private static final long DAY = 24L * 60 * 60 * SECOND;

    @Test
    void testShownInTheLargestExactUnit() {
        assertThat(MillisDurations.toDuration(60 * SECOND)).isEqualTo(new SimpleDuration(1L, TimeUnit.MINUTES));
        assertThat(MillisDurations.toDuration(90 * SECOND)).isEqualTo(new SimpleDuration(90L, TimeUnit.SECONDS));
        assertThat(MillisDurations.toDuration(DAY)).isEqualTo(new SimpleDuration(1L, TimeUnit.DAYS));
        assertThat(MillisDurations.toDuration(14 * DAY)).isEqualTo(new SimpleDuration(2L, TimeUnit.WEEKS));
        assertThat(MillisDurations.toDuration(36 * 60 * 60 * SECOND))
                .isEqualTo(new SimpleDuration(36L, TimeUnit.HOURS));
    }

    @Test
    void testNeverShownInMonthsOrYears() {
        assertThat(MillisDurations.toDuration(31 * DAY)).isEqualTo(new SimpleDuration(31L, TimeUnit.DAYS));
        assertThat(MillisDurations.toDuration(365 * DAY)).isEqualTo(new SimpleDuration(365L, TimeUnit.DAYS));
    }

    @Test
    void testPartSecondsShownAsTheNearestWholeSecond() {
        assertThat(MillisDurations.toDuration(1500L)).isEqualTo(new SimpleDuration(2L, TimeUnit.SECONDS));
        assertThat(MillisDurations.toDuration(1L)).isEqualTo(new SimpleDuration(1L, TimeUnit.SECONDS));
        assertThat(MillisDurations.toDuration(0L)).isEqualTo(new SimpleDuration(1L, TimeUnit.SECONDS));
    }

    @Test
    void testWhatWasReadIsKeptWhileUnchanged() {
        assertThat(MillisDurations.toMillis(MillisDurations.toDuration(1500L), 1500L)).isEqualTo(1500L);
        assertThat(MillisDurations.toMillis(null, 1500L)).isEqualTo(1500L);
        assertThat(MillisDurations.toMillis(MillisDurations.toDuration(DAY), DAY)).isEqualTo(DAY);
    }

    @Test
    void testAChosenDurationIsWritten() {
        assertThat(MillisDurations.toMillis(new SimpleDuration(5L, TimeUnit.MINUTES), 60 * SECOND))
                .isEqualTo(300 * SECOND);
        assertThat(MillisDurations.toMillis(new SimpleDuration(3L, TimeUnit.SECONDS), 1500L))
                .isEqualTo(3 * SECOND);
        assertThat(MillisDurations.toMillis(new SimpleDuration(1L, TimeUnit.MONTHS), DAY))
                .isEqualTo(31 * DAY);
    }
}
