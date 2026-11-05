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

package stroom.dashboard.shared;

import stroom.query.api.ExpressionOperator;
import stroom.test.common.TestUtil;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestStroomQLQueryComponentSettings {

    private static final StroomQLQueryComponentSettings SETTINGS = StroomQLQueryComponentSettings.builder()
            .query("from Events where UserId = ${user} select Count")
            .automate(Automate.builder().open(true).build())
            .selectionQuery(List.of(new ComponentSelectionHandler(
                    "handler-1",
                    "table-1",
                    ExpressionOperator.builder().build(),
                    true)))
            .build();

    @Test
    void testSerialisation() {
        // Read as one of the dashboard's component settings, as a dashboard holds it
        final ComponentSettings result = TestUtil.testSerialisation(SETTINGS, ComponentSettings.class);

        assertThat(result)
                .isEqualTo(SETTINGS);
    }

    @Test
    void testSerialisation_type() {
        final String json = JsonUtil.writeValueAsString(SETTINGS, false);

        assertThat(json)
                .contains("\"type\":\"stroomql-query\"");
    }

    @Test
    void testSerialisation_empty() {
        final StroomQLQueryComponentSettings settings = StroomQLQueryComponentSettings.builder().build();

        final ComponentSettings result = TestUtil.testSerialisation(settings, ComponentSettings.class);

        assertThat(result)
                .isEqualTo(settings);
    }

    @Test
    void testCopy() {
        final StroomQLQueryComponentSettings copy = SETTINGS.copy().build();

        assertThat(copy)
                .isEqualTo(SETTINGS);
        assertThat(SETTINGS.copy().query("from Other select Count").build().getQuery())
                .isEqualTo("from Other select Count");
    }
}
