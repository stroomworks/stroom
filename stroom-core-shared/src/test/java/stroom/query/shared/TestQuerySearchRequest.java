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

package stroom.query.shared;

import stroom.query.api.GroupSelection;
import stroom.query.api.OffsetRange;
import stroom.test.common.TestUtil;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestQuerySearchRequest {

    private static final QueryComponentRequest COMPONENT_REQUEST = new QueryComponentRequest(
            "pane1",
            QueryTablePreferences.builder().pageSize(50).build(),
            new OffsetRange(10, 5),
            GroupSelection.builder().openGroups(Set.of("group1")).build());

    @Test
    void testSerialisation_componentRequests() {
        final QuerySearchRequest request = QuerySearchRequest.builder()
                .query("from Events select Count")
                .componentRequests(List.of(
                        COMPONENT_REQUEST,
                        new QueryComponentRequest("pane2", null, null, null)))
                .build();

        final QuerySearchRequest result = TestUtil.testSerialisation(request, QuerySearchRequest.class);

        assertThat(result.getComponentRequests())
                .containsExactly(
                        COMPONENT_REQUEST,
                        new QueryComponentRequest("pane2", null, null, null));
    }

    @Test
    void testDeserialisation_noComponentRequests() {
        // A request from a client that doesn't know about component requests asks for the query's own
        // table and visualisation
        final QuerySearchRequest result = JsonUtil.readValue(
                "{\"query\": \"from Events select Count\"}",
                QuerySearchRequest.class);

        assertThat(result.getComponentRequests())
                .isNull();
    }

    @Test
    void testCopy_keepsComponentRequests() {
        final QuerySearchRequest request = QuerySearchRequest.builder()
                .componentRequests(List.of(COMPONENT_REQUEST))
                .build();

        final QuerySearchRequest copy = request.copy().build();

        assertThat(copy.getComponentRequests())
                .containsExactly(COMPONENT_REQUEST);
    }
}
