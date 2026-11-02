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

package stroom.shapeshifter.ai.learning;

import stroom.meta.shared.MetaFields;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// What an attempt shows the model of its stream (A126): the feed and the type, feed first, and nothing
/// else the stream carries, since nothing else is asked for.
class TestSample {

    @Test
    void keepsTheFeedAndTheTypeInThatOrder() {
        final Sample sample = Sample.of("text", "SYSLOG", "Raw Events");

        assertThat(sample.headers()).containsExactly(
                Map.entry(MetaFields.FIELD_FEED, "SYSLOG"),
                Map.entry(MetaFields.FIELD_TYPE, "Raw Events"));
    }

    @Test
    void aFieldTheStreamLacksIsAbsent() {
        final Sample sample = Sample.of("text", null, "Raw Events");

        assertThat(sample.headers()).containsExactly(Map.entry(MetaFields.FIELD_TYPE, "Raw Events"));
    }
}
