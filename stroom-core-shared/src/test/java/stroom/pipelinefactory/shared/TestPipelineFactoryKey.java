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

package stroom.pipelinefactory.shared;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPipelineFactoryKey {

    private static final Map<String, String> SYSLOG = values("SYSLOG", "Raw Events");

    @Test
    void aPatternHasTheCombinationsValuesWrittenIn() {
        assertThat(PipelineFactoryKey.substitute("Feeds/${feed}/${type}", SYSLOG))
                .isEqualTo("Feeds/SYSLOG/Raw Events");
        assertThat(PipelineFactoryKey.substitute("${feed}", SYSLOG)).isEqualTo("SYSLOG");
        assertThat(PipelineFactoryKey.substitute("fixed", SYSLOG)).isEqualTo("fixed");
        assertThat(PipelineFactoryKey.substitute(null, SYSLOG)).isEmpty();
    }

    @Test
    void aPatternsNamesAreReadInOrder() {
        assertThat(PipelineFactoryKey.placeholders("${type} of ${feed}, ${type}"))
                .containsExactly("type", "feed", "type");
        assertThat(PipelineFactoryKey.placeholders("no names")).isEmpty();
        assertThat(PipelineFactoryKey.placeholders(null)).isEmpty();
    }

    @Test
    void aPatternThatIsNotClosedOrNamesWhatIsNotThereIsRefused() {
        assertThatThrownBy(() -> PipelineFactoryKey.placeholders("${feed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'${feed' opens a ${ it does not close");
        assertThatThrownBy(() -> PipelineFactoryKey.substitute("${pipeline}", SYSLOG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'${pipeline}' names ${pipeline}, which is not in the key");
    }

    @Test
    void aFieldIsNamedInLowerCase() {
        assertThat(PipelineFactoryKey.placeholder("Feed")).isEqualTo("feed");
        assertThat(PipelineFactoryKey.FIELDS).containsExactly("Feed", "Type", "Pipeline");
    }

    private static Map<String, String> values(final String feed, final String type) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("Feed", feed);
        values.put("Type", type);
        return values;
    }
}
